package reach

import java.util.PriorityQueue

const val UNREACHABLE = -1

/**
 * 시간 의존 Dijkstra.
 *
 * 노드가 700개대라 RAPTOR 를 쓸 이유가 없다. RAPTOR 가 이기는 건 정류장이 수만 개여서
 * 우선순위 큐의 캐시 미스가 문제될 때인데, 이 규모면 그래프가 통째로 캐시에 들어간다.
 * 버스가 들어와 정류장이 수만 개가 되는 시점(Level 2)에는 R5 로 넘긴다.
 */
class Router(private val network: Network, timetable: Timetable) {

    private val forward = Engine(network, timetable)
    private val backward = Engine(network, timetable.mirrored(network.platformCount))

    /** [origin]에서 [departAtSec]에 출발했을 때 각 역까지 걸리는 시간(초). 못 가면 [UNREACHABLE]. */
    fun travelTimesDepartingAt(origin: Int, departAtSec: Int): IntArray {
        val arrival = forward.run(origin, departAtSec)
        return IntArray(arrival.size) { if (arrival[it] == Engine.INF) UNREACHABLE else arrival[it] - departAtSec }
    }

    /**
     * [destination]에 [arriveBySec]까지 도착하려 할 때 각 역에서 걸리는 시간(초).
     *
     * 출근 도달권이 이쪽이다. "40분 안에 강남역 도착"은 출발시각이 아니라 도착시각 기준이라
     * 시간축을 뒤집어야 정확해진다. 뒤집힌 망에서 정방향 탐색을 돌리므로 코드는 같다.
     */
    fun travelTimesArrivingBy(destination: Int, arriveBySec: Int): IntArray {
        val mirrored = backward.run(destination, -arriveBySec)
        return IntArray(mirrored.size) { if (mirrored[it] == Engine.INF) UNREACHABLE else mirrored[it] + arriveBySec }
    }

    /** 실제로 어떤 경로를 골랐는지 사람이 읽을 수 있게 풀어 쓴다. 검증용. */
    fun explain(origin: Int, destination: Int, departAtSec: Int): List<String> {
        forward.runTraced(origin, departAtSec)
        val arrival = forward.platformArrival
        val endPlatform = network.stations[destination].platforms.minByOrNull { arrival[it] }
            ?: return listOf("목적지에 승강장이 없다")
        if (arrival[endPlatform] >= Engine.INF) return listOf("도달불가")

        val legs = mutableListOf<String>()
        var p = endPlatform
        while (true) {
            val prev = forward.parentPlatform[p]
            if (prev < 0) break
            val conn = forward.parentConnection[p]
            val from = network.platforms[prev]
            val to = network.platforms[p]
            val fromName = network.stations[from.stationIndex].name
            val toName = network.stations[to.stationIndex].name
            legs += if (conn < 0) {
                "  환승  %s %s선 -> %s선  (%s 도착)".format(fromName, from.line, to.line, hhmm(arrival[p]))
            } else {
                "  승차  %s -> %s  %s선  (%s -> %s)".format(
                    fromName, toName, to.line, hhmm(arrival[prev]), hhmm(arrival[p]))
            }
            p = prev
        }
        legs.reverse()
        return legs
    }

    private fun hhmm(sec: Int): String = "%02d:%02d".format((sec / 3600) % 24, (sec % 3600) / 60)

    private class Engine(private val network: Network, private val timetable: Timetable) {

        /** 승강장별 환승 대상과 도보시간. 배열로 펴서 탐색 루프에서 할당이 안 생기게 한다. */
        private val transferTo: Array<IntArray>
        private val transferSec: Array<IntArray>

        /** 승강장별로 열차가 갈 수 있는 서로 다른 목적지. 보통 1~2개라 조기 종료가 잘 먹는다. */
        private val successors: Array<IntArray>

        init {
            val to = Array(network.platformCount) { mutableListOf<Int>() }
            val sec = Array(network.platformCount) { mutableListOf<Int>() }
            for (t in network.transferEdges) { to[t.from].add(t.to); sec[t.from].add(t.walkSeconds) }
            transferTo = Array(network.platformCount) { to[it].toIntArray() }
            transferSec = Array(network.platformCount) { sec[it].toIntArray() }

            successors = Array(network.platformCount) { p ->
                timetable.departuresByPlatform[p].map { timetable.toPlatform[it] }.distinct().toIntArray()
            }
        }

        /** 마지막 [runTraced] 호출의 승강장별 선행자. explain 전용이라 일반 경로에서는 안 쓴다. */
        var parentPlatform: IntArray = IntArray(0); private set
        var parentConnection: IntArray = IntArray(0); private set
        var platformArrival: IntArray = IntArray(0); private set

        fun runTraced(originStation: Int, startSec: Int): IntArray {
            parentPlatform = IntArray(network.platformCount) { -1 }
            parentConnection = IntArray(network.platformCount) { -1 }
            val r = run(originStation, startSec, trace = true)
            return r
        }

        /** 역 단위 최早 도착시각. 환승역은 승강장 중 가장 빠른 값. */
        fun run(originStation: Int, startSec: Int, trace: Boolean = false): IntArray {
            val best = IntArray(network.platformCount) { INF }
            val pq = PriorityQueue<Long>()   // (time << 20 | platform) 로 패킹 — 객체 할당 회피

            for (p in network.stations[originStation].platforms) {
                best[p] = startSec
                pq += pack(startSec, p)
            }

            while (pq.isNotEmpty()) {
                val cur = pq.poll()
                val platform = (cur and PLATFORM_MASK).toInt()
                val time = (cur shr PLATFORM_BITS).toInt() + TIME_OFFSET
                if (time > best[platform]) continue

                // 1) 같은 역 안에서 다른 노선으로 걸어간다
                val tt = transferTo[platform]
                for (i in tt.indices) {
                    val next = time + transferSec[platform][i]
                    if (next < best[tt[i]]) {
                        best[tt[i]] = next; pq += pack(next, tt[i])
                        if (trace) { parentPlatform[tt[i]] = platform; parentConnection[tt[i]] = -1 }
                    }
                }

                // 2) 열차를 탄다 — 목적지별로 가장 이른 것 하나면 충분하다
                val succ = successors[platform]
                if (succ.isEmpty()) continue
                var found = 0
                val seen = BooleanArray(succ.size)
                val list = timetable.departuresByPlatform[platform]
                var i = timetable.firstDepartureAt(platform, time)
                while (i < list.size && found < succ.size) {
                    val c = list[i]
                    val dest = timetable.toPlatform[c]
                    val slot = succ.indexOf(dest)
                    if (slot >= 0 && !seen[slot]) {
                        seen[slot] = true
                        found++
                        val arrival = timetable.arrSec[c]
                        if (arrival < best[dest]) {
                            best[dest] = arrival; pq += pack(arrival, dest)
                            if (trace) { parentPlatform[dest] = platform; parentConnection[dest] = c }
                        }
                    }
                    i++
                }
            }

            if (trace) platformArrival = best
            val byStation = IntArray(network.stationCount) { INF }
            for (p in network.platforms) {
                val v = best[p.index]
                if (v < byStation[p.stationIndex]) byStation[p.stationIndex] = v
            }
            return byStation
        }

        /**
         * 시각을 우선순위 큐 키로 패킹한다. 뒤집힌 망에서는 시각이 음수라
         * [TIME_OFFSET]을 더해 양수로 만든 뒤 정렬한다.
         */
        private fun pack(time: Int, platform: Int): Long =
            ((time - TIME_OFFSET).toLong() shl PLATFORM_BITS) or platform.toLong()

        companion object {
            const val INF = Int.MAX_VALUE / 4
            private const val PLATFORM_BITS = 20
            private const val PLATFORM_MASK = (1L shl PLATFORM_BITS) - 1
            /** 뒤집힌 망의 음수 시각(최대 -약 90000초)을 커버한다. */
            private const val TIME_OFFSET = -200_000
        }
    }
}
