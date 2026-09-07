package reach

import java.io.File
import java.util.zip.ZipFile

/**
 * 만든 GTFS 를 다시 읽어 검사한다.
 *
 * **왜 직접 쓰나.** MobilityData 의 공식 validator 가 있고 그게 더 꼼꼼하다. 그걸
 * 쓰기 전에 이걸 두는 이유는 **이 파이프라인이 사람 없이 도는 것**이기 때문이다.
 * 수집 스케줄이 하루 다섯 번 돌면서 GTFS 를 다시 만드는데, 그때마다 사람이 jar 를
 * 내려받아 돌려볼 수는 없다. 빌드 직후 자동으로 도는 검사가 하나는 있어야 한다.
 *
 * 검사는 두 층이다:
 *
 * * **규격** — 참조 무결성, 필수 필드, 정렬. 틀리면 라우터가 아예 못 읽는다
 * * **말이 되는가** — 정류장 간 함축 속도, 노선 총 소요시간. 규격은 맞는데 버스가
 *   시속 200km 로 달리는 파일은 검증기를 통과한다. 그건 우리가 봐야 한다
 *
 * 두 번째가 실제로 잡아낸 것들이 [주의] 로 나온다.
 */
object GtfsCheck {

    private const val REQUIRED_HEADERS = "agency.txt stops.txt routes.txt trips.txt stop_times.txt"

    private class Table(val name: String, val header: List<String>, val rows: List<List<String>>) {
        private val idx = header.withIndex().associate { (i, h) -> h to i }
        fun col(row: List<String>, field: String): String =
            idx[field]?.let { row.getOrNull(it) } ?: ""
        fun has(field: String) = field in idx
    }

    fun run(zipFile: File) {
        require(zipFile.exists()) { "GTFS 파일이 없다: ${zipFile.absolutePath}" }
        val errors = ArrayList<String>()
        val warns = ArrayList<String>()

        ZipFile(zipFile).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            for (need in REQUIRED_HEADERS.split(" ")) {
                if (need !in names) errors += "필수 파일 없음: $need"
            }
            if (errors.isNotEmpty()) { report(zipFile, errors, warns); return }

            fun read(name: String): Table? {
                val e = zip.getEntry(name) ?: return null
                val lines = zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readLines()
                    .filter { it.isNotBlank() }
                if (lines.isEmpty()) return null
                return Table(name, split(lines[0]), lines.drop(1).map { split(it) })
            }

            val stops = read("stops.txt")!!
            val routes = read("routes.txt")!!
            val trips = read("trips.txt")!!
            val stopTimes = read("stop_times.txt")!!
            val cal = read("calendar.txt")
            val freq = read("frequencies.txt")
            val transfers = read("transfers.txt")

            // ── 참조 무결성 ────────────────────────────────────────
            val stopIds = HashMap<String, Pair<Double, Double>>(stops.rows.size * 2)
            for (r in stops.rows) {
                val id = stops.col(r, "stop_id")
                if (id.isEmpty()) { errors += "stops.txt: 빈 stop_id"; continue }
                if (id in stopIds) errors += "stops.txt: stop_id 중복 $id"
                val la = stops.col(r, "stop_lat").toDoubleOrNull()
                val lo = stops.col(r, "stop_lon").toDoubleOrNull()
                if (la == null || lo == null) { errors += "stops.txt: 좌표 없음 $id"; continue }
                // 수도권을 한참 벗어난 좌표는 파싱이 어긋난 것이다
                if (la !in 33.0..39.0 || lo !in 124.0..132.0) {
                    errors += "stops.txt: 좌표가 한국 밖 $id ($la, $lo)"
                }
                stopIds[id] = la to lo
            }

            val routeIds = HashSet<String>()
            for (r in routes.rows) {
                val id = routes.col(r, "route_id")
                if (!routeIds.add(id)) errors += "routes.txt: route_id 중복 $id"
                val t = routes.col(r, "route_type").toIntOrNull()
                if (t == null || t !in 0..12) errors += "routes.txt: route_type 이상 $id=$t"
            }

            val serviceIds = cal?.rows?.map { cal.col(it, "service_id") }?.toHashSet() ?: HashSet()
            val tripIds = HashSet<String>()
            val tripRoute = HashMap<String, String>()
            for (r in trips.rows) {
                val id = trips.col(r, "trip_id")
                if (!tripIds.add(id)) errors += "trips.txt: trip_id 중복 $id"
                val rid = trips.col(r, "route_id")
                if (rid !in routeIds) errors += "trips.txt: 없는 route_id $rid"
                tripRoute[id] = rid
                val sid = trips.col(r, "service_id")
                if (serviceIds.isNotEmpty() && sid !in serviceIds) {
                    errors += "trips.txt: 없는 service_id $sid"
                }
                val d = trips.col(r, "direction_id")
                if (d.isNotEmpty() && d != "0" && d != "1") {
                    errors += "trips.txt: direction_id 는 0 또는 1 이어야 한다 ($id=$d)"
                }
            }

            // ── stop_times ─────────────────────────────────────────
            // 한 운행의 행들이 이어져 있다고 가정하고 한 번만 훑는다. 파일이 60만 줄이라
            // trip 별로 모아 담으면 힙이 아깝다.
            val usedStops = HashSet<String>(stopIds.size * 2)
            val tripStopCount = HashMap<String, Int>(tripIds.size * 2)
            val tripTotalSec = HashMap<String, Int>(tripIds.size * 2)
            var lastTrip = ""
            var lastSeq = 0
            var lastSec = 0
            var lastStop = ""
            var fastSegments = 0
            var slowSegments = 0
            var zeroTime = 0
            val seenTrips = HashSet<String>()

            for (r in stopTimes.rows) {
                val tid = stopTimes.col(r, "trip_id")
                val sid = stopTimes.col(r, "stop_id")
                val seq = stopTimes.col(r, "stop_sequence").toIntOrNull()
                val sec = hms(stopTimes.col(r, "departure_time"))
                if (tid !in tripIds) { errors += "stop_times.txt: 없는 trip_id $tid"; continue }
                if (sid !in stopIds) { errors += "stop_times.txt: 없는 stop_id $sid"; continue }
                if (seq == null) { errors += "stop_times.txt: stop_sequence 없음 $tid"; continue }
                if (sec == null) { errors += "stop_times.txt: 시각 형식 이상 $tid"; continue }
                usedStops += sid

                if (tid != lastTrip) {
                    if (!seenTrips.add(tid)) {
                        errors += "stop_times.txt: $tid 의 행이 흩어져 있다 (한 덩어리여야 한다)"
                    }
                    lastTrip = tid; lastSeq = seq; lastSec = sec; lastStop = sid
                    tripStopCount[tid] = 1
                    continue
                }
                if (seq <= lastSeq) errors += "stop_times.txt: stop_sequence 가 안 늘어난다 $tid"
                if (sec < lastSec) errors += "stop_times.txt: 시각이 거꾸로 간다 $tid"

                // 함축 속도. 규격은 맞는데 말이 안 되는 값을 여기서 잡는다.
                val dt = sec - lastSec
                val a = stopIds[lastStop]; val b = stopIds[sid]
                if (a != null && b != null) {
                    val m = Geo.haversineMeters(a.first, a.second, b.first, b.second)
                    if (m > 100) {
                        if (dt <= 0) zeroTime++
                        else {
                            val kmh = m / dt * 3.6
                            if (kmh > 90) fastSegments++
                            if (kmh < 3) slowSegments++
                        }
                    }
                }
                tripStopCount[tid] = (tripStopCount[tid] ?: 0) + 1
                tripTotalSec[tid] = sec
                lastSeq = seq; lastSec = sec; lastStop = sid
            }

            for (t in tripIds) {
                val n = tripStopCount[t] ?: 0
                if (n < 2) errors += "trips.txt: $t 에 정류장이 ${n}개뿐이다"
            }
            val unused = stopIds.keys - usedStops
            if (unused.isNotEmpty()) warns += "어느 운행에도 안 쓰이는 정류장 ${"%,d".format(unused.size)}개"

            // ── frequencies ────────────────────────────────────────
            // frequency 기반 파일에서 frequencies 에 없는 운행은 "자정 정각 출발 1회"가
            // 된다. 규격 위반은 아닌데 라우터가 그렇게 읽어서 노선이 통째로 사라진다.
            if (freq != null) {
                val covered = HashSet<String>()
                for (r in freq.rows) {
                    val tid = freq.col(r, "trip_id")
                    if (tid !in tripIds) { errors += "frequencies.txt: 없는 trip_id $tid"; continue }
                    covered += tid
                    val s = hms(freq.col(r, "start_time"))
                    val e = hms(freq.col(r, "end_time"))
                    val h = freq.col(r, "headway_secs").toIntOrNull()
                    if (s == null || e == null) { errors += "frequencies.txt: 시각 형식 이상 $tid"; continue }
                    if (e <= s) errors += "frequencies.txt: end_time 이 start_time 보다 빠르다 $tid"
                    if (h == null || h <= 0) errors += "frequencies.txt: headway_secs 이상 $tid=$h"
                    // 2시간 배차는 외곽 노선에 실제로 있다. 4시간이 넘으면 데이터를 의심한다.
                    else if (h > 14400) warns += "배차 ${h / 60}분 — 너무 길다 ($tid)"
                }
                val bare = tripIds - covered
                if (bare.isNotEmpty()) {
                    errors += "frequencies.txt 에 없는 운행 ${"%,d".format(bare.size)}개 " +
                        "— 라우터가 자정 1회 운행으로 읽는다"
                }
            } else warns += "frequencies.txt 가 없다 — 시각표 방식이라면 정상"

            if (transfers != null) {
                for (r in transfers.rows) {
                    val f = transfers.col(r, "from_stop_id")
                    val t = transfers.col(r, "to_stop_id")
                    if (f !in stopIds || t !in stopIds) errors += "transfers.txt: 없는 정류장 $f→$t"
                }
            }

            // ── 말이 되는가 ────────────────────────────────────────
            if (fastSegments > 0) warns += "시속 90km 넘는 구간 ${"%,d".format(fastSegments)}개"
            if (slowSegments > 0) warns += "시속 3km 안 되는 구간 ${"%,d".format(slowSegments)}개"
            if (zeroTime > 0) errors += "100m 넘게 가는데 소요시간 0인 구간 ${"%,d".format(zeroTime)}개"

            summary(stops, routes, trips, stopTimes, freq, transfers, tripTotalSec, tripStopCount)
        }

        report(zipFile, errors, warns)
    }

    private fun summary(
        stops: Table, routes: Table, trips: Table, stopTimes: Table,
        freq: Table?, transfers: Table?,
        tripTotalSec: Map<String, Int>, tripStopCount: Map<String, Int>,
    ) {
        println("      ── 내용 ──")
        println("      정류장 ${"%,d".format(stops.rows.size)} · 노선 ${"%,d".format(routes.rows.size)}" +
            " · 운행 ${"%,d".format(trips.rows.size)} · 정차 ${"%,d".format(stopTimes.rows.size)}")
        println("      배차 ${"%,d".format(freq?.rows?.size ?: 0)} · 환승 ${"%,d".format(transfers?.rows?.size ?: 0)}")

        val mins = tripTotalSec.values.map { it / 60 }.sorted()
        val nStops = tripStopCount.values.sorted()
        if (mins.isNotEmpty()) {
            fun p(v: List<Int>, q: Double) = v[(v.size * q).toInt().coerceIn(0, v.size - 1)]
            println("      노선 총 소요시간(분)  중앙 ${p(mins, 0.5)} · 하위10% ${p(mins, 0.1)}" +
                " · 상위10% ${p(mins, 0.9)} · 최대 ${mins.last()}")
            println("      운행당 정류장 수      중앙 ${p(nStops, 0.5)} · 최대 ${nStops.last()}")
        }
    }

    private fun report(f: File, errors: List<String>, warns: List<String>) {
        println("      ── 검사 ──")
        // 같은 종류가 수천 개씩 나오면 다 찍을 필요가 없다. 종류별로 몇 개만.
        fun show(label: String, list: List<String>) {
            if (list.isEmpty()) return
            println("      $label ${"%,d".format(list.size)}건")
            // 메시지에서 id·숫자를 지운 뒤 묶는다. 안 그러면 같은 문제 수백 건이
            // 전부 다른 종류로 세어져 무엇이 문제인지 안 보인다.
            list.groupBy { it.replace(Regex("[A-Za-z]*[0-9][0-9_A-Za-z]*"), "…").take(60) }
                .entries.sortedByDescending { it.value.size }.take(8)
                .forEach { (k, v) ->
                    println("        · $k  ${"%,d".format(v.size)}건 — 예: ${v.first().take(100)}")
                }
        }
        show("오류", errors)
        show("주의", warns)
        if (errors.isEmpty()) println("      ✅ 규격 오류 없음 — ${f.name}")
        else println("      ❌ 규격 오류 ${errors.size}건")
    }

    /** `25:30:00` 처럼 24를 넘는 표기를 허용해야 한다 — 그게 GTFS 의 운행일 표기다. */
    private fun hms(v: String): Int? {
        val p = v.split(":")
        if (p.size != 3) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = p[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    /** 따옴표 안의 쉼표를 지킨다. */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out.map { it.trim().removePrefix("﻿") }
    }
}
