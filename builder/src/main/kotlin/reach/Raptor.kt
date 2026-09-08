package reach

/**
 * RAPTOR — 라운드 기반 대중교통 탐색.
 *
 * **왜 이제야 RAPTOR 인가.** [ADR-3] 에서 지하철만 다룰 땐 노드가 741개라 통째로
 * 캐시에 들어가서 "최적화할 대상이 없다"고 판단하고 시간 의존 Dijkstra 를 썼다.
 * 버스가 들어오면서 정류장이 5만 개가 됐다. 그 근거가 사라졌다.
 *
 * **그리고 ADR-3 이 적어둔 대안이 지금은 없다.** "직접 짜지 말고 R5 로 넘긴다"고
 * 했는데, R5 의 Maven 배포는 **2017년 5월에 멈춰 있다**(최신 `2.0.0-pre`, 지금 R5 는 7.x).
 * 라이브러리로 쓰라고 내놓는 물건이 아니게 됐다. OTP2 는 Maven Central 에 본체는
 * 있지만 전이 의존성이 별도 저장소를 요구하고, 애초에 서버라 원점 8천 개를 도는
 * 배치에 임베드할 물건이 아니다(미니PC 16GB 제약도 있다).
 *
 * 그래서 직접 짠다. 대신 **입력은 검증된 GTFS** 라, 나중에 엔진을 바꿀 여지는 남는다.
 *
 * **frequency 를 펴지 않는다.** 배차 10분짜리를 18시간치 편성으로 펴면 정차가
 * 4천만 행이 된다. 대신 "그 시각 이후 첫 열차"를 `start + k×headway` 로 O(1) 에 푼다.
 * 우리 합성 시간표가 원래 하던 계산과 같아서 새로 검증할 것이 없다.
 */
class Raptor(private val d: TransitData) {

    companion object {
        const val INF = Int.MAX_VALUE / 4

        /** 환승 라운드 상한. 수도권 통근은 3회를 넘기지 않는다. */
        const val MAX_ROUNDS = 5

        /** 차를 타려면 이만큼 미리 도착해 있어야 한다. 계단·개찰구·정류장 찾기. */
        const val BOARD_SLACK_SEC = 20
    }

    private val best = IntArray(d.stopCount)
    private val prev = IntArray(d.stopCount)
    private val marked = BooleanArray(d.stopCount)
    private val nextMarked = BooleanArray(d.stopCount)

    /**
     * [origins] (정류장 → 그곳에 닿는 시각) 에서 출발해 전 정류장의 최早 도착시각.
     *
     * 출발점이 정류장 하나가 아니라 여럿인 이유는 **접근 도보** 때문이다.
     * "강남역에서 출발"은 실제로는 강남역 주변 정류장 수십 개에서 각각 다른
     * 도보시간 뒤에 출발할 수 있다는 뜻이다.
     */
    fun run(origins: Map<Int, Int>, cutoffSec: Int): IntArray {
        java.util.Arrays.fill(best, INF)
        java.util.Arrays.fill(marked, false)
        for ((s, t) in origins) {
            if (t < best[s]) { best[s] = t; marked[s] = true }
        }
        relaxTransfers(cutoffSec)

        for (round in 0 until MAX_ROUNDS) {
            System.arraycopy(best, 0, prev, 0, best.size)
            java.util.Arrays.fill(nextMarked, false)

            // 이번 라운드에 훑을 패턴과, 그 패턴에서 가장 앞선 마킹 위치
            val entry = HashMap<Int, Int>(1024)
            for (s in 0 until d.stopCount) {
                if (!marked[s]) continue
                for (packed in d.patternsAtStop[s]) {
                    val p = TransitData.patternOf(packed)
                    val i = TransitData.indexOf(packed)
                    val cur = entry[p]
                    if (cur == null || i < cur) entry[p] = i
                }
            }
            if (entry.isEmpty()) break

            var improved = false
            for ((p, from) in entry) {
                val stops = d.patternStops[p]
                val offs = d.patternOffsets[p]
                var tripStart = INF
                for (i in from until stops.size) {
                    val s = stops[i]
                    if (tripStart < INF) {
                        val arr = tripStart + offs[i]
                        if (arr < best[s] && arr <= cutoffSec) {
                            best[s] = arr; nextMarked[s] = true; improved = true
                        }
                    }
                    // 여기서 더 이른 차를 잡을 수 있나. 직전 라운드 결과로만 판단한다 —
                    // 이번 라운드에 갱신된 값으로 타면 환승 횟수가 한 번 새어나간다.
                    val ready = prev[s]
                    if (ready < INF) {
                        val cand = earliestTripStart(p, i, ready + BOARD_SLACK_SEC)
                        if (cand < tripStart) tripStart = cand
                    }
                }
            }
            if (!improved) break

            System.arraycopy(nextMarked, 0, marked, 0, marked.size)
            relaxTransfers(cutoffSec)
        }
        return best.copyOf()
    }

    /** 도보 환승. 갱신된 정류장에서만 뻗는다. */
    private fun relaxTransfers(cutoffSec: Int) {
        val src = marked.copyOf()
        for (s in 0 until d.stopCount) {
            if (!src[s]) continue
            val t = d.transfers[s]
            var k = 0
            while (k < t.size) {
                val to = t[k]
                val arr = best[s] + t[k + 1]
                if (arr < best[to] && arr <= cutoffSec) { best[to] = arr; marked[to] = true }
                k += 2
            }
        }
    }

    /**
     * 패턴 [p] 의 [i] 번째 정류장에서 [ready] 이후 처음 탈 수 있는 차의 **출발 기준시각**.
     *
     * 정류장 시각 = 기준시각 + offset 이므로, 기준시각만 알면 뒤 정류장 도착이 다 나온다.
     * 창(window)이 여러 개면 가장 이른 것을 고른다.
     */
    private fun earliestTripStart(p: Int, i: Int, ready: Int): Int {
        val off = d.patternOffsets[p][i]
        val w = d.patternWindows[p]
        var bestStart = INF
        var k = 0
        while (k < w.size) {
            val start = w[k]; val end = w[k + 1]; val head = w[k + 2]
            k += 3
            val base = start + phase(p, head)
            // base + m*head + off >= ready 인 최소 m (m >= 0)
            val need = ready - off - base
            val m = if (need <= 0) 0 else (need + head - 1) / head
            val t = base + m * head
            if (t <= end && t < bestStart) bestStart = t
        }
        return bestStart
    }

    /**
     * 노선마다 출발 위상을 다르게 준다.
     *
     * **없으면 환승이 공짜가 된다.** GTFS `frequencies` 는 창 시작시각부터 배차마다
     * 출발한다. 그런데 우리 지하철 창은 모든 노선이 05:30·07:00·09:00… 로 똑같이
     * 시작하고, 버스도 첫차가 05:00 에 몰려 있다. 그대로 두면 전 노선이 같은 순간에
     * 출발해서, 갈아탈 때 기다리는 시간이 0 이 된다. 실제 수도권 노선들은 서로
     * 시각을 맞추지 않는다.
     *
     * 노선 번호에서 결정론적으로 뽑는다 — 매번 같은 값이라 결과가 재현된다.
     * (합성 시간표의 위상 정렬([ADR-6])과 목적이 같다. 거기선 **타고 지나가는**
     * 승객의 대기를 0 으로 만드는 게 목적이었는데, RAPTOR 는 같은 패턴에 머무르면
     * 애초에 다시 안 타므로 그건 저절로 된다. 여기서 필요한 건 반대쪽 —
     * **새로 타는** 승객이 제대로 기다리게 하는 것이다.)
     */
    private fun phase(pattern: Int, headway: Int): Int {
        var h = pattern * -1640531527          // Knuth 곱셈 해시
        h = h xor (h ushr 15)
        return Math.floorMod(h, headway)
    }
}
