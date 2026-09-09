package reach

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * 카카오 표본(`tools/isochrone-od.json`)과 우리 값을 **같은 문 앞에서** 맞대본다.
 *
 * ## 왜 행렬을 그대로 안 읽나
 *
 * 배포 행렬은 위상 K개의 **중앙값** 하나만 들고 있다. 그 값이 카카오보다 크게 나왔을 때
 * 원인이 둘인데 행렬만 봐서는 못 가른다.
 *
 * 1. **망이 틀렸다** — 카카오가 아는 노선(급행 등)을 우리가 모른다.
 * 2. **묻는 게 다르다** — 카카오는 "지금 나가면 언제 도착"이고 우리는 "보통 얼마나
 *    걸리나"다. 배차 30분짜리 버스면 운 좋은 draw 와 기댓값이 15분 차이 난다.
 *    이건 **버그가 아니라 정의**다. 이사 갈 동네를 고르는 데엔 기댓값이 맞다.
 *
 * 그래서 여기서는 위상 표본을 **전부** 돌려 최솟값·중앙값·최댓값을 같이 낸다.
 * 판정은 이렇게 읽는다.
 *
 * | 카카오가 앉은 자리 | 뜻 |
 * |---|---|
 * | 우리 min ~ max 사이 | 망은 맞다. 차이는 배차 운뿐이다 |
 * | 우리 min 보다 **작다** | 망이 틀렸다 — 우리가 모르는 길이 있다 |
 * | 우리 max 보다 크다 | 우리가 낙관적이다 |
 *
 * 도보 모델은 [Access] 를 **생산 행렬과 공유**한다. 검증이 다른 모델을 쓰면
 * 검증이 아니다.
 */
object OdCheck {

    private class Od(
        val origin: String, val band: String, val dong: String, val gu: String,
        val olat: Double, val olon: Double, val dlat: Double, val dlon: Double,
        val ours: Int, val kakao: Int,
    )

    fun run(
        subwayGtfs: File, busGtfs: File, odJson: File, walkGraph: File?,
        phases: Int, budgetMinutes: Int, maxPerStop: Int, out: File,
    ) {
        val t0 = System.currentTimeMillis()
        val walk = walkGraph?.takeIf { it.exists() }?.let { WalkGraph.load(it) }
        if (walk == null) println("      ⚠️ 도보망이 없다 — 접근·이탈을 직선거리로 잡는다")
        val data = TransitData.load(subwayGtfs, busGtfs)
        val links = data.linkNearbyStops(maxPerStop = maxPerStop)
        println("      ${data.describe()} · 도보 환승 ${"%,d".format(links)}개 추가")

        @Suppress("UNCHECKED_CAST")
        val root = ObjectMapper().readValue(odJson, Map::class.java) as Map<String, Any?>
        val slot = root["slot"] as String            // "출발 19:00"
        require(slot.startsWith("출발")) {
            "출발 기준 슬롯만 카카오와 의미가 맞는다(카카오 PC 는 '지금 출발'뿐이다): $slot"
        }
        val hm = slot.substringAfter(' ').trim().split(':')
        val depart = hm[0].toInt() * 3600 + hm[1].toInt() * 60

        @Suppress("UNCHECKED_CAST")
        val rows = root["ods"] as List<Map<String, Any?>>
        val ods = rows.map {
            Od(
                it["origin"] as String, it["band"] as String,
                it["dong"] as String, it["gu"] as String,
                (it["olat"] as Number).toDouble(), (it["olon"] as Number).toDouble(),
                (it["dlat"] as Number).toDouble(), (it["dlon"] as Number).toDouble(),
                (it["ours"] as Number).toInt(),
                (it["kakao"] as? Number)?.toInt() ?: -1,
            )
        }
        println("      OD ${ods.size}개 · 슬롯 $slot · 예산 ${budgetMinutes}분")

        val access = Access.Walkers(walk, data)
        val k = phases.coerceAtLeast(1)
        // [DongMatrix] 와 같은 소금을 쓴다. 다른 위상을 뽑으면 배포본을 검증하는 게 아니다.
        val salts = intArrayOf(0, 1_190_311, 51_147_071, 987_654_321,
            12_345_701, 777_767_777, 424_242_469, 160_481_183)
        val fws = Array(k) { Raptor(if (it == 0) data else data.rephase(salts[it])) }

        // 출발지는 몇 개 안 되니(3곳) 좌표별로 묶어 한 번만 탐색한다.
        val originKeys = ods.map { "%.6f,%.6f".format(it.olat, it.olon) }.distinct()
        val bestsByOrigin = HashMap<String, Array<IntArray>>(originKeys.size)
        for (key in originKeys) {
            val od = ods.first { "%.6f,%.6f".format(it.olat, it.olon) == key }
            val seeds = access.from(od.olat, od.olon)
            val origins = HashMap<Int, Int>(seeds.size / 2)
            var q = 0
            while (q < seeds.size) {
                val v = depart + seeds[q + 1]
                if (v < (origins[seeds[q]] ?: Int.MAX_VALUE)) origins[seeds[q]] = v
                q += 2
            }
            println("      ${od.origin} 승차 후보 ${seeds.size / 2}개")
            bestsByOrigin[key] = Array(k) { fws[it].run(origins, depart + budgetMinutes * 60) }
        }

        val sb = StringBuilder("origin,band,dong,gu,ours,kakao,min,median,max,spread,walk,verdict\n")
        var netWrong = 0; var luckOnly = 0; var weOptimistic = 0; var unreached = 0
        for (od in ods) {
            val near = access.from(od.dlat, od.dlon)
            val bests = bestsByOrigin["%.6f,%.6f".format(od.olat, od.olon)]!!
            val got = ArrayList<Pair<Int, Int>>(k)     // (총초, 이탈도보초)
            for (pi in 0 until k) {
                val best = bests[pi]
                var bs = Raptor.INF; var bw = 0
                var j = 0
                while (j < near.size) {
                    val a = best[near[j]]
                    if (a < Raptor.INF) {
                        val v = a + near[j + 1]
                        if (v < bs) { bs = v; bw = near[j + 1] }
                    }
                    j += 2
                }
                if (bs < Raptor.INF) got += (bs - depart) to bw
            }
            if (got.isEmpty()) {
                unreached++
                sb.append(row(od, -1, -1, -1, -1, "도달못함")).append('\n')
                continue
            }
            val sorted = got.sortedBy { it.first }
            val lo = sorted.first().first / 60
            val hi = sorted.last().first / 60
            val mid = sorted[((k - 1) / 2).coerceAtMost(sorted.size - 1)].first / 60
            val w = sorted[((k - 1) / 2).coerceAtMost(sorted.size - 1)].second / 60
            val verdict = when {
                od.kakao < 0 -> "카카오없음"
                od.kakao < lo -> { netWrong++; "망결손" }
                od.kakao > hi -> { weOptimistic++; "우리낙관" }
                else -> { luckOnly++; "배차운" }
            }
            sb.append(row(od, lo, mid, hi, w, verdict)).append('\n')
        }
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())

        println()
        println("      판정 — 망결손 $netWrong · 배차운 $luckOnly · 우리낙관 $weOptimistic" +
            (if (unreached > 0) " · 도달못함 $unreached" else ""))
        println("      ${out.path} (${System.currentTimeMillis() - t0}ms)")
    }

    private fun row(od: Od, lo: Int, mid: Int, hi: Int, w: Int, v: String) =
        "${od.origin},${od.band},${od.dong},${od.gu},${od.ours},${od.kakao}," +
            "$lo,$mid,$hi,${if (lo < 0) -1 else hi - lo},$w,$v"
}
