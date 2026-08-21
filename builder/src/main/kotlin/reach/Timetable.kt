package reach

/**
 * 연결 하나 = 열차 1대가 [fromPlatform]을 [depSec]에 떠나 [toPlatform]에 [arrSec]에 닿는 것.
 *
 * 배열 4개로 평탄화해 둔 이유는 탐색이 이 위를 수십만 번 훑기 때문이다.
 * 객체 리스트로 두면 캐시 미스로 몇 배 느려진다.
 */
class Timetable(
    val fromPlatform: IntArray,
    val toPlatform: IntArray,
    val depSec: IntArray,
    val arrSec: IntArray,
    /** 승강장별 출발 연결 인덱스. depSec 오름차순 — 이진탐색으로 "t 이후 첫 열차"를 찾는다. */
    val departuresByPlatform: Array<IntArray>,
) {
    val size get() = depSec.size

    /** [platform]에서 [afterSec] 이후 첫 출발이 [departuresByPlatform] 안에서 몇 번째인지. */
    fun firstDepartureAt(platform: Int, afterSec: Int): Int {
        val list = departuresByPlatform[platform]
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (depSec[list[mid]] < afterSec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 시간축을 뒤집은 시간표.
     *
     * 출근 도달권은 "09:00까지 강남역 도착"이라 도착시각 기준(arrive-by)인데,
     * 뒤집은 망에서 정방향 탐색을 돌리면 같은 코드로 답이 나온다.
     * 연결 (A→B, dep, arr) 는 (B→A, -arr, -dep) 가 된다.
     */
    fun mirrored(platformCount: Int): Timetable = build(
        platformCount,
        List(size) { i ->
            RawConnection(
                from = toPlatform[i],
                to = fromPlatform[i],
                dep = -arrSec[i],
                arr = -depSec[i],
            )
        },
    )

    companion object {
        fun build(platformCount: Int, connections: List<RawConnection>): Timetable {
            val sorted = connections.sortedBy { it.dep }
            val n = sorted.size
            val from = IntArray(n); val to = IntArray(n)
            val dep = IntArray(n); val arr = IntArray(n)
            sorted.forEachIndexed { i, c ->
                from[i] = c.from; to[i] = c.to; dep[i] = c.dep; arr[i] = c.arr
            }
            val buckets = Array(platformCount) { mutableListOf<Int>() }
            for (i in 0 until n) buckets[from[i]].add(i)   // sorted 순서라 dep 오름차순 유지
            return Timetable(from, to, dep, arr, Array(platformCount) { buckets[it].toIntArray() })
        }
    }
}

class RawConnection(val from: Int, val to: Int, val dep: Int, val arr: Int)
