package reach

/**
 * 논리적인 역. 환승역(강남 = 2호선 + 신분당선)은 하나로 합쳐진다.
 * 사용자가 고르는 단위이자 출력 행렬의 축이다.
 */
class Station(
    val index: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
) {
    val platforms: MutableList<Int> = mutableListOf()
    override fun toString() = "$name#$index"
}

/**
 * 그래프의 실제 노드 = (역, 노선).
 *
 * 역 단위로 노드를 두면 2호선↔신분당선 환승 도보 시간이 사라져서 도달권이
 * 낙관적으로 나온다. 그래서 노선별로 쪼개고 사이에 [TransferEdge]를 둔다.
 */
class Platform(
    val index: Int,
    val stationIndex: Int,
    val line: String,
    val code: String,
    val lat: Double,
    val lon: Double,
)

/** 같은 노선의 인접한 두 승강장. 열차가 실제로 달리는 구간. */
class TrackEdge(
    val from: Int,
    val to: Int,
    val line: String,
    val meters: Double,
)

/** 같은 역 안에서 노선을 갈아타는 도보 구간. */
class TransferEdge(
    val from: Int,
    val to: Int,
    val walkSeconds: Int,
)

class Network(
    val stations: List<Station>,
    val platforms: List<Platform>,
    val trackEdges: List<TrackEdge>,
    val transferEdges: List<TransferEdge>,
) {
    val stationCount get() = stations.size
    val platformCount get() = platforms.size

    /**
     * 한 노선을 **종점에서 종점까지 통으로 달리는 운행** 하나.
     *
     * [headwayScale] 은 이 운행의 배차를 노선 기본 배차의 몇 배로 볼지다.
     * 종점 쌍마다 운행을 만들면 본선이 그만큼 겹쳐서, 배수를 안 주면
     * 본선 배차가 실제보다 촘촘해진다. 자세한 건 [lineRuns].
     */
    class LineRun(val stops: List<Int>, val headwayScale: Int)

    /**
     * 노선을 **직통 운행**으로 만든다.
     *
     * ## 왜
     *
     * [lineSequences] 는 간선을 이어붙여 체인을 만든다. 분기점이 있으면 노선이
     * 토막난다 — 1호선이 `소요산~인천`과 `신창~금천구청` 둘로 쪼개진다.
     * 그러면 **수원에서 서울역 가는 데 1호선 안에서 두 번 갈아타야 한다.**
     * 실제로는 직통이다. 갈아탈 때마다 배차 한 번을 더 기다리므로 그 노선을
     * 지나는 모든 도달시간이 부풀었다.
     *
     * 카카오 OD 46개 대조에서 우리 값이 **가장 빠른 위상으로도** 카카오보다
     * 느린 경우가 31건이었고, 특히 1호선 축(수원 출발)이 11건 중 10건이었다.
     *
     * ## 어떻게
     *
     * 종점(차수 1)끼리의 단순경로를 전부 만든다. 노선 그래프는 나무라서
     * 경로가 하나뿐이다. 수도권에서 종점이 셋 이상인 노선은 **1호선(5)·
     * 경의중앙(3)·5호선(3)** 뿐이라 조합이 폭발하지 않는다. 종점이 둘 이하면
     * 예전 그대로 [lineSequences] 를 쓴다 — 2호선 순환처럼 특수한 처리가
     * 거기 붙어 있고, 바꿀 이유가 없다.
     *
     * ## 배차를 몇 배로 볼 것인가
     *
     * 종점 쌍마다 운행을 만들면 본선을 여러 운행이 겹쳐 지난다. 1호선 서울역~구로는
     * 4개, 구로~금천구청은 6개가 지난다. 전부 기본 배차로 두면 본선이 6배 촘촘해진다.
     *
     * 그래서 **운행마다 자기가 지나는 가장 붐비는 간선의 겹침 수**를 배수로 준다.
     * 그러면 가장 붐비는 간선의 총 운행빈도가 기본 배차와 정확히 같아지고
     * (`Σ 1/(mH) = m/(mH) = 1/H`), 지선은 그 간선을 지나는 비율만큼 덜 촘촘해진다.
     * 실제로도 지선 열차는 전부 본선을 지나므로 이 비율이 대체로 맞다.
     *
     * ⚠️ **여기서 생기는 낙관.** 지선↔지선 직통(예: 5호선 마천→하남)은 실제로는
     * 강동에서 갈아타야 하는데 이 모델은 직통으로 본다. 그 대가로 훨씬 큰
     * 비관(본선 직통을 환승으로 세던 것)을 없앤다.
     */
    fun lineRuns(): Map<String, List<LineRun>> {
        return trackEdges.groupBy { it.line }.mapValues { (_, edges) ->
            val adj = HashMap<Int, MutableList<Int>>()
            for (e in edges) {
                adj.getOrPut(e.from) { mutableListOf() }.add(e.to)
                adj.getOrPut(e.to) { mutableListOf() }.add(e.from)
            }
            val ends = adj.keys.filter { adj[it]!!.size == 1 }.sorted()
            if (ends.size < 3) return@mapValues traceChains(edges).map { LineRun(it, 1) }

            val paths = ArrayList<List<Int>>()
            for (i in ends.indices) for (j in i + 1 until ends.size) {
                path(adj, ends[i], ends[j])?.let { paths += it }
            }
            if (paths.isEmpty()) return@mapValues traceChains(edges).map { LineRun(it, 1) }

            val overlap = HashMap<Long, Int>()
            for (p in paths) for (k in 1 until p.size) {
                overlap.merge(edgeKey(p[k - 1], p[k]), 1, Int::plus)
            }
            paths.map { p ->
                var scale = 1
                for (k in 1 until p.size) {
                    scale = maxOf(scale, overlap[edgeKey(p[k - 1], p[k])] ?: 1)
                }
                LineRun(p, scale)
            }
        }
    }

    /** 노선 그래프에서 [a]→[b] 단순경로. 나무라 하나뿐이다. */
    private fun path(adj: Map<Int, List<Int>>, a: Int, b: Int): List<Int>? {
        val prev = HashMap<Int, Int>()
        val seen = HashSet<Int>()
        val q = ArrayDeque<Int>()
        q += a; seen += a
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            if (cur == b) {
                val out = ArrayList<Int>()
                var v = b
                while (true) { out += v; v = prev[v] ?: break }
                return out.asReversed()
            }
            for (n in adj[cur] ?: emptyList()) if (seen.add(n)) { prev[n] = cur; q += n }
        }
        return null
    }

    private fun edgeKey(a: Int, b: Int) =
        if (a < b) a.toLong() * 1_000_000 + b else b.toLong() * 1_000_000 + a

    /** 노선별 승강장 시퀀스. 시간표를 만들 때 이 순서대로 열차를 굴린다. */
    fun lineSequences(): Map<String, List<List<Int>>> {
        val byLine = trackEdges.groupBy { it.line }
        return byLine.mapValues { (_, edges) -> traceChains(edges) }
    }

    /**
     * 한 노선의 간선들을 이어붙여 경로(체인)로 만든다.
     * 지선·순환선 때문에 노선 하나가 체인 여러 개로 쪼개질 수 있다.
     */
    private fun traceChains(edges: List<TrackEdge>): List<List<Int>> {
        val adj = HashMap<Int, MutableList<Int>>()
        for (e in edges) {
            adj.getOrPut(e.from) { mutableListOf() }.add(e.to)
            adj.getOrPut(e.to) { mutableListOf() }.add(e.from)
        }
        val used = HashSet<Long>()
        fun key(a: Int, b: Int) = if (a < b) a.toLong() * 1_000_000 + b else b.toLong() * 1_000_000 + a
        fun step(from: Int, avoid: Int): Int? =
            adj[from]!!.firstOrNull { it != avoid && key(from, it) !in used }

        val chains = mutableListOf<List<Int>>()
        // 종점(차수 1)을 먼저 잡아야 노선이 중간에서 잘리지 않는다.
        for (seed in adj.keys.sortedBy { adj[it]!!.size }) {
            for (neighbour in adj[seed]!!.toList()) {
                if (key(seed, neighbour) in used) continue
                used += key(seed, neighbour)
                val chain = ArrayDeque<Int>()
                chain.addLast(seed); chain.addLast(neighbour)

                // 앞으로 최대한 뻗고
                var prev = seed; var cur = neighbour
                while (true) {
                    val next = step(cur, prev) ?: break
                    used += key(cur, next); chain.addLast(next); prev = cur; cur = next
                }
                // 뒤로도 최대한 뻗는다. 이걸 빼먹으면 노선이 잘게 조각난다.
                prev = neighbour; cur = seed
                while (true) {
                    val next = step(cur, prev) ?: break
                    used += key(cur, next); chain.addFirst(next); prev = cur; cur = next
                }
                chains += chain.toList()
            }
        }
        return chains
    }
}
