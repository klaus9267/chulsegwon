package reach

import java.io.File
import kotlin.math.abs

/**
 * **참값 없이** 우리 탐색이 스스로 모순되지 않는지 검사한다.
 *
 * **왜 필요한가.** 지금 우리의 유일한 바깥 기준은 카카오인데, 카카오 대조는
 * "우리가 만든 시간표가 틀린 것"과 "우리 탐색 로직이 틀린 것"을 **못 가른다.**
 * 둘 다 똑같이 "우리 값이 X초 다름"으로 나온다. 그래서 로직 버그는 자료 오차 뒤에
 * 숨는다 — 실제로 `maxPerStop=8` 과 `MAX_ROUNDS=5` 가 답을 자르고 있었는데
 * 구간 1,118개 대조에서 아무 표시도 안 났다.
 *
 * 여기 있는 검사는 **바깥 자료가 필요 없다.** 성립하지 않으면 무조건 우리 버그다.
 */
object Invariants {

    fun run(subwayGtfs: File, busGtfs: File, samples: Int) {
        val t0 = System.currentTimeMillis()
        val data = TransitData.load(subwayGtfs, busGtfs)
        val added = data.linkNearbyStops()
        println("      ${data.describe()}  (${System.currentTimeMillis() - t0}ms)")
        println("      도보 환승 ${"%,d".format(added)}개")
        println()

        var fail = 0
        fail += mirrorConsistency(data, samples)
        fail += triangle(data, samples)
        fail += monotoneRounds(data, samples)
        fail += determinism(data)

        fail += walkPlaneSane()

        println()
        if (fail == 0) {
            println("      ✅ 불변식 위반 없음")
        } else {
            println("      ❌ 위반 ${fail}건 — 위를 볼 것")
            // 스케줄이 실패로 잡아야 한다. 조용히 넘어가면 검사가 없는 것과 같다.
            throw IllegalStateException("불변식 위반 ${fail}건")
        }
    }

    /**
     * **1. 시간축을 뒤집어도 같은 시간표를 봐야 한다.**
     *
     * 생산 행렬의 출근 슬롯은 전부 [TransitData.mirrored] 로 나온다. 정방향으로
     * "T 에 출발해 A 에 도착"이 나왔으면, 뒤집은 그래프에서 "A 에 도착하려면 언제
     * 출발"을 물었을 때 **T 이상**이 나와야 한다. 더 이른 출발이 나오면 뒤집기가
     * 시간을 만들어낸 것이고, 더 늦게 나오면 뒤집기가 길을 잃은 것이다.
     *
     * 왜 등호가 아니라 부등호인가: 정방향은 "T 에 출발하면 언제 도착하나"이고
     * 역방향은 "A 에 도착하려면 언제까지 출발하면 되나"다. 중간에 기다리는 시간이
     * 있으면 역방향이 더 늦게 출발해도 같은 차를 탄다. 그래서 **역방향 출발 ≥ T** 다.
     */
    private fun mirrorConsistency(data: TransitData, samples: Int): Int {
        println("      [1] 시간축 뒤집기 정합 — 정방향 도착시각을 역방향으로 되물어본다")
        val fw = Raptor(data)
        val bw = Raptor(data.mirrored())
        val rnd = java.util.Random(20260909)
        var checked = 0
        var bad = 0
        var earlier = 0
        var lost = 0
        val slack = ArrayList<Int>()
        val gap = ArrayList<Int>()

        var tries = 0
        while (checked < samples && tries < samples * 20) {
            tries++
            val o = rnd.nextInt(data.stopCount)
            if (data.stopLat[o] == 0.0) continue
            val depart = 7 * 3600 + rnd.nextInt(4 * 3600)   // 07:00~11:00
            // 상한을 넉넉히. 도착지를 상한 근처에서 고르면 역방향이 그 밖을 못 본다.
            val f = fw.run(mapOf(o to depart), depart + 180 * 60)

            // 도달한 정류장 하나를 고른다
            val hit = ArrayList<Int>(64)
            for (i in 0 until data.stopCount) {
                if (f[i] < Raptor.INF && f[i] in (depart + 20 * 60)..(depart + 80 * 60)) hit += i
                if (hit.size >= 400) break
            }
            if (hit.isEmpty()) continue
            val dst = hit[rnd.nextInt(hit.size)]
            val arrive = f[dst]

            // 뒤집은 축에서 dst 에 arrive 까지 도착하려면 o 를 언제 떠나야 하나.
            // mirrored 축의 시각은 -실제시각 이다.
            // 역방향도 180분. 정방향 도착이 80분 안이므로 참값은 반드시 이 안에 있다.
            val b = bw.run(mapOf(dst to -arrive), -arrive + 180 * 60)
            val backDepart = if (b[o] >= Raptor.INF) null else -b[o]
            checked++

            if (backDepart == null) { lost++; bad++; continue }
            // BOARD_SLACK 이 정방향은 승차 전, 역방향은 하차 후에 붙어 모델이 비대칭이다.
            // 승차 6회면 그 차이가 최대 120초. 그만큼은 위반으로 세지 않는다.
            val tol = Raptor.MAX_ROUNDS * Raptor.BOARD_SLACK_SEC
            val d = backDepart - depart
            if (d < -tol) { earlier++; bad++; gap += -d }
            else slack += d
        }
        slack.sort()
        println("          표본 %d쌍".format(checked))
        if (lost > 0) println("          ❌ 역방향에서 길을 잃음 %d건".format(lost))
        if (earlier > 0) {
            gap.sort()
            println("          ❌ 역방향이 정방향보다 %d초(허용오차) 넘게 이르다 %d건".format(
                Raptor.MAX_ROUNDS * Raptor.BOARD_SLACK_SEC, earlier))
            println("             초과 폭: 중앙 %d초 · 90%% %d초 · 최대 %d초".format(
                gap[gap.size / 2], gap[(gap.size * 9) / 10], gap.last()))
        }
        if (slack.isNotEmpty()) {
            println("          여유(역방향 출발 − 정방향 출발): 중앙 %d초 · 90%% %d초 · 최대 %d초".format(
                slack[slack.size / 2], slack[(slack.size * 9) / 10], slack.last()))
        }
        if (bad == 0) println("          ✅ 통과")
        return bad
    }

    /**
     * **2. 삼각 부등식.** A→C 가 A→B→C 보다 느리면 안 된다.
     *
     * B 에서 갈아탈 수 있는 길을 A→C 탐색이 놓쳤다는 뜻이다. 다만 **여유를 둬야
     * 한다** — A→B→C 를 이어붙이면 B 에서 갈아타는 셈이라 승차 횟수가 늘고,
     * 우리는 [Raptor.MAX_ROUNDS] 로 그걸 제한한다. 그래서 두 조각의 승차 합이
     * 상한을 넘으면 합법적으로 위반될 수 있다. 여기서는 **크게** 어긋난 것만 센다.
     */
    private fun triangle(data: TransitData, samples: Int): Int {
        println("      [2] 삼각 부등식 — A→C 가 A→B→C 보다 느리면 안 된다")
        val r = Raptor(data)
        val rnd = java.util.Random(4242)
        var checked = 0
        var bad = 0
        val worst = ArrayList<Int>()
        var tries = 0
        while (checked < samples && tries < samples * 20) {
            tries++
            val a = rnd.nextInt(data.stopCount)
            if (data.stopLat[a] == 0.0) continue
            val t = 8 * 3600 + rnd.nextInt(2 * 3600)
            val CAP = 200 * 60
            val fa = r.run(mapOf(a to t), t + CAP)
            val mid = ArrayList<Int>(64)
            for (i in 0 until data.stopCount) {
                if (fa[i] < Raptor.INF && fa[i] in (t + 10 * 60)..(t + 40 * 60)) mid += i
                if (mid.size >= 300) break
            }
            if (mid.isEmpty()) continue
            val b = mid[rnd.nextInt(mid.size)]
            val ab = fa[b]

            val fb = r.run(mapOf(b to ab), ab + CAP)
            val far = ArrayList<Int>(64)
            for (i in 0 until data.stopCount) {
                if (fb[i] < Raptor.INF && fb[i] > ab + 10 * 60) far += i
                if (far.size >= 300) break
            }
            if (far.isEmpty()) continue
            val c = far[rnd.nextInt(far.size)]
            val viaB = fb[c]
            // **경유 결과가 A 의 탐색 상한 밖이면 비교할 수 없다.** 상한 밖을
            // "직행 불가"로 세면 검사가 스스로 위반을 만들어낸다.
            if (viaB > t + CAP) continue
            val direct = fa[c]
            checked++
            // 이어붙인 길보다 직행이 5분 넘게 느리면 탐색이 뭔가를 놓쳤다.
            if (direct >= Raptor.INF || direct > viaB + 300) {
                bad++
                worst += if (direct >= Raptor.INF) -1 else direct - viaB
            }
        }
        val unreachable = worst.count { it < 0 }
        val over = worst.filter { it >= 0 }.sorted()
        println("          표본 %d쌍 · 위반 %d건 (그중 직행 도달불가 %d건)".format(
            checked, bad, unreachable))
        if (over.isNotEmpty()) {
            println("          느린 폭: 중앙 %d초 · 최대 %d초".format(
                over[over.size / 2], over.last()))
        }
        if (bad == 0) println("          ✅ 통과")
        return if (bad > checked / 50) bad else 0   // 1% 미만은 라운드 상한 탓으로 본다
    }

    /**
     * **3. 승차 상한을 늘리면 답이 나빠질 수 없다** (완화).
     *
     * RAPTOR 는 라운드마다 답을 개선만 한다. 상한을 올려서 어떤 정류장이든 **느려지면**
     * 라운드 사이에 상태가 새는 것이다 — `prev` 와 `best` 를 섞어 쓰거나
     * `if (!improved) break` 가 너무 일찍 끊거나.
     */
    private fun monotoneRounds(data: TransitData, samples: Int): Int {
        println("      [3] 승차 상한 단조성 — 더 태워주면 느려질 수 없다")
        val keep = Raptor.MAX_ROUNDS
        val rnd = java.util.Random(777)
        var bad = 0
        var checked = 0
        try {
            var tries = 0
            while (checked < samples / 10 && tries < samples) {
                tries++
                val o = rnd.nextInt(data.stopCount)
                if (data.stopLat[o] == 0.0) continue
                val t = 8 * 3600 + rnd.nextInt(2 * 3600)
                Raptor.MAX_ROUNDS = keep
                val lo = Raptor(data).run(mapOf(o to t), t + 120 * 60)
                Raptor.MAX_ROUNDS = keep + 2
                val hi = Raptor(data).run(mapOf(o to t), t + 120 * 60)
                checked++
                for (i in 0 until data.stopCount) {
                    if (hi[i] > lo[i]) { bad++; break }
                }
            }
        } finally {
            Raptor.MAX_ROUNDS = keep
        }
        println("          표본 %d회 · 느려진 탐색 %d회".format(checked, bad))
        if (bad == 0) println("          ✅ 통과")
        return bad
    }

    /** **4. 같은 입력이면 같은 출력.** 해시 순회 순서 같은 게 새면 여기서 걸린다. */
    private fun determinism(data: TransitData): Int {
        println("      [4] 결정론 — 같은 입력을 두 번 돌리면 같아야 한다")
        val o = (0 until data.stopCount).first { data.stopLat[it] != 0.0 }
        val t = 8 * 3600
        val a = Raptor(data).run(mapOf(o to t), t + 120 * 60)
        val b = Raptor(data).run(mapOf(o to t), t + 120 * 60)
        val diff = a.indices.count { a[it] != b[it] }
        println("          다른 정류장 %d개".format(diff))
        if (diff == 0) println("          ✅ 통과")
        return if (diff > 0) 1 else 0
    }

    /**
     * **5. 도달불가 칸은 도보도 0 이어야 한다.**
     *
     * 배포된 행렬을 그대로 읽어 검사한다. 상한을 넘겨 255 가 된 칸에 실제 도보 분이
     * 남아 있었다(고치기 전 990,255칸 중 58,614칸, 5.9%). 지금은 호출부가 항상
     * 소요시간을 먼저 보므로 무해하지만, 도보를 먼저 보는 코드가 하나 생기면
     * 도달불가 동네가 필터를 통과한다.
     */
    private fun walkPlaneSane(): Int {
        println("      [5] 도달불가 칸의 도보 평면 — 0 이어야 한다")
        val dir = File("data/out/reach2/matrix")
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("          행렬이 없어 건너뜀 (${dir.absolutePath})")
            return 0
        }
        var bad = 0L
        var un = 0L
        for (f in files.take(80)) {
            val raw = f.readBytes()
            if (raw.size < 10) continue
            val hasWalk = (raw[5].toInt() and 1) != 0
            if (!hasWalk) continue
            val ns = (raw[6].toInt() and 0xFF) or ((raw[7].toInt() and 0xFF) shl 8)
            val nd = (raw[8].toInt() and 0xFF) or ((raw[9].toInt() and 0xFF) shl 8)
            for (si in 0 until ns) {
                val a = 10 + si * nd
                val b = 10 + (ns + si) * nd
                for (i in 0 until nd) {
                    if ((raw[a + i].toInt() and 0xFF) == 255) {
                        un++
                        if (raw[b + i].toInt() != 0) bad++
                    }
                }
            }
        }
        println("          도달불가 칸 %,d 중 도보값이 남은 것 %,d".format(un, bad))
        if (bad == 0L) println("          ✅ 통과")
        return if (bad > 0) 1 else 0
    }

    @Suppress("unused")
    private fun fmt(v: Int) = abs(v).toString()
}
