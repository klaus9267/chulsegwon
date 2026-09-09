package reach

/**
 * 지점 → 정류장 **접근·이탈 도보**.
 *
 * [DongMatrix] 안에 있던 것을 밖으로 뺐다. 이유는 하나다 — 검증(`--mode odcheck`)이
 * 생산 행렬과 **같은 도보 모델**을 써야 하기 때문이다. 복사본을 하나 더 두면
 * "검증은 통과하는데 배포본은 다르다"가 언제든 생긴다. [SubwayGtfs] 머리말이
 * 경고하는 것과 같은 함정이다.
 */
object Access {

    /**
     * 걸어서 정류장까지 갈 수 있다고 보는 **시간**(초).
     *
     * ⚠️ 예전엔 거리(800m)로 잡았는데, 그러면 **모델을 바꿀 때 예산이 조용히 바뀐다.**
     * 직선거리 모델에서 800m 는 ×1.4 를 곱해 도보 1,120m 즉 17분이었는데,
     * 도보망 모델에서 800m 는 그냥 12분이다. 도보망을 붙이면서 도보 예산이 5분
     * 줄었고, "도보망 때문에 멀어졌다"고 나온 값에 그 몫이 섞여 있었다.
     *
     * 사용자가 고르는 것도 거리가 아니라 시간이다(화면의 도보 상한 슬라이더).
     * 그래서 시간으로 잡고 모델마다 거리로 환산한다.
     */
    const val ACCESS_SEC = 900          // 15분
    const val WALK_MPS = 1.1

    /** 직선거리를 도보거리로 보정. 카카오 대조에서 잰 값(1.07)보다 크게 잡는다 — 보행은 더 굽는다. */
    const val WALK_DETOUR = 1.4

    /** 도보망을 쓸 때의 반경(m). 시간 예산을 거리로 환산한 것. */
    const val ACCESS_WALK_M = ACCESS_SEC * WALK_MPS

    /** 직선거리로 떨어질 때의 반경(m). 같은 시간 예산이 되도록 우회계수로 나눈다. */
    const val ACCESS_STRAIGHT_M = ACCESS_SEC * WALK_MPS / WALK_DETOUR

    /** 지점·정류장을 도보망에 붙일 때 허용하는 최대 거리(m). */
    const val SNAP_M = 300.0

    /**
     * 이보다 작은 연결 성분에는 붙이지 않는다.
     *
     * 육교·지하도가 주변 길과 안 이어진 채 OSM 에 들어가 있으면 노드 몇 개짜리 섬이
     * 된다. 정류장이 거기 붙으면 어디로도 못 가서 그 정류장이 통째로 사라진다.
     * 검증에서 그런 정류장이 434개 나왔다. 걸어서 15분이면 노드 수백 개는 지나므로
     * 200 은 넉넉히 안전한 문턱이다.
     */
    const val MIN_COMPONENT = 200

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
    class Walkers(private val g: WalkGraph?, private val d: TransitData) {
        private val stopNode = IntArray(d.stopCount) { -1 }
        private val stopSnap = DoubleArray(d.stopCount)
        private val nodeStops = HashMap<Int, MutableList<Int>>(d.stopCount)
        var snapped = 0; private set
        var fellBack = 0; private set

        init {
            if (g != null) {
                for (i in 0 until d.stopCount) {
                    if (d.stopLat[i] == 0.0) continue
                    val n = g.nearest(d.stopLat[i], d.stopLon[i], SNAP_M, MIN_COMPONENT)
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
        fun from(lat: Double, lon: Double): IntArray {
            if (g != null) {
                val start = g.nearest(lat, lon, SNAP_M, MIN_COMPONENT)
                if (start >= 0) {
                    val maxM = ACCESS_WALK_M
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
            fellBack++
            return straight(lat, lon)
        }

        /** 도보망이 없거나 그 지점이 도보망에서 떨어져 있을 때. */
        private fun straight(lat: Double, lon: Double): IntArray {
            val out = ArrayList<Int>(32)
            for (i in 0 until d.stopCount) {
                if (d.stopLat[i] == 0.0) continue
                val m = Geo.haversineMeters(lat, lon, d.stopLat[i], d.stopLon[i])
                if (m > ACCESS_STRAIGHT_M) continue
                out += i
                out += ((m * WALK_DETOUR) / WALK_MPS).toInt().coerceAtLeast(10)
            }
            return out.toIntArray()
        }
    }
}
