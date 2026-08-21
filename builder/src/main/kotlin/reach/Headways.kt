package reach

/** [startSec, endSec) 구간의 배차간격. 자정 넘는 막차는 86400 을 넘는 값으로 표현한다. */
class ServicePeriod(val startSec: Int, val endSec: Int, val headwaySec: Int)

/**
 * ⚠️ 추정치다. 실제 시간표가 아니다.
 *
 * KTDB GTFS 가 도착하면 이 파일 전체가 GtfsSource 로 대체된다. 그때까지는 이 표로
 * 합성 시간표를 만들어 쓴다 — 탐색 코드는 "진짜 시간표"만 알고 있으면 되므로
 * 나중에 바뀌는 건 이 파일 하나뿐이다.
 */
object Headways {
    private fun hm(h: Int, m: Int) = h * 3600 + m * 60

    val WEEKDAY = listOf(
        ServicePeriod(hm(5, 30), hm(7, 0), 6 * 60),
        ServicePeriod(hm(7, 0), hm(9, 0), 3 * 60),    // 출근 피크
        ServicePeriod(hm(9, 0), hm(17, 0), 6 * 60),
        ServicePeriod(hm(17, 0), hm(20, 0), 4 * 60),  // 퇴근 피크
        ServicePeriod(hm(20, 0), hm(23, 0), 7 * 60),
        ServicePeriod(hm(23, 0), hm(24, 40), 10 * 60), // 막차
    )

    /** 광역철도는 배차가 길다. 기본 1.0 에 노선별 배율. */
    private val LINE_MULTIPLIER = mapOf(
        "경의중앙" to 2.5, "경춘" to 3.0, "수인분당" to 1.6, "경강" to 2.5,
        "서해" to 2.2, "공항철도" to 1.8, "인천1" to 1.3, "인천2" to 1.3,
        "의정부" to 1.3, "용인" to 1.3, "우이신설" to 1.3, "김포" to 1.2,
    )

    fun multiplierFor(line: String): Double =
        LINE_MULTIPLIER.entries.firstOrNull { line.contains(it.key) }?.value ?: 1.0

    /**
     * 표정속도(정차 포함) 기반 구간 소요시간.
     * 도심 지하철은 역간이 짧고 느리며, 광역철도는 역간이 길고 빠르다.
     */
    fun segmentSeconds(meters: Double): Int {
        val kmh = if (meters < 1_500) 32.0 else 50.0
        return (meters / (kmh * 1000.0 / 3600.0)).toInt().coerceAtLeast(30)
    }
}
