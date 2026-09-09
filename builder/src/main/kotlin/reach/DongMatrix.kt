package reach

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * 출발지 × 시각 × **동네** 도달시간 행렬.
 *
 * **왜 도착 축을 바꾸나.** 지금 행렬은 `출발역 → 도착역 621개` 다. 지하철만 있을 땐
 * 그게 맞았다 — 화면은 역 주변을 원으로 부풀려 등시선을 그렸고, 역이 곧 도달 지점이었다.
 *
 * 버스가 들어오면 그게 깨진다. 정류장이 5만 개라 도착 축에 다 넣을 수 없고
 * (5만 × 슬롯 × 출발지면 수 GB), "가까운 역"이라는 개념도 의미를 잃는다 —
 * 역에서 먼 동네도 버스로는 가깝다.
 *
 * 그래서 **화면이 이미 쓰고 있는 단위**로 바꾼다. 이 앱의 주인공은
 * [ADR-14](docs/HANDOFF.md) 이후로 법정동 1,768개다. 도착 축을 거기로 맞추면
 * 부풀리기 없이 바로 읽는 값이 되고, 파일도 작아진다.
 *
 * **슬롯을 232개에서 20개로 줄인다.** 10분 간격 232슬롯은 지하철만 계산할 때
 * (2.3초) 공짜였다. 버스가 들어오면 탐색 한 번이 80ms 라 621×232 = 4시간이 되고,
 * 파일도 254MB 가 된다. 이사 갈 동네를 고르는 데 10분 간격은 필요 없다 —
 * 출근 도착 07~10시, 퇴근 출발 17~23시를 30분 간격으로 본다.
 */
object DongMatrix {

    private class Dong(val name: String, val gu: String, val lat: Double, val lon: Double)

    /**
     * 슬롯 안에서 **떠나는 순간**을 이만큼씩 옮겨가며 표본을 뽑는다(초).
     *
     * ⚠️ 예전엔 배차 **위상**을 소금으로 여러 번 뽑았다. 지하철 1~9호선에 실제
     * 시각표가 들어온 뒤로는 그게 못 쓴다 — [TransitData.bake] 는 창 시작을
     * `위상 mod 배차` 만큼 미는데, 실제 시각은 배차를 1초로 넣으므로 이동이 0 이다.
     * 즉 위상을 아무리 다시 뽑아도 지하철은 안 흔들리고, **행렬 값이 "19:00 정각
     * 한 순간"에 매달린다.** 급행이 19:03 에 오느냐 19:29 에 오느냐로 답이 20분
     * 넘게 갈리는데 그중 하나만 찍는 셈이다.
     *
     * 떠나는 순간을 옮기면 시각표를 아는 노선이든 배차만 아는 노선이든 **상대
     * 위상이 똑같이 흩어진다.** 사용자가 묻는 것도 "19시쯤 나서면"이지
     * "19:00:00 에 나서면"이 아니다.
     */
    private const val SAMPLE_STEP_SEC = 360      // 6분

    /**
     * [n] 개 표본을 소요시간 순으로 정렬하고 중앙값 자리를 돌려준다.
     *
     * **[walk] 를 같이 옮긴다.** 표본마다 최선인 정류장이 다를 수 있어서, 도보는
     * 그 표본의 소요시간과 짝이다. 따로 정렬하면 엉뚱한 짝이 남는다.
     *
     * 짝수 개면 아래쪽(작은 쪽)을 고른다 — 두 값을 평균내면 도보 짝을 못 고른다.
     *
     * ⚠️ **[total] 은 뽑은 표본 수(K), [n] 은 그중 도달한 수다.** 못 닿은 표본은
     * "무한대"라 정렬하면 맨 뒤에 온다. 그러니 중앙값 자리는 `(n-1)/2` 가 아니라
     * `(total-1)/2` 다. K=4·n=3 이면 우연히 같지만 K=5·n=3 이면 어긋나고,
     * 어긋날 때는 **항상 빠른 쪽**을 골라 도달권을 부풀린다.
     * 자리가 도달한 표본 밖이면 그 동네는 애초에 도달불가로 걸러진다.
     */
    private fun medianIndex(sec: IntArray, walk: IntArray, n: Int, total: Int): Int {
        // n 이 8 이하라 삽입정렬이 가장 빠르다. 동네 1,768 × 슬롯 20 × 출발지 621 번 돈다.
        for (i in 1 until n) {
            val v = sec[i]; val w = walk[i]
            var j = i - 1
            while (j >= 0 && sec[j] > v) { sec[j + 1] = sec[j]; walk[j + 1] = walk[j]; j-- }
            sec[j + 1] = v; walk[j + 1] = w
        }
        return ((total - 1) / 2).coerceAtMost(n - 1)
    }

    fun build(
        network: Network,
        subwayGtfs: File,
        busGtfs: File,
        dongsJson: File,
        outDir: File,
        capMinutes: Int,
        walkGraph: File? = null,
        /** 슬롯 안에서 출발 시각을 몇 번 옮겨 볼지. 자세한 건 [SAMPLE_STEP_SEC]. */
        samples: Int = 1,
        /**
         * 정류장당 도보 환승 이웃 상한. 성능 상한이지 모델이 아니다 —
         * 자세한 근거는 [TransitData.linkNearbyStops] 를 볼 것.
         */
        maxPerStop: Int = 64,
    ) {
        val t0 = System.currentTimeMillis()
        val walk = walkGraph?.takeIf { it.exists() }?.let {
            val g = WalkGraph.load(it)
            println("      도보망 노드 ${"%,d".format(g.nodeCount)} (${System.currentTimeMillis() - t0}ms)")
            g
        }
        if (walk == null) println("      ⚠️ 도보망이 없다 — 접근·이탈을 직선거리로 잡는다")
        val data = TransitData.load(subwayGtfs, busGtfs)
        val links = data.linkNearbyStops(maxPerStop = maxPerStop)
        println("      ${data.describe()} · 도보 환승 ${"%,d".format(links)}개 추가")

        val dongs = readDongs(dongsJson)
        println("      동네 ${"%,d".format(dongs.size)}개")

        val access = Access.Walkers(walk, data)
        if (walk != null) {
            println("      정류장 ${"%,d".format(access.snapped)}/${"%,d".format(data.stopCount)}" +
                " 개를 도보망에 붙였다 (${Access.SNAP_M.toInt()}m 안, 끊긴 섬 제외)")
        }

        // 동네마다 걸어서 닿는 정류장. 이게 이탈(egress) 도보다.
        val nearStops = Array(dongs.size) { access.from(dongs[it].lat, dongs[it].lon) }
        val orphan = nearStops.count { it.isEmpty() }
        println("      동네당 도보 ${Access.ACCESS_SEC / 60}분 안 정류장 중앙 " +
            "${nearStops.map { it.size / 2 }.sorted()[nearStops.size / 2]}개" +
            if (orphan > 0) " · 정류장이 없는 동네 ${orphan}개" else "")

        // 출발지: 역 621개. 역 주변 정류장도 같이 태운다(버스로 갈아탈 수 있으니).
        val originSeeds = network.stations.map { st -> access.from(st.lat, st.lon) }
        // 도보망에 못 붙어 직선거리로 떨어진 지점이 몇 개인지 밝힌다. 한 파일 안에
        // 두 모델이 섞이면 결과를 해석할 수 없으니, 최소한 얼마나 섞였는지는 알아야 한다.
        println("      직선거리로 떨어진 지점 ${access.fellBack}" +
            "/${dongs.size + network.stations.size}")
        println("      출발지 ${network.stations.size}개 · 역당 승차 후보 중앙 " +
            "${originSeeds.map { it.size / 2 }.sorted()[originSeeds.size / 2]}개")

        val slots = slots()
        println("      슬롯 ${slots.size}개 (도착 ${slots.count { it.direction == Direction.ARRIVE_BY }}" +
            " · 출발 ${slots.count { it.direction == Direction.DEPART_AT }})")

        // 슬롯 안에서 떠나는 순간을 옮겨가며 **중앙값**을 쓴다.
        //
        // 시각표가 없는 노선(버스·13개 광역철도)은 배차만 아는데, 그 위상은 한 표본이지
        // 참값이 아니다. 소금 6개로 실측했을 때 동네 도달시간의 표본 간 폭이 중앙 6분 ·
        // 90% 14분 · 최대 39분이었고, 강남 45분 예산에서 후보 298곳 중 모든 표본에
        // 드는 건 196곳(66%)뿐이었다. **답의 1/3을 해시가 정하고 있었다.**
        //
        // 시각표가 있는 노선은 반대로 위상이 고정이라, 흔들리는 건 **우리가 언제
        // 나서느냐**뿐이다. 둘 다 담는 방법이 출발 시각 훑기다.
        val k = samples.coerceAtLeast(1)
        val sampleOffsets = IntArray(k) { it * SAMPLE_STEP_SEC }
        val fw = Raptor(data)
        val bw = Raptor(data.mirrored())
        if (k > 1) {
            println("      출발 시각을 ${SAMPLE_STEP_SEC / 60}분 간격으로 ${k}회 옮겨 중앙값 사용")
        }

        outDir.mkdirs()
        val matrixDir = File(outDir, "matrix")
        matrixDir.mkdirs()

        var done = 0
        // 표본별 (총소요초, 그중 이탈도보초). 동네 하나를 K번 재고 중앙값을 고른다.
        val sampleSec = IntArray(k)
        val sampleWalk = IntArray(k)
        for ((oi, seeds) in originSeeds.withIndex()) {
            val rows = Array(slots.size) { ByteArray(dongs.size) }
            val walkRows = Array(slots.size) { ByteArray(dongs.size) }
            for ((si, slot) in slots.withIndex()) {
                val arriveBy = slot.direction == Direction.ARRIVE_BY
                val t = if (arriveBy) -slot.secondsOfDay else slot.secondsOfDay
                // 표본마다 기준 시각을 옮긴다. 도착 기준(뒤집힌 축)에서는 t 가
                // 음수라, 늦게 도착해도 되는 쪽으로 가려면 t 를 **줄여야** 한다.
                val bests = Array(k) { pi ->
                    val tp = if (arriveBy) t - sampleOffsets[pi] else t + sampleOffsets[pi]
                    val org = HashMap<Int, Int>(seeds.size / 2)
                    var qq = 0
                    while (qq < seeds.size) {
                        val v = tp + seeds[qq + 1]
                        if (v < (org[seeds[qq]] ?: Int.MAX_VALUE)) org[seeds[qq]] = v
                        qq += 2
                    }
                    (if (arriveBy) bw else fw).run(org, tp + capMinutes * 60)
                }
                val row = rows[si]
                val walkRow = walkRows[si]
                for (di in dongs.indices) {
                    val near = nearStops[di]
                    var got = 0
                    for (pi in 0 until k) {
                        val best = bests[pi]
                        var bestSec = Raptor.INF
                        var bestWalk = 0
                        var j = 0
                        while (j < near.size) {
                            val a = best[near[j]]
                            if (a < Raptor.INF) {
                                val v = a + near[j + 1]
                                // 같은 동네라도 표본마다 **다른 정류장**이 최선일 수 있다.
                                // 그래서 도보도 그 표본의 최선과 짝지어 기록한다.
                                if (v < bestSec) { bestSec = v; bestWalk = near[j + 1] }
                            }
                            j += 2
                        }
                        if (bestSec < Raptor.INF) {
                            val tp = if (arriveBy) t - sampleOffsets[pi] else t + sampleOffsets[pi]
                            sampleSec[got] = bestSec - tp
                            sampleWalk[got] = bestWalk
                            got++
                        }
                    }
                    // 표본 절반 이상이 못 닿으면 도달 불가로 본다. 한 표본만 운 좋게
                    // 닿은 걸 "간다"고 말하면 그게 바로 지금 고치려는 문제다.
                    if (got * 2 <= k) {
                        row[di] = MatrixWriter.UNREACHABLE_MINUTES.toByte()
                        walkRow[di] = 0
                        continue
                    }
                    val mid = medianIndex(sampleSec, sampleWalk, got, k)
                    val minute = MatrixWriter.toMinuteByte(sampleSec[mid], capMinutes)
                    row[di] = minute
                    // **도달불가면 도보도 0 이어야 한다.** 상한을 넘겨 255 가 된 칸에
                    // 실제 도보 분이 남아 있었다(배포본 990,255칸 중 58,614칸, 5.9%).
                    // 지금은 호출부가 항상 소요시간을 먼저 보므로 무해하지만, 도보를
                    // 먼저 보는 코드가 하나 생기면 도달불가 동네가 필터를 통과한다.
                    walkRow[di] =
                        if (minute == MatrixWriter.UNREACHABLE_MINUTES.toByte()) 0
                        else ((sampleWalk[mid] + 30) / 60).coerceIn(0, 254).toByte()
                }
            }
            MatrixWriter.write(File(matrixDir, "$oi.bin"), slots, dongs.size, rows, walkRows)
            if (++done % 50 == 0) {
                println("      $done/${originSeeds.size}  (${(System.currentTimeMillis() - t0) / 1000}초)")
            }
        }

        writeManifest(File(outDir, "manifest.json"), slots, network, dongs, capMinutes)
        val bytes = matrixDir.listFiles()?.sumOf { it.length() } ?: 0
        println("      완료 ${(System.currentTimeMillis() - t0) / 1000}초 · " +
            "행렬 ${"%,d".format(bytes / 1024 / 1024)}MB (출발지당 ${"%,d".format(bytes / originSeeds.size / 1024)}KB)")
    }

    /**
     * 시각 슬롯.
     *
     * 출근은 "몇 시까지 도착"이라 도착 기준, 퇴근은 "몇 시에 출발"이라 출발 기준이다.
     * 30분 간격인 이유는 [DongMatrix] 머리말을 볼 것 — 10분 간격은 계산이 4시간,
     * 파일이 254MB 가 되고 이사 갈 동네를 고르는 데 그 해상도가 필요 없다.
     */
    private fun slots(): List<Slot> {
        val out = ArrayList<Slot>()
        var i = 0
        var t = 7 * 3600
        while (t <= 10 * 3600) { out += Slot(i++, Direction.ARRIVE_BY, t); t += 1800 }
        t = 17 * 3600
        while (t <= 23 * 3600) { out += Slot(i++, Direction.DEPART_AT, t); t += 1800 }
        return out
    }

    private fun readDongs(f: File): List<Dong> {
        // `{"months":[...], "dongs":[...]}` 꼴이다. 순서가 곧 색인이므로 그대로 쓴다 —
        // 웹이 이미 이 순서로 동네를 들고 있어서 색인이 맞아떨어진다.
        @Suppress("UNCHECKED_CAST")
        val root = ObjectMapper().readValue(f, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val raw = root["dongs"] as List<Map<String, Any?>>
        return raw.map {
            Dong(
                it["name"] as? String ?: "",
                it["gu"] as? String ?: "",
                (it["lat"] as Number).toDouble(),
                (it["lon"] as Number).toDouble(),
            )
        }
    }

    private fun writeManifest(
        f: File, slots: List<Slot>, network: Network, dongs: List<Dong>, cap: Int,
    ) {
        val sb = StringBuilder()
        sb.append("{\n  \"version\": 2,\n  \"generatedBy\": \"raptor-gtfs\",\n")
        // ⚠️ 예전엔 "실제 편성 시각표는 공공에 없다"고 적혀 있었다. 틀린 말이었다 —
        // 1~9호선은 서울교통공사가 공개한다([RailTimetable]). 남은 노선만 합성이다.
        sb.append("  \"warning\": \"지하철 1~9호선은 실측 시각표(서울교통공사), " +
            "그 밖의 광역철도 13개 노선과 버스는 배차간격 기반 합성 시간표다.\",\n")
        sb.append("  \"transferOverheadSeconds\": 0,\n")
        sb.append("  \"note\": \"도착 축이 역이 아니라 법정동이다. 지하철+버스+도보 통합.\",\n")
        sb.append("  \"capMinutes\": ").append(cap).append(",\n  \"slots\": [\n")
        slots.forEachIndexed { i, s ->
            sb.append("    {\"index\": ").append(s.index)
                .append(", \"direction\": \"").append(s.direction.name)
                .append("\", \"secondsOfDay\": ").append(s.secondsOfDay)
                .append(", \"label\": \"").append(s.label).append("\"}")
            sb.append(if (i == slots.size - 1) "\n" else ",\n")
        }
        // 키 이름을 `stations` 로 둔다. **출발지는 여전히 역 621개**라, 이러면
        // 출발지 콤보박스·공유 링크·지도 마커가 손댈 필요 없이 그대로 돈다.
        // 바뀌는 건 도착 축뿐이고 그건 아래 `dongs` 다.
        sb.append("  ],\n  \"stations\": [\n")
        network.stations.forEachIndexed { i, s ->
            val lines = s.platforms.map { network.platforms[it].line }.distinct()
            sb.append("    {\"index\": ").append(i).append(", \"name\": \"").append(s.name)
                .append("\", \"lat\": ").append(s.lat).append(", \"lon\": ").append(s.lon)
                .append(", \"lines\": [")
                .append(lines.joinToString(",") { "\"" + it + "\"" }).append("]}")
            sb.append(if (i == network.stations.size - 1) "\n" else ",\n")
        }
        sb.append("  ],\n  \"dongs\": [\n")
        dongs.forEachIndexed { i, g ->
            sb.append("    {\"index\": ").append(i).append(", \"name\": \"").append(g.name)
                .append("\", \"gu\": \"").append(g.gu)
                .append("\", \"lat\": ").append(g.lat).append(", \"lon\": ").append(g.lon).append("}")
            sb.append(if (i == dongs.size - 1) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        f.writeText(sb.toString())
    }
}
