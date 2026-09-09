package reach

import java.io.File
import java.nio.charset.Charset

/**
 * 1~9호선 **실제 열차운행시각표**를 운행 패턴으로 바꾼다.
 *
 * ## 왜
 *
 * [Headways] 는 "추정치다, 실제 시간표가 아니다"라고 스스로 적어뒀다. 그 추정이
 * 얼마짜리인지 [ADR-37] 에서 쟀다 — 카카오 대조 편향 +5.5분 중 **+3.0분이
 * 급행을 모르는 대가**였고, 격차 상위 7건이 전부 급행 축이었다.
 *
 * 그런데 실제 시간표가 공개돼 있다. 서울교통공사 「서울 도시철도 열차운행시각표」
 * (공공데이터포털 15098251, 이용허락범위 제한 없음)는 **열차 한 편마다 역별
 * 출·도착 시각과 급행 여부**를 준다. 1호선은 코레일 구간(동인천·서동탄·신창)까지
 * 102역이 들어 있고, 8호선 별내·4호선 진접처럼 2020년 이후 개통분도 있다.
 *
 * ## 무엇을 만드나
 *
 * 열차 4,811편(평일)을 **정차 시퀀스가 같은 것끼리 묶어** 패턴 297개로 만든다.
 * 패턴마다 구간 소요시간은 편들의 **중앙값**을 쓰고, 출발 시각은 **편마다 그대로**
 * 창 `(t, t+1, 1초)` 로 넣는다.
 *
 * ⚠️ **처음엔 30분 구간의 평균 배차로 넣었다가 되돌렸다.** 그게 왜 틀렸냐면,
 * 배차가 성긴 노선에서 **우리가 아는 시각을 도로 버리기** 때문이다.
 * 경부선 급행은 저녁에 수원을 시간당 1~2편만 지난다. 그걸 "30분 안 어딘가"로
 * 뭉개면 19:00 출발 질의에서 대기가 20~50분으로 흩어진다. 실제로는 19:30 에
 * 오는 걸 알고 있는데도. 카카오 대조에서 수원→서울역이 우리 69분 · 카카오 52분으로
 * 갈린 게 정확히 이 자리였다.
 *
 * 배차 창이 아니라 실제 시각을 넣으면 **위상 무작위화가 저절로 꺼진다** —
 * [TransitData.bake] 는 창 시작을 `위상 mod 배차` 만큼 미는데 배차가 1초면 0 이다.
 * 시간표를 아는 노선에는 위상 표본이 필요 없다. 그게 맞다.
 *
 * 대가는 탐색 비용이다. 창이 패턴당 30~40개에서 편수(최대 120개)로 는다.
 * 지하철 패턴은 297개뿐이고 버스가 14,891개라 전체로는 묻힌다.
 *
 * ## 이 자료가 못 덮는 것
 *
 * 서울교통공사가 다루는 1~9호선뿐이다. 경의중앙·수인분당·경춘·신분당·공항철도·
 * 서해·인천1·2·의정부·에버라인·우이신설·김포골드·경강은 여전히 [Headways] 의
 * 합성 시간표로 돈다. 그쪽 급행(경의중앙·수인분당)은 아직 모른다.
 */
object RailTimetable {

    /**
     * 시각표 역명 → `metro_graph.gml` 역명.
     *
     * GML 쪽에 **어순이 뒤집힌 이름**이 있다(`문화공원동대문역사`). 원본의 특징이라
     * 여기서 흡수한다. 나머지는 개명(총신대입구→이수, 당고개→불암산)이다.
     */
    private val ALIAS = mapOf(
        "동대문역사문화공원" to "문화공원동대문역사",
        "신대방삼거리" to "삼거리신대방",
        "이수" to "총신대입구",
        "자양" to "뚝섬유원지",
        "불암산" to "당고개",
        "평택지제" to "지제",
    )


    class Pattern(
        val line: String,
        val express: Boolean,
        /** 승강장 색인(= [Network.platforms] 의 자리). */
        val stops: IntArray,
        /** 첫 정차로부터의 경과초. 편들의 중앙값. */
        val offsets: IntArray,
        /**
         * (시작, 끝, 배차초) 삼중항의 평탄 배열. 편마다 하나씩이고 배차는 1초다 —
         * 실제 시각을 그대로 넣는다는 뜻이다. 자세한 건 이 파일 머리말.
         */
        val windows: IntArray,
        val trips: Int,
    )

    fun load(csv: File, network: Network): List<Pattern> {
        // 승강장 찾기: (노선, 역명) → 색인.
        val byName = HashMap<String, Int>(network.platforms.size * 2)
        for (p in network.platforms) {
            byName["${p.line}\u0000${network.stations[p.stationIndex].name}"] = p.index
        }

        val lines = csv.readLines(Charset.forName("MS949"))
        require(lines.isNotEmpty()) { "시각표가 비었다: ${csv.absolutePath}" }
        val head = splitCsv(lines[0])
        fun col(n: String) = head.indexOf(n).also { require(it >= 0) { "열이 없다: $n ($head)" } }
        val cLine = col("호선"); val cName = col("역사명"); val cDay = col("주중주말")
        val cDir = col("방향"); val cExp = col("급행여부"); val cCode = col("열차코드")
        val cArr = col("열차도착시간"); val cDep = col("열차출발시간")

        // 열차 한 편 = (호선, 열차코드, 방향).
        class Stop(val sec: Int, val plat: Int)
        val trains = LinkedHashMap<String, MutableList<Stop>>(8192)
        val trainExpress = HashMap<String, Boolean>(8192)
        var unmapped = 0
        val unmappedNames = HashSet<String>()

        for (i in 1 until lines.size) {
            val v = splitCsv(lines[i])
            if (v.size <= cDep) continue
            if (v[cDay] != "DAY") continue        // 평일만
            val line = v[cLine]
            val sec = hms(v[cDep]) ?: hms(v[cArr]) ?: continue
            val name = v[cName]
            val plat = byName["$line\u0000${ALIAS[name] ?: name}"]
            if (plat == null) { unmapped++; unmappedNames += "$line $name"; continue }
            val key = "$line\u0000${v[cCode]}\u0000${v[cDir]}"
            trains.getOrPut(key) { ArrayList(60) } += Stop(sec, plat)
            if (v[cExp] == "1") trainExpress[key] = true
        }

        // 패턴으로 묶는다.
        class Acc(val stops: IntArray, val express: Boolean, val line: String) {
            val starts = ArrayList<Int>(64)
            val legs = Array(stops.size) { ArrayList<Int>(64) }
        }
        val pats = LinkedHashMap<String, Acc>(512)
        for ((key, raw) in trains) {
            val line = key.substringBefore('\u0000')
            raw.sortBy { it.sec }
            // 자정을 넘긴 편은 시각이 되감긴다. 크게 뒤로 뛰면 하루를 더한다.
            val seq = ArrayList<Stop>(raw.size)
            var prev = Int.MIN_VALUE
            for (s in raw) {
                var t = s.sec
                if (prev != Int.MIN_VALUE && t < prev - 3600) t += 86400
                prev = t
                if (seq.isNotEmpty() && seq.last().plat == s.plat) continue
                seq += Stop(t, s.plat)
            }
            if (seq.size < 2) continue
            val express = trainExpress[key] == true
            val sig = StringBuilder(line).append(if (express) "|X|" else "|N|")
            for (s in seq) sig.append(s.plat).append(',')
            val acc = pats.getOrPut(sig.toString()) {
                Acc(IntArray(seq.size) { seq[it].plat }, express, line)
            }
            acc.starts += seq[0].sec
            for (i in seq.indices) acc.legs[i] += seq[i].sec - seq[0].sec
        }

        val out = ArrayList<Pattern>(pats.size)
        for (a in pats.values) {
            val offs = IntArray(a.stops.size) { median(a.legs[it]) }
            // 단조 증가를 강제한다. 중앙값을 자리마다 따로 잡으면 어긋날 수 있다.
            for (i in 1 until offs.size) if (offs[i] <= offs[i - 1]) offs[i] = offs[i - 1] + 30
            // 편마다 창 하나. 끝을 시작+1 로 두는 건 로더가 `end <= start` 를
            // 버리기 때문이고, 배차 1초는 위상 이동을 0 으로 만들기 위한 것이다.
            val w = ArrayList<Int>(a.starts.size * 3)
            for (t in a.starts.distinct().sorted()) { w += t; w += t + 1; w += 1 }
            if (w.isEmpty()) continue
            out += Pattern(a.line, a.express, a.stops, offs, w.toIntArray(), a.starts.size)
        }

        val byLine = out.groupingBy { it.line }.eachCount().toSortedMap()
        println("      실측 시각표 ${csv.name}: 평일 열차 ${"%,d".format(trains.size)}편 " +
            "→ 패턴 ${out.size}개 " + byLine.entries.joinToString(" ") { "${it.key}호선${it.value}" })
        println("      급행 패턴 ${out.count { it.express }}개 · " +
            "실제 출발시각 ${"%,d".format(out.sumOf { it.windows.size / 3 })}개 · " +
            "GML 에 없어 건너뛴 정차 ${"%,d".format(unmapped)}건 (역 ${unmappedNames.size}개)")
        return out
    }

    private fun median(v: List<Int>): Int {
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun hms(v: String): Int? {
        val t = v.trim().trim('"')
        if (t.length < 8) return null
        val h = t.substring(0, 2).toIntOrNull() ?: return null
        val m = t.substring(3, 5).toIntOrNull() ?: return null
        val s = t.substring(6, 8).toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    /** 따옴표가 일부 열에만 붙어 있어서 직접 쪼갠다. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(16)
        val sb = StringBuilder()
        var q = false
        for (c in line) when {
            c == '"' -> q = !q
            c == ',' && !q -> { out += sb.toString().trim(); sb.setLength(0) }
            else -> sb.append(c)
        }
        out += sb.toString().trim()
        return out
    }
}
