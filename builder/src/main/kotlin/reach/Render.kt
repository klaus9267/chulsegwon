package reach

import java.io.File
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * 도달권을 SVG 로 그린다.
 *
 * 웹앱을 띄우지 않고도 결과를 눈으로 확인하기 위한 도구다. 브라우저가 없는 환경에서
 * "계산은 맞는데 화면이 이상한 건지, 계산부터 틀린 건지"를 가르는 데 쓴다.
 */
object Render {

    private const val WALK_MPS = 1.25

    /**
     * 웹 메르카토르 Y. 경도와 같은 "도" 단위로 돌려줘야 x/y 축척이 맞는다.
     * 라디안 그대로 쓰면 세로가 1/57 로 납작해진다.
     */
    private fun projectY(lat: Double) = Math.toDegrees(ln(tan(PI / 4 + Math.toRadians(lat) / 2)))

    private val RAMP = listOf(
        0 to "#1a4d8f", 10 to "#2b6cb0", 20 to "#4a90c4",
        30 to "#7fb3d5", 45 to "#aecfe4", 60 to "#d3e3f0",
    )

    private fun colorFor(minutes: Int) = RAMP.last { minutes >= it.first }.second

    fun run(
        network: Network,
        router: Router,
        originName: String,
        atSec: Int,
        arriveBy: Boolean,
        budgetMinutes: Int,
        walkCapMinutes: Int,
        out: File,
        width: Int = 1400,
    ) {
        val origin = network.stations.firstOrNull { it.name == originName }
            ?: error("출발역을 못 찾음: $originName")
        val seconds = if (arriveBy) router.travelTimesArrivingBy(origin.index, atSec)
                      else router.travelTimesDepartingAt(origin.index, atSec)

        val reached = network.stations
            .mapNotNull { st ->
                val s = seconds[st.index]
                if (s == UNREACHABLE) null else (st to (s + 30) / 60)
            }
            .filter { it.second <= budgetMinutes }

        require(reached.isNotEmpty()) { "도달 가능한 역이 없다" }

        // 도달 범위 + 도보 반경을 담는 경계 상자
        val padDeg = walkCapMinutes * 60 * WALK_MPS / 111_320.0 * 1.4
        val minLon = reached.minOf { it.first.lon } - padDeg
        val maxLon = reached.maxOf { it.first.lon } + padDeg
        val minLat = reached.minOf { it.first.lat } - padDeg
        val maxLat = reached.maxOf { it.first.lat } + padDeg

        val y0 = projectY(minLat); val y1 = projectY(maxLat)
        val scale = width / (maxLon - minLon)
        val height = ((y1 - y0) * scale).toInt().coerceAtLeast(200)
        fun px(lon: Double) = (lon - minLon) * scale
        fun py(lat: Double) = height - (projectY(lat) - y0) * scale
        fun metersToPx(m: Double, lat: Double) = m / (111_320.0 * Math.cos(Math.toRadians(lat))) * scale

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">""")
        sb.append("""<rect width="100%" height="100%" fill="#f4f6f8"/>""")

        // 소요시간 내림차순으로 그려야 짧은 밴드가 위로 온다
        sb.append("""<g fill-opacity="0.85">""")
        for ((st, minutes) in reached.sortedByDescending { it.second }) {
            val remaining = (budgetMinutes - minutes).coerceAtMost(walkCapMinutes).coerceAtLeast(0)
            val r = metersToPx(remaining * 60.0 * WALK_MPS, st.lat)
            if (r < 0.5) continue
            sb.append("""<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>"""
                .format(px(st.lon), py(st.lat), r, colorFor(minutes)))
        }
        sb.append("</g>")

        // 역 점
        sb.append("""<g fill="#fff" stroke="#1a4d8f" stroke-width="1">""")
        for ((st, _) in reached) {
            sb.append("""<circle cx="%.1f" cy="%.1f" r="2"/>""".format(px(st.lon), py(st.lat)))
        }
        sb.append("</g>")

        // 출발역 강조
        sb.append("""<circle cx="%.1f" cy="%.1f" r="7" fill="#e53e3e" stroke="#fff" stroke-width="2.5"/>"""
            .format(px(origin.lon), py(origin.lat)))

        val mode = if (arriveBy) "까지 도착" else "에 출발"
        val label = "%s %02d:%02d%s · %d분 이내 · 역에서 도보 %d분 · 도달역 %d개"
            .format(originName, (atSec / 3600) % 24, (atSec % 3600) / 60, mode,
                    budgetMinutes, walkCapMinutes, reached.size)
        sb.append("""<rect x="12" y="12" width="${label.length * 11 + 24}" height="34" rx="7" fill="#fff" fill-opacity="0.92"/>""")
        sb.append("""<text x="24" y="35" font-family="Malgun Gothic,sans-serif" font-size="16" fill="#14171a">$label</text>""")

        // 범례
        var lx = 12.0
        val ly = height - 34.0
        sb.append("""<rect x="6" y="${ly - 16}" width="330" height="30" rx="7" fill="#fff" fill-opacity="0.92"/>""")
        for ((m, c) in RAMP) {
            sb.append("""<rect x="%.0f" y="%.0f" width="44" height="10" fill="%s"/>""".format(lx + 6, ly - 8, c))
            sb.append("""<text x="%.0f" y="%.0f" font-family="sans-serif" font-size="10" fill="#555">%d</text>"""
                .format(lx + 6, ly + 12, m))
            lx += 48
        }
        sb.append("</svg>")

        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println("SVG 저장: ${out.absolutePath}  (${width}x$height, 도달역 ${reached.size}개)")
    }
}
