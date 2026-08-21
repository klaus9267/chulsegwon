package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 외부 경로탐색 서비스에서 손으로 뽑은 기준값과 우리 계산을 대조한다.
 *
 * 전수 대조(621×621)는 어느 서비스든 약관 위반이라 하지 않는다. 표본으로 오차 분포를
 * 보는 것이 목적이고, 그 정도면 "모델이 대체로 맞는가"라는 질문에는 충분히 답한다.
 *
 * 기준값 파일 형식:
 * ```json
 * { "origin": "강남", "departAt": "20:30",
 *   "note": "카카오맵 지하철 경로, 평일 20:30 출발",
 *   "rows": [ { "to": "역삼", "boardAt": "20:34", "arriveAt": "20:35", "rideMinutes": 1 } ] }
 * ```
 *
 * 비교는 **문 앞에서 문 앞까지**(출발 기준시각 → 도착시각)로 한다. 우리 값은 대기를
 * 포함하는데 외부 서비스의 대표 숫자(`rideMinutes`)는 승차~하차만 세는 경우가 많아,
 * 그대로 비교하면 우리가 항상 나쁘게 나온다.
 */
object Compare {

    private data class Row(
        val to: String,
        val boardAt: String? = null,
        val rideMinutes: Int? = null,
        val stops: Int? = null,
        val transfer: String? = null,
    )

    private data class Reference(
        val origin: String,
        val departAt: String,
        val note: String = "",
        val method: String = "",
        val rows: List<Row>,
    )

    private fun hm(s: String): Int {
        val (h, m) = s.split(":").map { it.toInt() }
        return h * 3600 + m * 60
    }

    fun run(network: Network, router: Router, file: File) {
        val ref: Reference = ObjectMapper().registerKotlinModule().readValue(file.readText())
        val origin = network.stations.firstOrNull { it.name == ref.origin }
            ?: error("출발역을 못 찾음: ${ref.origin}")
        val departSec = hm(ref.departAt)
        val ours = router.travelTimesDepartingAt(origin.index, departSec)

        println("=== 대조: ${ref.origin} ${ref.departAt} 출발 ===")
        if (ref.note.isNotEmpty()) println("기준: ${ref.note}")
        println()
        println("%-10s %8s %8s %8s   %s".format("도착역", "우리", "카카오", "차이", "비고"))
        println("-".repeat(58))

        val diffs = mutableListOf<Int>()
        var missing = 0

        for (row in ref.rows) {
            val st = network.stations.firstOrNull { it.name == row.to }
            if (st == null) {
                println("%-10s %8s %8s %8s   %s".format(row.to, "-", "-", "-", "우리 데이터에 없는 역"))
                missing++
                continue
            }
            val oursMin = ours[st.index].let { if (it == UNREACHABLE) null else (it + 30) / 60 }
            // 문앞-문앞 = 대기(승차시각 - 기준시각) + 승차시간.
            // 도착시각을 그대로 쓰지 않는 이유: 화면에서 긁은 도착시각이 막차 안내 등
            // 다른 시각을 집는 경우가 있어 내부 정합성이 깨진다.
            val refMin = row.rideMinutes?.let { ride ->
                ride + (row.boardAt?.let { (hm(it) - departSec) / 60 } ?: 0)
            }

            if (oursMin == null || refMin == null) {
                println("%-10s %8s %8s %8s   %s".format(row.to, oursMin ?: "도달불가", refMin ?: "-", "-", ""))
                missing++
                continue
            }
            val d = oursMin - refMin
            diffs += d
            val flag = when {
                abs(d) <= 3 -> ""
                abs(d) <= 7 -> "△"
                else -> "❗"
            }
            val extra = listOfNotNull(row.stops?.let { "$it 개역" }, row.transfer).joinToString(" ")
            println("%-10s %7d분 %7d분 %+7d분   %s %s".format(row.to, oursMin, refMin, d, flag, extra))
        }

        if (diffs.isEmpty()) { println("\n비교 가능한 행이 없다"); return }
        val sorted = diffs.sorted()
        val mean = diffs.average()
        val mae = diffs.map { abs(it) }.average()
        println("-".repeat(58))
        println("표본 %d개 (제외 %d개)".format(diffs.size, missing))
        println("평균 오차(편향) %+.1f분   절대 평균 오차 %.1f분".format(mean, mae))
        println("중앙값 %+d분   최소 %+d분   최대 %+d분".format(sorted[sorted.size / 2], sorted.first(), sorted.last()))
        println("±3분 이내 %d%%   ±7분 이내 %d%%".format(
            (diffs.count { abs(it) <= 3 } * 100.0 / diffs.size).roundToInt(),
            (diffs.count { abs(it) <= 7 } * 100.0 / diffs.size).roundToInt(),
        ))
    }
}
