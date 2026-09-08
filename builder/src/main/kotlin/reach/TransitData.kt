package reach

import java.io.File
import java.util.zip.ZipFile

/**
 * GTFS 여러 벌을 읽어 탐색이 쓸 **평탄한 배열**로 만든다.
 *
 * **왜 GTFS 를 다시 읽나.** 우리가 만든 것을 우리가 읽는 게 돌아가는 것처럼 보이지만,
 * 그게 요점이다 — 지하철과 버스가 같은 문법으로 합쳐지고, 나중에 다른 사람의 GTFS
 * (예: 인천, 또는 갱신된 KTDB)를 그냥 한 벌 더 얹으면 된다.
 *
 * **frequency 기반을 펴지 않는다.** 배차 10분짜리 노선을 18시간치 실제 편성으로
 * 펴면 운행 11,218개가 120만 개가 되고 정차가 4천만 행이 된다. 대신 탐색할 때
 * `start + k×headway + offset` 을 O(1) 로 푼다 — 우리 합성 시간표가 원래 하던 것과
 * 같은 계산이라 새로 검증할 것도 없다.
 *
 * 배열로 펴는 이유는 [Timetable] 과 같다. 탐색이 이 위를 수십만 번 훑는다.
 */
private const val DETOUR = 1.4

class TransitData(
    /** 정류장 id → 색인. 지하철 승강장과 버스 정류장이 한 공간에 들어간다. */
    val stopIds: List<String>,
    val stopNames: List<String>,
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    /** 운행 패턴. `patternStops[p]` 가 그 패턴이 지나는 정류장 색인들. */
    val patternStops: Array<IntArray>,
    /** 패턴 시작에서 각 정류장까지의 상대 초. */
    val patternOffsets: Array<IntArray>,
    /** 패턴별 운행 시간대. (시작초, 끝초, 배차초) 세 쌍이 이어져 있다. */
    val patternWindows: Array<IntArray>,
    /** 정류장별로 지나가는 (패턴, 그 패턴 안에서 몇 번째) 쌍. 둘을 한 int 에 담았다. */
    val patternsAtStop: Array<IntArray>,
    /** 도보 환승. `transfers[stop]` 이 (상대 정류장, 초) 쌍의 평탄 배열. */
    var transfers: Array<IntArray>,
    val patternRoute: Array<String>,
) {
    val stopCount get() = stopIds.size
    val patternCount get() = patternStops.size

    companion object {

        /** 패턴 번호와 그 안의 위치를 한 int 에 담는다. 패턴은 2^20 개까지. */
        fun pack(pattern: Int, index: Int) = (pattern shl 12) or (index and 0xFFF)
        fun patternOf(v: Int) = v ushr 12
        fun indexOf(v: Int) = v and 0xFFF

        fun load(vararg feeds: File): TransitData {
            val stopIndex = LinkedHashMap<String, Int>()
            val names = ArrayList<String>()
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            val pStops = ArrayList<IntArray>()
            val pOffs = ArrayList<IntArray>()
            val pWins = ArrayList<IntArray>()
            val pRoute = ArrayList<String>()
            val rawTransfers = ArrayList<IntArray>()

            for (feed in feeds) {
                require(feed.exists()) { "GTFS 가 없다: ${feed.absolutePath}" }
                val tag = feed.nameWithoutExtension
                ZipFile(feed).use { zip ->
                    fun rows(name: String): List<Map<String, String>> {
                        val e = zip.getEntry(name) ?: return emptyList()
                        val lines = zip.getInputStream(e).bufferedReader(Charsets.UTF_8)
                            .readLines().filter { it.isNotBlank() }
                        if (lines.isEmpty()) return emptyList()
                        val head = splitCsv(lines[0])
                        return lines.drop(1).map { l ->
                            val v = splitCsv(l)
                            head.indices.associate { head[it] to (v.getOrNull(it) ?: "") }
                        }
                    }

                    fun stopOf(id: String) = stopIndex.getOrPut("$tag:$id") {
                        names += ""; lats += 0.0; lons += 0.0
                        names.size - 1
                    }

                    for (r in rows("stops.txt")) {
                        // location_type=1 은 승강장을 묶는 역이라 탐색에 안 쓴다.
                        if (r["location_type"] == "1") continue
                        val i = stopOf(r["stop_id"] ?: continue)
                        names[i] = r["stop_name"] ?: ""
                        lats[i] = r["stop_lat"]?.toDoubleOrNull() ?: 0.0
                        lons[i] = r["stop_lon"]?.toDoubleOrNull() ?: 0.0
                    }

                    val tripRoute = rows("trips.txt")
                        .associate { (it["trip_id"] ?: "") to (it["route_id"] ?: "") }

                    // 같은 운행의 정차는 파일에서 이어져 있다(우리가 그렇게 쓴다).
                    val seq = LinkedHashMap<String, MutableList<Pair<Int, Int>>>()
                    for (r in rows("stop_times.txt")) {
                        val t = r["trip_id"] ?: continue
                        val s = stopIndex["$tag:${r["stop_id"]}"] ?: continue
                        val sec = hms(r["departure_time"] ?: "") ?: continue
                        seq.getOrPut(t) { ArrayList(40) } += s to sec
                    }

                    val freq = HashMap<String, MutableList<Int>>()
                    for (r in rows("frequencies.txt")) {
                        val t = r["trip_id"] ?: continue
                        val s = hms(r["start_time"] ?: "") ?: continue
                        val e = hms(r["end_time"] ?: "") ?: continue
                        val h = r["headway_secs"]?.toIntOrNull() ?: continue
                        if (e <= s || h <= 0) continue
                        val l = freq.getOrPut(t) { ArrayList(8) }
                        l += s; l += e; l += h
                    }

                    for ((trip, stops) in seq) {
                        if (stops.size < 2) continue
                        val w = freq[trip] ?: continue   // 배차가 없으면 안 다니는 것으로 본다
                        val base = stops[0].second
                        pStops += IntArray(stops.size) { stops[it].first }
                        pOffs += IntArray(stops.size) { stops[it].second - base }
                        pWins += w.toIntArray()
                        pRoute += tripRoute[trip] ?: ""
                    }

                    for (r in rows("transfers.txt")) {
                        val a = stopIndex["$tag:${r["from_stop_id"]}"] ?: continue
                        val b = stopIndex["$tag:${r["to_stop_id"]}"] ?: continue
                        val t = r["min_transfer_time"]?.toIntOrNull() ?: 60
                        rawTransfers += intArrayOf(a, b, t)
                    }
                }
            }

            // 정류장별 역색인
            val n = names.size
            val atStop = Array(n) { ArrayList<Int>(4) }
            for (p in pStops.indices) {
                val ss = pStops[p]
                for (i in ss.indices) atStop[ss[i]].add(pack(p, i))
            }

            val trAcc = Array(n) { ArrayList<Int>(4) }
            for (t in rawTransfers) {
                trAcc[t[0]].add(t[1]); trAcc[t[0]].add(t[2])
            }

            return TransitData(
                stopIds = stopIndex.keys.toList(),
                stopNames = names,
                stopLat = lats.toDoubleArray(),
                stopLon = lons.toDoubleArray(),
                patternStops = pStops.toTypedArray(),
                patternOffsets = pOffs.toTypedArray(),
                patternWindows = pWins.toTypedArray(),
                patternsAtStop = Array(n) { atStop[it].toIntArray() },
                transfers = Array(n) { trAcc[it].toIntArray() },
                patternRoute = pRoute.toTypedArray(),
            )
        }

        /** `25:30:00` 처럼 24를 넘는 표기를 허용한다. */
        private fun hms(v: String): Int? {
            val p = v.split(":")
            if (p.size != 3) return null
            val h = p[0].trim().toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val s = p[2].toIntOrNull() ?: return null
            return h * 3600 + m * 60 + s
        }

        private fun splitCsv(line: String): List<String> {
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

    /**
     * 가까운 정류장끼리 도보 환승을 만든다.
     *
     * **이게 없으면 지하철과 버스가 서로 남남이다.** 두 GTFS 는 id 공간이 달라서
     * 피드 안의 `transfers.txt` 만으로는 연결이 안 된다. 실제로 이걸 붙이기 전에는
     * 강남역에서 90분 안에 닿는 정류장이 51,583개 중 679개뿐이었다 —
     * 지하철만 타고 다닌 것이다.
     *
     * 거리는 직선 × [DETOUR] 로 본다. 짧은 거리(400m 이하)라 도로가 굽을 여지가
     * 적고, 이 값은 카카오 대조에서 잰 것이다. 한강·고속도로를 사이에 둔 쌍은
     * 이 근사가 낙관적인데, 그건 도보 그래프를 붙일 때 고친다.
     */
    fun linkNearbyStops(maxMeters: Double = 400.0, walkMps: Double = 1.1, maxPerStop: Int = 8): Int {
        val cell = maxMeters / 111_000.0          // 위도 1도 ≈ 111km
        val grid = HashMap<Long, MutableList<Int>>(stopCount)
        for (i in 0 until stopCount) {
            if (stopLat[i] == 0.0) continue
            val k = (Math.floor(stopLat[i] / cell).toLong() shl 32) xor
                Math.floor(stopLon[i] / cell).toLong()
            grid.getOrPut(k) { ArrayList(4) } += i
        }

        var added = 0
        val out = Array(stopCount) { ArrayList<Int>(transfers[it].size + 16) }
        for (i in 0 until stopCount) {
            transfers[i].forEach { out[i].add(it) }
            if (stopLat[i] == 0.0) continue
            val gy = Math.floor(stopLat[i] / cell).toLong()
            val gx = Math.floor(stopLon[i] / cell).toLong()
            val near = ArrayList<Pair<Int, Double>>(16)
            for (dy in -1..1) for (dx in -1..1) {
                for (j in grid[((gy + dy) shl 32) xor (gx + dx)] ?: continue) {
                    if (j == i) continue
                    val m = Geo.haversineMeters(stopLat[i], stopLon[i], stopLat[j], stopLon[j])
                    if (m <= maxMeters) near += j to m
                }
            }
            near.sortBy { it.second }
            for ((j, m) in near.take(maxPerStop)) {
                val sec = ((m * DETOUR) / walkMps).toInt().coerceAtLeast(30)
                out[i].add(j); out[i].add(sec); added++
            }
        }
        transfers = Array(stopCount) { out[it].toIntArray() }
        return added
    }

    fun describe(): String =
        "정류장 ${"%,d".format(stopCount)} · 패턴 ${"%,d".format(patternCount)}" +
            " · 정차 ${"%,d".format(patternStops.sumOf { it.size })}" +
            " · 환승 ${"%,d".format(transfers.sumOf { it.size / 2 })}"
}
