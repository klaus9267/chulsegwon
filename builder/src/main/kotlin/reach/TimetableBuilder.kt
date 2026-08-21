package reach

/**
 * 네트워크 + 배차간격표로 **합성 시간표**를 만든다.
 *
 * 탐색 코드가 "진짜 시간표"만 상대하게 만드는 게 목적이다. 배차간격을 간선 가중치에
 * 녹이면 막차·첫차가 표현되지 않고 나중에 실제 GTFS 로 바꿀 때 탐색까지 다시 써야 한다.
 * 합성이라도 열차를 실제로 굴려두면 교체 지점이 이 파일 하나로 끝난다.
 */
object TimetableBuilder {

    fun synthesize(network: Network, periods: List<ServicePeriod> = Headways.WEEKDAY): Timetable {
        val meters = HashMap<Long, Double>(network.trackEdges.size * 2)
        for (e in network.trackEdges) {
            meters[key(e.from, e.to)] = e.meters
            meters[key(e.to, e.from)] = e.meters
        }

        val connections = ArrayList<RawConnection>(500_000)

        for ((line, chains) in network.lineSequences()) {
            val multiplier = Headways.multiplierFor(line)
            for (chain in chains) {
                for (direction in listOf(chain, chain.asReversed())) {
                    emitChain(direction, meters, multiplier, periods, connections)
                }
            }
        }
        return Timetable.build(network.platformCount, connections)
    }

    /**
     * 구간별로 출발을 찍되, **위상(phase)** 을 누적 소요시간에 맞춰 정렬한다.
     *
     * 이게 핵심이다. 열차를 체인 시작점 기준으로만 굴리면 노선 중간 역은 한참 전
     * 시간대의 배차간격을 물려받는다 (강남역 08:00 인데 06:20 의 6분 배차를 겪는 식).
     * 구간마다 그 시각의 배차로 출발을 찍고, 위상을 누적시간 % 배차로 맞추면
     *   - 통과 승객: 도착시각이 정확히 다음 출발시각이라 대기 0
     *   - 승차 승객: 그 시각의 실제 배차만큼 대기
     * 둘 다 맞는다.
     */
    private fun emitChain(
        direction: List<Int>,
        meters: Map<Long, Double>,
        multiplier: Double,
        periods: List<ServicePeriod>,
        out: MutableList<RawConnection>,
    ) {
        var cumulative = 0
        for (i in 0 until direction.size - 1) {
            val from = direction[i]
            val to = direction[i + 1]
            val ride = Headways.segmentSeconds(meters[key(from, to)] ?: 900.0)

            for (period in periods) {
                val headway = (period.headwaySec * multiplier).toInt().coerceAtLeast(60)
                val phase = Math.floorMod(cumulative, headway)
                // period.startSec 이상이면서 위상이 맞는 첫 시각
                var t = period.startSec + Math.floorMod(phase - period.startSec, headway)
                while (t < period.endSec) {
                    out += RawConnection(from, to, t, t + ride)
                    t += headway
                }
            }
            cumulative += ride
        }
    }

    private fun key(a: Int, b: Int): Long = a.toLong() * 1_000_000L + b
}
