package reach

import java.io.File

/**
 * 우리 RAPTOR 의 답을 **대조 가능한 형태로** 뱉는다.
 *
 * 정류장 하나에서 출발해 닿는 모든 정류장의 도착시각을 찍는다. 같은 시간표를 먹인
 * OTP2 와 이 값을 대면 **순수하게 라우팅 로직 차이**만 남는다 — 카카오 대조로는
 * 절대 못 가르는 것이다([GtfsExpand] 의 주석을 볼 것).
 *
 * **왜 시간표가 같은가.** OTP 에는 [GtfsExpand] 가 펼친 피드를 주는데, 그 펼치기가
 * 우리 위상(`start + k×headway`)을 그대로 시각으로 박은 것이다. 그래서 우리는 원본
 * frequency 피드를 읽고 OTP 는 펼친 피드를 읽어도 **두 쪽이 보는 차 시각이 같다.**
 */
object Oracle {

    fun run(subwayGtfs: File, busGtfs: File, fromName: String, atHm: String, out: File) {
        val t0 = System.currentTimeMillis()
        val data = TransitData.load(subwayGtfs, busGtfs)
        val added = data.linkNearbyStops(maxMeters = 400.0)
        println("      ${data.describe()}  (${System.currentTimeMillis() - t0}ms)")
        println("      도보 환승 ${"%,d".format(added)}개 생성")

        val at = parseHm(atHm)
        // 이름의 **첫 낱말**이 같은 정류장 전부에서 동시에 출발한다. 지하철 승강장이
        // "강남 2"·"강남 S" 처럼 노선 코드를 뒤에 달고 있어서다.
        //
        // ⚠️ 접두 일치(startsWith)로 하면 안 된다. "강남" 이 **강남구청·강남대**(용인)
        // 까지 잡아 112개가 출발지가 된다. `--mode raptor` 와 같은 규칙을 쓴다.
        val origins = (0 until data.stopCount)
            .filter { data.stopNames[it].substringBefore(' ') == fromName }
            .associateWith { at }
        require(origins.isNotEmpty()) { "출발 정류장을 못 찾았다: $fromName" }
        println("      출발 '$fromName' 정류장 ${origins.size}개 · $atHm 출발")

        val best = Raptor(data).run(origins, at + 3 * 3600)
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            w.write("stop_id,stop_name,lat,lon,arrival_sec\n")
            var n = 0
            for (i in 0 until data.stopCount) {
                val v = best[i]
                if (v >= Raptor.INF) continue
                if (data.stopLat[i] == 0.0) continue
                w.write(data.stopIds[i]); w.write(",")
                w.write(data.stopNames[i].replace(',', ' ')); w.write(",")
                w.write(data.stopLat[i].toString()); w.write(",")
                w.write(data.stopLon[i].toString()); w.write(",")
                w.write((v - at).toString()); w.write("\n")
                n++
            }
            println("      닿은 정류장 ${"%,d".format(n)} / ${"%,d".format(data.stopCount)}")
        }
        println("      -> ${out.absolutePath}")
    }

    private fun parseHm(v: String): Int {
        val p = v.split(':')
        require(p.size == 2) { "시각 형식이 HH:MM 이 아니다: $v" }
        return p[0].toInt() * 3600 + p[1].toInt() * 60
    }
}
