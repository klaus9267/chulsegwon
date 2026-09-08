package reach

import java.io.DataInputStream
import java.io.File

/**
 * OSM 도보망 위의 반경 탐색.
 *
 * **왜 필요한가.** 지금 접근·이탈 도보는 **직선거리 × 1.4** 다. 그건 한강도 고속도로도
 * 없는 것처럼 계산한다. 직선 400m 인데 다리를 돌아 2km 인 자리가 실제로 많고,
 * 동네 중심에서 정류장까지가 딱 그 규모다 — 마지막 몇백 미터에서 도달권이 틀어진다.
 *
 * [Walk] 가 만든 CSR 을 읽어 그 자리를 메운다. 노드 309만·간선 674만(전국)이고,
 * 우리가 하는 건 반경 1km 안쪽 탐색이라 한 번에 수천 노드만 만진다.
 *
 * **재사용 가능한 상태로 둔다.** 출발지 621 + 동네 1,768 = 2,389번을 도는데,
 * 질의마다 309만짜리 거리 배열을 새로 잡으면 그것만 30GB 를 할당한다.
 * 거리 배열을 한 벌 두고 **만진 노드만 되돌린다.**
 */
class WalkGraph private constructor(
    private val xs: FloatArray,      // 경도
    private val ys: FloatArray,      // 위도
    private val degree: IntArray,    // CSR 시작 색인 (n+1)
    private val edgeTo: IntArray,
    private val edgeCost: FloatArray,
) {
    val nodeCount get() = xs.size

    fun latOf(node: Int) = ys[node].toDouble()
    fun lonOf(node: Int) = xs[node].toDouble()

    /** 데시미터 단위 거리. -1 은 아직 안 닿음. 질의마다 만진 것만 되돌린다. */
    private val dist = IntArray(xs.size) { -1 }
    private val touched = IntArray(1 shl 18)
    private var touchedCount = 0

    private var heap = LongArray(1 shl 16)
    private var heapSize = 0

    // ── 공간 격자 ────────────────────────────────────────────────
    // 좌표로 가장 가까운 노드를 찾으려면 색인이 필요하다. 위도 0.002° ≈ 220m.
    private val cell = 0.002
    private val grid = HashMap<Long, IntArray>(1 shl 20)

    private fun key(lat: Double, lon: Double): Long =
        (Math.floor(lat / cell).toLong() shl 32) xor Math.floor(lon / cell).toLong()

    private fun buildIndex() {
        val tmp = HashMap<Long, MutableList<Int>>(1 shl 20)
        for (i in xs.indices) tmp.getOrPut(key(ys[i].toDouble(), xs[i].toDouble())) { ArrayList(8) } += i
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    /** [lat],[lon] 에서 가장 가까운 도보망 노드. [maxM] 안에 없으면 -1. */
    fun nearest(lat: Double, lon: Double, maxM: Double = 400.0): Int {
        val r = Math.ceil(maxM / (cell * 111_000)).toInt().coerceAtLeast(1)
        val gy = Math.floor(lat / cell).toLong()
        val gx = Math.floor(lon / cell).toLong()
        var best = -1
        var bestD = maxM
        for (dy in -r..r) for (dx in -r..r) {
            val arr = grid[((gy + dy) shl 32) xor (gx + dx)] ?: continue
            for (i in arr) {
                val d = Geo.haversineMeters(lat, lon, ys[i].toDouble(), xs[i].toDouble())
                if (d < bestD) { bestD = d; best = i }
            }
        }
        return best
    }

    /**
     * [from] 에서 [maxMeters] 안에 있는 노드를 거리와 함께 훑는다.
     *
     * 다익스트라인데 반경으로 자른다. 힙은 `(거리, 노드)` 를 long 하나에 담는다 —
     * 상위 32비트가 거리(데시미터)라 long 을 그냥 비교하면 거리순이 된다.
     * 객체를 안 만들어서 2,389번을 돌아도 GC 가 안 생긴다.
     */
    fun reachable(from: Int, maxMeters: Double, visit: (node: Int, meters: Double) -> Unit) {
        clearTouched()
        if (from < 0) return
        val cap = (maxMeters * 10).toInt()
        heapSize = 0
        push(0, from)
        setDist(from, 0)
        while (heapSize > 0) {
            val top = pop()
            val d = (top ushr 32).toInt()
            val u = (top and 0xFFFFFFFFL).toInt()
            if (d > dist[u]) continue          // 이미 더 짧은 길로 왔다
            visit(u, d / 10.0)
            var k = degree[u]
            val end = degree[u + 1]
            while (k < end) {
                val v = edgeTo[k]
                val nd = d + (edgeCost[k] * 10).toInt()
                if (nd <= cap && (dist[v] < 0 || nd < dist[v])) {
                    setDist(v, nd)
                    push(nd, v)
                }
                k++
            }
        }
    }

    private fun setDist(node: Int, d: Int) {
        if (dist[node] < 0) {
            if (touchedCount < touched.size) touched[touchedCount] = node
            touchedCount++
        }
        dist[node] = d
    }

    /**
     * 만진 노드를 되돌린다.
     *
     * 기록 배열이 넘칠 만큼 크게 퍼진 질의(끊긴 섬이 아니라 도심 한복판)에서는
     * 통째로 비운다. 드물게 일어나고, 그때만 비싸다.
     */
    private fun clearTouched() {
        if (touchedCount > touched.size) {
            java.util.Arrays.fill(dist, -1)
        } else {
            for (i in 0 until touchedCount) dist[touched[i]] = -1
        }
        touchedCount = 0
    }

    private fun push(d: Int, node: Int) {
        if (heapSize == heap.size) heap = heap.copyOf(heap.size * 2)
        var i = heapSize++
        heap[i] = (d.toLong() shl 32) or node.toLong()
        while (i > 0) {
            val p = (i - 1) / 2
            if (heap[p] <= heap[i]) break
            val t = heap[p]; heap[p] = heap[i]; heap[i] = t
            i = p
        }
    }

    private fun pop(): Long {
        val top = heap[0]
        heap[0] = heap[--heapSize]
        var i = 0
        while (true) {
            val l = i * 2 + 1
            if (l >= heapSize) break
            var m = l
            if (l + 1 < heapSize && heap[l + 1] < heap[l]) m = l + 1
            if (heap[i] <= heap[m]) break
            val t = heap[i]; heap[i] = heap[m]; heap[m] = t
            i = m
        }
        return top
    }

    companion object {
        fun load(f: File): WalkGraph {
            require(f.exists()) { "도보 그래프가 없다: ${f.absolutePath}" }
            DataInputStream(f.inputStream().buffered(1 shl 22)).use { s ->
                val magic = ByteArray(4).also { s.readFully(it) }.toString(Charsets.US_ASCII)
                require(magic == "WALK") { "도보 그래프 포맷이 아니다: $magic" }
                s.readInt()                                  // version
                val n = s.readInt()
                val m = s.readInt()
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                for (i in 0 until n) { xs[i] = s.readFloat(); ys[i] = s.readFloat() }
                val deg = IntArray(n + 1) { s.readInt() }
                val to = IntArray(m) { s.readInt() }
                val cost = FloatArray(m) { s.readFloat() }
                val g = WalkGraph(xs, ys, deg, to, cost)
                g.buildIndex()
                return g
            }
        }
    }
}
