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
