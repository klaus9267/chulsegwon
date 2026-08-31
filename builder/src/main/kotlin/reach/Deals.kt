package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 국토교통부 실거래가를 모아 **단지 단위**로 요약한다.
 *
 * 도달권만으로는 "그래서 뭐?" 에 걸린다. "40분 안, 전세 4억 이하 단지 27곳" 까지 나와야
 * 도구가 된다. 그 두 번째 축이 이 데이터다.
 *
 * ⚠️ 좌표가 없다. API 가 주는 건 `법정동코드 + 지번 + 도로명 + 단지명` 뿐이라, 지도에
 * 찍으려면 지오코딩이 한 단계 더 필요하다. 그래서 여기서는 좌표 없는 단지 목록까지만
 * 만들고, 좌표는 [Geocode] 가 따로 채운다. `aptSeq` 가 단지 고유키라 지오코딩을
 * 단지당 한 번만 하고 캐시할 수 있다.
 */
object Deals {

    private const val BASE = "https://apis.data.go.kr/1613000"

    /**
     * 아파트만 쓴다.
     *
     * 오피스텔·연립다세대도 받아봤지만 `aptSeq`(단지 고유키)가 없어서 단지 식별이 안 된다.
     * 이름 해시로 대체하면 같은 건물이 여러 단지로 쪼개지고, 실제로 3개 구 3개월에
     * 5,808단지 중 4,233개가 해시 대체에 56%가 거래 1건짜리였다. 노이즈가 신호를 덮는다.
     *
     * 전월세는 반드시 넣는다. 매매보다 8배 많고(강남구 1,244 vs 160/월) 이사할 때
     * 실제로 보는 건 전월세다.
     */
    private val ENDPOINTS = listOf(
        Endpoint("APT_TRADE", "RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev"),
        Endpoint("APT_RENT", "RTMSDataSvcAptRent", "getRTMSDataSvcAptRent"),
    )

    private class Endpoint(val kind: String, val service: String, val operation: String)

    /**
     * 수도권 시군구 법정동코드 앞 5자리.
     * 서울 25 + 인천 10 + 경기 31. 실거래가 API 는 이 단위로만 조회된다.
     */
    val SUDOGWON: List<String> = listOf(
        // 서울
        "11110", "11140", "11170", "11200", "11215", "11230", "11260", "11290", "11305",
        "11320", "11350", "11380", "11410", "11440", "11470", "11500", "11530", "11545",
        "11560", "11590", "11620", "11650", "11680", "11710", "11740",
        // 인천
        "28110", "28140", "28177", "28185", "28200", "28237", "28245", "28260", "28710", "28720",
        // 경기
        "41111", "41113", "41115", "41117", "41131", "41133", "41135", "41150", "41171",
        "41173", "41190", "41210", "41220", "41250", "41271", "41273", "41281", "41285",
        "41287", "41290", "41310", "41360", "41370", "41390", "41410", "41430", "41450",
        "41461", "41463", "41465", "41480", "41500", "41550", "41570", "41590", "41610",
        "41630", "41650", "41670", "41800", "41820", "41830",
    )

    /** 거래 한 건. 우리가 실제로 쓰는 필드만 뽑는다. */
    private class Deal(
        val aptSeq: String,
        val name: String,
        val sggCd: String,
        val umdNm: String,
        val jibun: String,
        val roadNm: String,
        val roadBonbun: String,
        val roadBubun: String,
        val buildYear: String,
        val areaM2: Double,
        /** 매매가 또는 전세보증금 (만원). 월세는 보증금만 담고 monthly 에 월세를 둔다. */
        val amountManwon: Int,
        val monthlyManwon: Int,
        val kind: String,
        val yearMonth: String,
    )

    fun run(key: String, months: Int, outDir: File, sggFilter: List<String>?) {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
        val targets = sggFilter ?: SUDOGWON
        val periods = recentMonths(months)
        val deals = ArrayList<Deal>(200_000)

        var done = 0
        val total = targets.size * periods.size * ENDPOINTS.size
        for (sgg in targets) {
            for (ym in periods) {
                for (ep in ENDPOINTS) {
                    val body = fetch(client, key, ep, sgg, ym)
                    if (body != null) deals += parse(body, ep.kind, ym, sgg)
                    done++
                    if (done % 50 == 0) println("      $done/$total 요청, 누적 거래 ${"%,d".format(deals.size)}건")
                }
            }
        }
        println("      요청 $total 회 완료, 거래 ${"%,d".format(deals.size)}건")

        val complexes = summarize(deals)
        outDir.mkdirs()
        val mapper = ObjectMapper().registerKotlinModule()
        val file = File(outDir, "complexes-raw.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(
            file,
            mapOf(
                "generatedFrom" to "국토교통부 실거래가 오픈API",
                "months" to periods,
                "sggCount" to targets.size,
                "note" to "좌표 없음. Geocode 로 채운 뒤에 지도에 쓸 것.",
                "complexes" to complexes,
            ),
        )
        println("      단지 ${complexes.size}개 -> ${file.absolutePath} (${file.length() / 1024}KB)")
    }

    private fun recentMonths(n: Int): List<String> {
        // 실거래는 계약 후 30일 내 신고라 최근 달은 비어 있다. 한 달 앞에서 시작한다.
        val base = LocalDate.now().minusMonths(1)
        val fmt = DateTimeFormatter.ofPattern("yyyyMM")
        return (0 until n).map { base.minusMonths(it.toLong()).format(fmt) }
    }

    private fun fetch(
        client: HttpClient,
        key: String,
        ep: Endpoint,
        sgg: String,
        ym: String,
    ): String? {
        val url = "$BASE/${ep.service}/${ep.operation}" +
            "?serviceKey=" + URLEncoder.encode(key, Charsets.UTF_8) +
            "&LAWD_CD=$sgg&DEAL_YMD=$ym&numOfRows=1000&pageNo=1"
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET()
                        .timeout(java.time.Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                if (res.statusCode() == 200) return res.body()
                // 공공 API 는 일시적으로 500/503 을 잘 낸다. 몇 번은 참는다.
                Thread.sleep(500L * (attempt + 1))
            } catch (e: Exception) {
                Thread.sleep(500L * (attempt + 1))
            }
        }
        System.err.println("      ! 실패 ${ep.kind} $sgg $ym")
        return null
    }

    private fun tag(xml: String, from: Int, end: Int, name: String): String {
        val open = "<$name>"
        val s = xml.indexOf(open, from)
        if (s < 0 || s > end) return ""
        val e = xml.indexOf("</$name>", s)
        if (e < 0 || e > end) return ""
        return xml.substring(s + open.length, e).trim()
    }

    private fun parse(xml: String, kind: String, ym: String, sgg: String): List<Deal> {
        val out = ArrayList<Deal>()
        var i = xml.indexOf("<item>")
        while (i >= 0) {
            val end = xml.indexOf("</item>", i)
            if (end < 0) break
            val seq = tag(xml, i, end, "aptSeq")
            val name = tag(xml, i, end, "aptNm")
            // aptSeq 가 없으면 단지를 식별할 수 없다. 이름 해시로 대체하면 같은 건물이
            // 여러 단지로 쪼개지므로 아예 버린다.
            if (name.isNotEmpty() && seq.isNotEmpty()) {
                val amount = tag(xml, i, end, "dealAmount").ifEmpty { tag(xml, i, end, "deposit") }
                out += Deal(
                    aptSeq = seq,
                    name = name,
                    sggCd = sgg,
                    umdNm = tag(xml, i, end, "umdNm"),
                    jibun = tag(xml, i, end, "jibun"),
                    roadNm = tag(xml, i, end, "roadNm"),
                    roadBonbun = tag(xml, i, end, "roadNmBonbun"),
                    roadBubun = tag(xml, i, end, "roadNmBubun"),
                    buildYear = tag(xml, i, end, "buildYear"),
                    areaM2 = tag(xml, i, end, "excluUseAr").toDoubleOrNull() ?: 0.0,
                    amountManwon = amount.replace(",", "").trim().toIntOrNull() ?: 0,
                    monthlyManwon = tag(xml, i, end, "monthlyRent").replace(",", "").trim()
                        .toIntOrNull() ?: 0,
                    kind = kind,
                    yearMonth = ym,
                )
            }
            i = xml.indexOf("<item>", end)
        }
        return out
    }

    /**
     * 단지 단위로 접는다.
     *
     * 중위값을 쓰는 이유: 같은 단지 안에서도 면적·층에 따라 값이 크게 흩어진다.
     * 평균은 초고층 펜트하우스 한 건에 끌려간다.
     */
    private fun summarize(deals: List<Deal>): List<Map<String, Any?>> =
        deals.groupBy { it.aptSeq }.map { (seq, group) ->
            val latest = group.maxByOrNull { it.yearMonth }!!
            fun median(v: List<Int>): Int? =
                v.filter { it > 0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

            val jeonse = group.filter { it.kind.endsWith("RENT") && it.monthlyManwon == 0 }
            val sale = group.filter { it.kind == "APT_TRADE" }
            mapOf(
                "aptSeq" to seq,
                "name" to latest.name,
                "sggCd" to latest.sggCd,
                // 지오코딩용 주소. 도로명이 있으면 그게 정확하고, 없으면 지번으로 떨어진다.
                "roadAddress" to
                    (if (latest.roadNm.isNotEmpty())
                        "${latest.roadNm} ${latest.roadBonbun.trimStart('0')}" +
                            (latest.roadBubun.trimStart('0').takeIf { it.isNotEmpty() }
                                ?.let { "-$it" } ?: "")
                    else ""),
                "jibunAddress" to "${latest.umdNm} ${latest.jibun}",
                "buildYear" to latest.buildYear,
                "deals" to group.size,
                "saleMedianManwon" to median(sale.map { it.amountManwon }),
                "jeonseMedianManwon" to median(jeonse.map { it.amountManwon }),
                "areaMinM2" to group.mapNotNull { it.areaM2.takeIf { a -> a > 0 } }.minOrNull(),
                "areaMaxM2" to group.mapNotNull { it.areaM2.takeIf { a -> a > 0 } }.maxOrNull(),
            )
        }.sortedByDescending { it["deals"] as Int }
}
