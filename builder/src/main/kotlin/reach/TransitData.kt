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
    /**
     * 위상을 **더하기 전의** 창. `(start, end, headway)` 삼중항이 이어진 배열이다.
     *
     * [rephase] 가 이걸로 다른 위상의 시간표를 만든다.
     */
    val patternRawWindows: Array<IntArray>? = null,
    /** 패턴별 위상 씨앗(trip_id 해시). [patternRawWindows] 와 짝이다. */
    val patternPhaseSeed: IntArray? = null,
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
            val pRawWins = ArrayList<IntArray>()
            val pSeed = ArrayList<Int>()
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
                        // 창의 시작에 **노선별 위상**을 미리 더해둔다. 안 그러면 전
                        // 노선이 같은 순간에 출발해 환승 대기가 0 이 된다(우리 지하철
                        // 창은 전부 05:30·07:00… 로 같고 버스 첫차도 05:00 에 몰려 있다).
                        // 탐색 때 계산하지 않고 여기서 굽는 이유는 시간축을 뒤집어도
                        // 같은 시간표를 보게 하기 위해서다.
                        val ph = phaseOf(trip)
                        // 마지막으로 실제 출발하는 시각까지만 창으로 둔다.
                        // 뒤집을 때 그 값이 그대로 시작점이 된다. (bake 를 볼 것)
                        pWins += bake(IntArray(w.size) { w[it] }, ph)
                        pRawWins += IntArray(w.size) { w[it] }
                        pSeed += ph
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
                patternRawWindows = pRawWins.toTypedArray(),
                patternPhaseSeed = pSeed.toIntArray(),
            )
        }

        /**
         * 원본 창 `(start, end, headway)` 에 위상 [ph] 를 굽는다.
         *
         * [load] 안에 있던 계산과 같은 것을 [rephase] 도 쓰려고 꺼냈다.
         */
        internal fun bake(w: IntArray, ph: Int): IntArray {
            val out = IntArray(w.size)
            var k = 0
            while (k < w.size) {
                val head = w[k + 2]
                val start = w[k] + Math.floorMod(ph, head)
                out[k] = start
                out[k + 1] = if (w[k + 1] < start) start
                else start + ((w[k + 1] - start) / head) * head
                out[k + 2] = head
                k += 3
            }
            return out
        }

        /**
         * 노선 이름에서 뽑는 결정론적 위상 씨앗.
         *
         * 실제 수도권 노선들은 서로 시각을 맞추지 않는다. 같은 값이 매번 나와야
         * 결과가 재현되므로 난수가 아니라 이름 해시를 쓴다.
         */
        private fun phaseOf(trip: String): Int {
            var h = 0
            for (c in trip) h = h * 31 + c.code
            h = h xor (h ushr 15)
            return h and 0x3FFFFFF
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
     *
     * ⚠️ **[maxPerStop] 은 성능 상한이지 모델이 아니다.** 처음엔 8 이었는데
     * 재본 적이 없었다. 400m 안에 이웃이 8개를 넘는 정류장이 **전체의 약 60%**
     * 라(중앙 11 · 90% 24 · 최대 59) 밀집지역에서 300m 떨어진 쓸모있는 정류장이
     * 통째로 잘리고 있었다.
     *
     * 8 → 64 로 올려 배치를 다시 돌려보니 (출발지 8곳 · 도착 08:00 표본):
     * **도달 쌍의 7.24% 가 빨라졌고**(중앙 −2분 · 90% −7분 · 최대 −21분)
     * 새로 닿는 동네가 32곳 생겼다. 느려지거나 잃은 건 0 이다 —
     * 환승을 더 주는 것은 완화(relaxation)라 답이 나빠질 수 없다.
     *
     * 64 는 최대 이웃 수(59)보다 커서 **사실상 무제한**이다. 대가는 배치
     * 191초 → 214초(+12%) 뿐이다.
     */
    fun linkNearbyStops(maxMeters: Double = 400.0, walkMps: Double = 1.1, maxPerStop: Int = 64): Int {
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

    /**
     * 시간축을 뒤집은 망.
     *
     * 연결 (A→B, 출발 d, 도착 a) 를 (B→A, 출발 −a, 도착 −d) 로 바꾼다.
     * 그러면 "T 까지 도착" 문제가 "−T 에 출발" 문제가 되어 같은 코드로 풀린다.
     *
     * 패턴 하나는 정류장 열과 상대시각(offset), 그리고 출발 기준시각의 집합
     * {start + m·headway} 로 표현된다. 뒤집으면:
     * * 정류장 열이 거꾸로
     * * offset' = offLast − offset (거꾸로)
     * * 기준시각' = −(기준시각 + offLast) — 집합이 그대로 등차수열이라 창으로 표현된다
     */
    /**
     * 배차 위상만 다시 칠한 사본.
     *
     * **왜 필요한가.** 우리는 시간표가 없고 배차만 안다. 그래서 "몇 시 몇 분에 오는
     * 차인지"를 `trip_id` 해시로 정하는데, 그건 **한 표본을 뽑은 것**이지 참값이 아니다.
     * 배차 45분 노선이면 그 한 번의 해시가 도달시간을 최대 45분 움직인다.
     *
     * 실측(소금 6개, 강남 도착 08:00): 동네 도달시간의 표본 간 폭이 중앙 6분 ·
     * 90% 14분 · 최대 39분. 45분 예산에서 후보 298곳 중 **모든 표본에 드는 건
     * 196곳(66%)** 뿐이었다. 즉 답의 1/3이 해시가 정하고 있었다.
     *
     * 여러 소금으로 돌려 **중앙값**을 쓰면 그게 없어진다. 큰 배열(정류장·패턴·정차·
     * 환승)은 전부 공유하고 창만 새로 만든다 — 패턴 14,875개짜리라 사본이 400KB 다.
     */
    fun rephase(salt: Int): TransitData {
        val raw = patternRawWindows ?: return this
        val seed = patternPhaseSeed ?: return this
        val nw = Array(patternCount) { p ->
            // 소금을 씨앗에 섞는다. 곱하고 흩뜨려야 배차와 소금이 공명하지 않는다.
            var h = seed[p] * 31 + salt
            h = h xor (h ushr 13)
            h *= 0x5bd1e995
            h = h xor (h ushr 15)
            bake(raw[p], h and 0x3FFFFFF)
        }
        return TransitData(
            stopIds, stopNames, stopLat, stopLon,
            patternStops, patternOffsets, nw,
            patternsAtStop, transfers, patternRoute,
            raw, seed,
        )
    }

    fun mirrored(): TransitData {
        val n = patternCount
        val ms = Array(n) { p -> patternStops[p].reversedArray() }
        val mo = Array(n) { p ->
            val o = patternOffsets[p]
            val last = o[o.size - 1]
            IntArray(o.size) { last - o[o.size - 1 - it] }
        }
        val mw = Array(n) { p ->
            val o = patternOffsets[p]
            val last = o[o.size - 1]
            val w = patternWindows[p]
            val out = IntArray(w.size)
            var k = 0
            while (k < w.size) {
                // 정방향 기준시각은 [start, end] 를 headway 로 훑는다(end 는 실제 마지막
                // 출발이라 정확히 등차수열의 끝이다). 뒤집으면 순서가 반대가 된다.
                out[k] = -(w[k + 1] + last)
                out[k + 1] = -(w[k] + last)
                out[k + 2] = w[k + 2]
                k += 3
            }
            out
        }
        val at = Array(stopCount) { ArrayList<Int>(4) }
        for (p in 0 until n) {
            val ss = ms[p]
            for (i in ss.indices) at[ss[i]].add(pack(p, i))
        }
        return TransitData(
            stopIds, stopNames, stopLat, stopLon,
            ms, mo, mw,
            Array(stopCount) { at[it].toIntArray() },
            transfers,          // 도보는 방향이 없다
            patternRoute,
        )
    }

    fun describe(): String =
        "정류장 ${"%,d".format(stopCount)} · 패턴 ${"%,d".format(patternCount)}" +
            " · 정차 ${"%,d".format(patternStops.sumOf { it.size })}" +
            " · 환승 ${"%,d".format(transfers.sumOf { it.size / 2 })}"
}
