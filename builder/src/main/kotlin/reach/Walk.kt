package reach

import java.io.DataOutputStream
import java.io.File

/**
 * OSM 에서 도보 그래프를 만든다.
 *
 * **왜 필요한가.** 지금 도보 반경은 **직선거리 원**이다. 한강도 산도 없는 것처럼 계산한다.
 * 직선 400m 인데 다리를 돌아 2km 인 곳이 실제로 많고, 그게 지금 도달권이 틀리는 지점이다.
 * 정류장 5만 개의 도보 환승도 이 그래프 위에서 푼다.
 *
 * 두 번 훑는다. 웨이를 먼저 읽어 **필요한 노드 id 를 알아낸 뒤** 노드를 읽는다.
 * 수도권 전체 노드는 수천만 개인데 도보 웨이에 쓰이는 건 그 일부라, 한 번에 다 들고
 * 있으면 힙이 터진다.
 */
object Walk {

    /**
     * 걸어갈 수 있는 길.
     *
     * `highway` 태그로 거른다. 고속도로·자동차전용도로는 빼고, 계단·보행자도로는 넣는다.
     * `service` 와 `residential` 을 넣는 이유는 골목이 실제 보행 경로라서다 — 이걸 빼면
     * 아파트 단지 안이나 주택가가 통째로 끊긴다.
     */
    private val WALKABLE = setOf(
        "footway", "path", "pedestrian", "steps", "living_street", "track",
        "residential", "service", "unclassified", "road",
        "tertiary", "tertiary_link", "secondary", "secondary_link",
        "primary", "primary_link", "cycleway",
    )

    /** 사람이 못 다니는 길. `highway` 가 이거면 제외한다. */
    private val EXCLUDED = setOf(
        "motorway", "motorway_link", "trunk", "trunk_link",
        "construction", "proposed", "raceway", "bus_guideway",
    )

    /** 계단은 걷긴 하는데 느리다. 짐 든 자취생에겐 특히. */
    private const val STEPS_PENALTY = 2.2

    fun isWalkable(tags: Map<String, String>): Boolean {
        val hw = tags["highway"] ?: return false
        if (hw in EXCLUDED) return false
        if (hw !in WALKABLE) return false
        // 명시적으로 막힌 길은 뺀다. 태그가 없으면 걸을 수 있다고 본다.
        if (tags["foot"] == "no" || tags["access"] == "no" || tags["access"] == "private") return false
        return true
    }

    /** 수도권 + 여유. 전국 그래프는 455MB 인데 우리가 쓰는 건 이 안쪽뿐이다. */
    class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun has(lat: Double, lon: Double) =
            lon in west..east && lat in south..north
    }

    fun build(pbf: File, outDir: File, bbox: Bbox? = null, check: Boolean = false) {
        val t0 = System.currentTimeMillis()
        outDir.mkdirs()

        // ── 1패스: 걸을 수 있는 웨이와, 거기 쓰이는 노드 id ──────────
        val ways = ArrayList<LongArray>()
        val penalties = ArrayList<Double>()
        val needed = HashSet<Long>(1 shl 21)
        var seenWays = 0L
        OsmPbf.read(pbf, wantNodes = false, wantWays = true, onWay = { w ->
            seenWays++
            if (isWalkable(w.tags) && w.refs.size >= 2) {
                ways += w.refs
                penalties += if (w.tags["highway"] == "steps") STEPS_PENALTY else 1.0
                for (r in w.refs) needed += r
            }
        })
        println("      1패스: 웨이 ${"%,d".format(seenWays)} 중 보행 ${"%,d".format(ways.size)}" +
            " · 필요한 노드 ${"%,d".format(needed.size)}")

        // ── 2패스: 그 노드들의 좌표만 ────────────────────────────
        val lat = HashMap<Long, Double>(needed.size * 2)
        val lon = HashMap<Long, Double>(needed.size * 2)
        var seenNodes = 0L
        OsmPbf.read(pbf, wantNodes = true, wantWays = false, onNode = { n ->
            seenNodes++
            // 범위 밖 노드는 버린다. 그 노드를 쓰는 간선도 아래에서 자연히 빠진다.
            if (n.id in needed && (bbox == null || bbox.has(n.lat, n.lon))) {
                lat[n.id] = n.lat; lon[n.id] = n.lon
            }
        })
        println("      2패스: 노드 ${"%,d".format(seenNodes)} 중 ${"%,d".format(lat.size)} 확보" +
            if (bbox == null) "" else " (범위 안만)")

        // ── 그래프 ───────────────────────────────────────────────
        // 노드 id 를 0..N-1 로 다시 매긴다. 원본 id 는 64비트라 배열 색인으로 못 쓴다.
        val index = HashMap<Long, Int>(lat.size * 2)
        val xs = DoubleArray(lat.size)
        val ys = DoubleArray(lat.size)
        for ((id, la) in lat) {
            val lo = lon[id] ?: continue
            val i = index.size
            index[id] = i
            xs[i] = lo; ys[i] = la
        }

        // 간선을 모아 인접 리스트로. 양방향이라 두 번 넣는다.
        val from = ArrayList<Int>(ways.size * 8)
        val to = ArrayList<Int>(ways.size * 8)
        val cost = ArrayList<Float>(ways.size * 8)
        for ((wi, refs) in ways.withIndex()) {
            val pen = penalties[wi]
            var prev = -1
            for (r in refs) {
                val i = index[r] ?: continue
                if (prev >= 0 && prev != i) {
                    val d = Geo.haversineMeters(ys[prev], xs[prev], ys[i], xs[i]) * pen
                    from += prev; to += i; cost += d.toFloat()
                    from += i; to += prev; cost += d.toFloat()
                }
                prev = i
            }
        }
        println("      그래프: 노드 ${"%,d".format(index.size)} · 간선 ${"%,d".format(from.size)}")

        // ── 저장 ─────────────────────────────────────────────────
        // CSR(압축 인접 행렬)로 담는다. 탐색이 노드 하나의 이웃을 훑는 일만 하므로
        // 이 형태가 캐시에 가장 친하다.
        val n = index.size
        val degree = IntArray(n + 1)
        for (f in from) degree[f + 1]++
        for (i in 1..n) degree[i] += degree[i - 1]
        val cursor = degree.copyOf()
        val edgeTo = IntArray(from.size)
        val edgeCost = FloatArray(from.size)
        for (k in from.indices) {
            val slot = cursor[from[k]]++
            edgeTo[slot] = to[k]
            edgeCost[slot] = cost[k]
        }

        val f = File(outDir, "walk-graph.bin")
        DataOutputStream(f.outputStream().buffered(1 shl 20)).use { o ->
            o.writeBytes("WALK")
            o.writeInt(1)
            o.writeInt(n)
            o.writeInt(edgeTo.size)
            for (i in 0 until n) { o.writeFloat(xs[i].toFloat()); o.writeFloat(ys[i].toFloat()) }
            for (i in 0..n) o.writeInt(degree[i])
            for (v in edgeTo) o.writeInt(v)
            for (c in edgeCost) o.writeFloat(c)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        println("      -> ${f.absolutePath}  ${"%,d".format(f.length() / 1024 / 1024)}MB  ${"%.1f".format(secs)}초")

        // 눈으로 볼 수 있는 확인 — 고립 노드가 많으면 필터가 잘못된 것이다
        var isolated = 0
        for (i in 0 until n) if (degree[i + 1] == degree[i]) isolated++
        val avgDeg = if (n > 0) edgeTo.size.toDouble() / n else 0.0
        println("      점검: 평균 차수 ${"%.2f".format(avgDeg)} · 고립 노드 ${"%,d".format(isolated)}")

        if (check) verify(xs, ys, degree, edgeTo, edgeCost)
    }

    /**
     * 아는 구간 몇 개를 실제로 걸어본다.
     *
     * "노드 몇 개, 간선 몇 개"는 그래프가 **말이 되는지**를 말해주지 않는다.
     * 한강을 건너는 구간이 직선거리와 같게 나오면 다리를 안 타고 물 위를 걸은 것이다.
     * 그건 숫자만 봐선 절대 모른다.
     */
    private fun verify(
        xs: DoubleArray, ys: DoubleArray,
        degree: IntArray, edgeTo: IntArray, edgeCost: FloatArray,
    ) {
        data class Case(val name: String, val aLat: Double, val aLon: Double,
                        val bLat: Double, val bLon: Double)
        val cases = listOf(
            Case("압구정→옥수 (한강)", 37.5270, 127.0280, 37.5400, 127.0180),
            Case("여의도→마포 (한강)", 37.5215, 126.9245, 37.5450, 126.9450),
            Case("강남역→역삼역 (평지)", 37.4979, 127.0276, 37.5006, 127.0365),
            Case("수원역→수원시청 (경기)", 37.2659, 127.0001, 37.2636, 127.0286),
        )
        println("      ── 경로 점검 ──")
        for (c in cases) {
            val s = nearest(xs, ys, c.aLat, c.aLon)
            val t = nearest(xs, ys, c.bLat, c.bLon)
            if (s < 0 || t < 0) { println("      ${c.name}: 근처 노드 없음"); continue }
            val straight = Geo.haversineMeters(c.aLat, c.aLon, c.bLat, c.bLon)
            val walk = dijkstra(degree, edgeTo, edgeCost, s, t, xs.size)
            if (walk == null) { println("      ${c.name}: 경로 없음"); continue }
            println("      ${c.name.padEnd(24)} 직선 ${"%,.0f".format(straight)}m" +
                " · 도보 ${"%,.0f".format(walk)}m · 우회 ${"%.2f".format(walk / straight)}배")
        }
    }

    private fun nearest(xs: DoubleArray, ys: DoubleArray, lat: Double, lon: Double): Int {
        var best = -1
        var bd = Double.MAX_VALUE
        for (i in xs.indices) {
            val dx = xs[i] - lon; val dy = ys[i] - lat
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun dijkstra(
        degree: IntArray, edgeTo: IntArray, edgeCost: FloatArray,
        src: Int, dst: Int, n: Int,
    ): Double? {
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        dist[src] = 0.0
        val pq = java.util.PriorityQueue<LongArray>(compareBy { java.lang.Double.longBitsToDouble(it[0]) })
        pq += longArrayOf(java.lang.Double.doubleToLongBits(0.0), src.toLong())
        while (pq.isNotEmpty()) {
            val top = pq.poll()
            val d = java.lang.Double.longBitsToDouble(top[0])
            val u = top[1].toInt()
            if (d > dist[u]) continue
            if (u == dst) return d
            for (k in degree[u] until degree[u + 1]) {
                val v = edgeTo[k]
                val nd = d + edgeCost[k]
                if (nd < dist[v]) {
                    dist[v] = nd
                    pq += longArrayOf(java.lang.Double.doubleToLongBits(nd), v.toLong())
                }
            }
        }
        return null
    }
}
