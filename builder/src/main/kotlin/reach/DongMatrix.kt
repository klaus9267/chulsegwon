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

    /** 걸어서 정류장까지 갈 수 있다고 보는 최대 거리(m). */
    private const val ACCESS_M = 800.0
    private const val WALK_MPS = 1.1

    /** 직선거리를 도보거리로 보정. 카카오 대조에서 잰 값(1.07)보다 크게 잡는다 — 보행은 더 굽는다. */
    private const val WALK_DETOUR = 1.4

    private class Dong(val name: String, val gu: String, val lat: Double, val lon: Double)

    /**
     * 한 지점에서 걸어 닿는 정류장들.
     *
     * **도보망이 있으면 그걸 쓰고, 없으면 직선거리로 떨어진다.** 직선거리는 한강도
     * 고속도로도 없는 것처럼 계산한다 — 직선 400m 인데 다리를 돌아 2km 인 자리가
     * 실제로 많고, 동네 중심에서 정류장까지가 딱 그 규모다.
     *
     * 정류장 6만 개를 미리 도보망 노드에 붙여두고(스냅), 질의할 때는 반경 안에서
     * 그 노드들을 만나는지만 본다. 지점→노드, 정류장→노드 스냅 거리도 도보시간에
     * 더한다 — 안 그러면 큰길에서 100m 들어간 정류장이 공짜가 된다.
     */
    private class WalkAccess(private val g: WalkGraph?, private val d: TransitData) {
        private val stopNode = IntArray(d.stopCount) { -1 }
        private val stopSnap = DoubleArray(d.stopCount)
        private val nodeStops = HashMap<Int, MutableList<Int>>(d.stopCount)
        var snapped = 0; private set

        init {
            if (g != null) {
                for (i in 0 until d.stopCount) {
                    if (d.stopLat[i] == 0.0) continue
                    val n = g.nearest(d.stopLat[i], d.stopLon[i], SNAP_M)
                    if (n < 0) continue
                    stopNode[i] = n
                    stopSnap[i] = Geo.haversineMeters(
                        d.stopLat[i], d.stopLon[i], g.latOf(n), g.lonOf(n))
                    nodeStops.getOrPut(n) { ArrayList(2) } += i
                    snapped++
                }
            }
        }

        /** (정류장, 도보초) 쌍의 평탄 배열. */
        fun from(lat: Double, lon: Double, maxM: Double): IntArray {
            if (g != null) {
                val start = g.nearest(lat, lon, SNAP_M)
                if (start >= 0) {
                    val base = Geo.haversineMeters(lat, lon, g.latOf(start), g.lonOf(start))
                    val best = HashMap<Int, Double>(64)
                    g.reachable(start, maxM - base) { node, meters ->
                        val ss = nodeStops[node] ?: return@reachable
                        for (i in ss) {
                            val total = base + meters + stopSnap[i]
                            if (total <= maxM && total < (best[i] ?: Double.MAX_VALUE)) best[i] = total
                        }
                    }
                    if (best.isNotEmpty()) {
                        val out = IntArray(best.size * 2)
                        var k = 0
                        for ((i, m) in best) {
                            out[k++] = i
                            out[k++] = (m / WALK_MPS).toInt().coerceAtLeast(10)
                        }
                        return out
                    }
                }
            }
            return straight(lat, lon, maxM)
        }

        /** 도보망이 없거나 그 지점이 도보망에서 떨어져 있을 때. */
        private fun straight(lat: Double, lon: Double, maxM: Double): IntArray {
            val out = ArrayList<Int>(32)
            for (i in 0 until d.stopCount) {
                if (d.stopLat[i] == 0.0) continue
                val m = Geo.haversineMeters(lat, lon, d.stopLat[i], d.stopLon[i])
                if (m > maxM) continue
                out += i
                out += ((m * WALK_DETOUR) / WALK_MPS).toInt().coerceAtLeast(10)
            }
            return out.toIntArray()
        }
    }

    /** 지점·정류장을 도보망에 붙일 때 허용하는 최대 거리(m). */
    private const val SNAP_M = 300.0

    fun build(
        network: Network,
        subwayGtfs: File,
        busGtfs: File,
        dongsJson: File,
        outDir: File,
        capMinutes: Int,
        walkGraph: File? = null,
    ) {
        val t0 = System.currentTimeMillis()
        val walk = walkGraph?.takeIf { it.exists() }?.let {
            val g = WalkGraph.load(it)
            println("      도보망 노드 ${"%,d".format(g.nodeCount)} (${System.currentTimeMillis() - t0}ms)")
            g
        }
        if (walk == null) println("      ⚠️ 도보망이 없다 — 접근·이탈을 직선거리로 잡는다")
        val data = TransitData.load(subwayGtfs, busGtfs)
        val links = data.linkNearbyStops()
        println("      ${data.describe()} · 도보 환승 ${"%,d".format(links)}개 추가")

        val dongs = readDongs(dongsJson)
        println("      동네 ${"%,d".format(dongs.size)}개")

        val access = WalkAccess(walk, data)
        if (walk != null) {
            println("      정류장 ${"%,d".format(access.snapped)}/${"%,d".format(data.stopCount)}" +
                " 개를 도보망에 붙였다 (${SNAP_M.toInt()}m 안)")
        }

        // 동네마다 걸어서 닿는 정류장. 이게 이탈(egress) 도보다.
        val nearStops = Array(dongs.size) { access.from(dongs[it].lat, dongs[it].lon, ACCESS_M) }
        val orphan = nearStops.count { it.isEmpty() }
        println("      동네당 ${ACCESS_M.toInt()}m 안 정류장 중앙 " +
            "${nearStops.map { it.size / 2 }.sorted()[nearStops.size / 2]}개" +
            if (orphan > 0) " · 정류장이 없는 동네 ${orphan}개" else "")

        // 출발지: 역 621개. 역 주변 정류장도 같이 태운다(버스로 갈아탈 수 있으니).
        val originSeeds = network.stations.map { st -> access.from(st.lat, st.lon, ACCESS_M) }
        println("      출발지 ${network.stations.size}개 · 역당 승차 후보 중앙 " +
            "${originSeeds.map { it.size / 2 }.sorted()[originSeeds.size / 2]}개")

        val slots = slots()
        println("      슬롯 ${slots.size}개 (도착 ${slots.count { it.direction == Direction.ARRIVE_BY }}" +
            " · 출발 ${slots.count { it.direction == Direction.DEPART_AT }})")

        val forward = data
        val backward = data.mirrored()
        val fw = Raptor(forward)
        val bw = Raptor(backward)

        outDir.mkdirs()
        val matrixDir = File(outDir, "matrix")
        matrixDir.mkdirs()

        var done = 0
        for ((oi, seeds) in originSeeds.withIndex()) {
            val rows = Array(slots.size) { ByteArray(dongs.size) }
            for ((si, slot) in slots.withIndex()) {
                val arriveBy = slot.direction == Direction.ARRIVE_BY
                val t = if (arriveBy) -slot.secondsOfDay else slot.secondsOfDay
                val origins = HashMap<Int, Int>(seeds.size / 2)
                var k = 0
                while (k < seeds.size) {
                    val s = seeds[k]; val w = seeds[k + 1]
                    val v = t + w
                    if (v < (origins[s] ?: Int.MAX_VALUE)) origins[s] = v
                    k += 2
                }
                val best = (if (arriveBy) bw else fw).run(origins, t + capMinutes * 60)
                val row = rows[si]
                for (di in dongs.indices) {
                    val near = nearStops[di]
                    var bestSec = Raptor.INF
                    var j = 0
                    while (j < near.size) {
                        val a = best[near[j]]
                        if (a < Raptor.INF) {
                            val v = a + near[j + 1]
                            if (v < bestSec) bestSec = v
                        }
                        j += 2
                    }
                    row[di] = if (bestSec >= Raptor.INF) MatrixWriter.UNREACHABLE_MINUTES.toByte()
                    else MatrixWriter.toMinuteByte(bestSec - t, capMinutes)
                }
            }
            MatrixWriter.write(File(matrixDir, "$oi.bin"), slots, dongs.size, rows)
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
        sb.append("  \"warning\": \"배차간격 기반 합성 시간표다. 실제 편성 시각표는 공공에 없다.\",\n")
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
