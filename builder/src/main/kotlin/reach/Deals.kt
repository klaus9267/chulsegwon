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
    private val APT = listOf(
        Endpoint("APT_TRADE", "RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev"),
        Endpoint("APT_RENT", "RTMSDataSvcAptRent", "getRTMSDataSvcAptRent"),
    )

    /**
     * 자취 타겟 — 빌라·오피스텔·단독다가구 전월세.
     *
     * 아파트와 달리 `aptSeq` 같은 고유키가 없다. 대신 **지번**(sggCd + umdNm + jibun)을
     * 식별자로 쓴다. 같은 지번의 건물은 하나로 묶인다.
     *
     * ⚠️ 단독·다가구(SH)만은 지번조차 없다. 동(umdNm) 단위까지가 한계라 개별 건물을
     * 지도에 찍을 수 없다. 대신 "이 동네 원룸 월세 시세" 로는 쓸 수 있다.
     */
    private val RENT = listOf(
        Endpoint("VILLA", "RTMSDataSvcRHRent", "getRTMSDataSvcRHRent"),
        Endpoint("OFFI", "RTMSDataSvcOffiRent", "getRTMSDataSvcOffiRent"),
        Endpoint("HOUSE", "RTMSDataSvcSHRent", "getRTMSDataSvcSHRent"),
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

    fun run(key: String, months: Int, outDir: File, sggFilter: List<String>?, rental: Boolean = false) {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
        val targets = sggFilter ?: SUDOGWON
        val periods = recentMonths(months)
        val deals = ArrayList<Deal>(200_000)

        var done = 0
        val endpoints = if (rental) RENT else APT
        val total = targets.size * periods.size * endpoints.size
        for (sgg in targets) {
            for (ym in periods) {
                for (ep in endpoints) {
                    val body = fetch(client, key, ep, sgg, ym)
                    if (body != null) deals += parse(body, ep.kind, ym, sgg)
                    done++
                    if (done % 50 == 0) println("      $done/$total 요청, 누적 거래 ${"%,d".format(deals.size)}건")
                }
            }
        }
        println("      요청 $total 회 완료, 거래 ${"%,d".format(deals.size)}건")

        // 집계 방식은 앞으로도 바뀐다. 원자료를 남겨두면 그때마다 1,386번씩 다시
        // 받을 이유가 없다. 40MB 남짓이라 디스크가 아깝지도 않다.
        writeRaw(deals, outDir)
        val complexes = summarize(deals)
        if (rental) writeDongs(deals, outDir)
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

    /**
     * XML 한 태그의 값.
     *
     * 정규식이나 파서를 쓰지 않는 이유는 응답이 단순하고 건수가 많아서다(33만 건).
     * 대신 **엔티티는 반드시 풀어야 한다.** 그러지 않으면 "T&amp;PC서초" 같은 이름이
     * 그대로 굳어서 화면까지 따라간다. 실제로 그렇게 나왔다.
     */
    private fun tag(xml: String, from: Int, end: Int, name: String): String {
        val open = "<$name>"
        val s = xml.indexOf(open, from)
        if (s < 0 || s > end) return ""
        val e = xml.indexOf("</$name>", s)
        if (e < 0 || e > end) return ""
        return unescape(xml.substring(s + open.length, e).trim())
    }

    /** XML 기본 엔티티 5종. 실거래가 응답에 실제로 나오는 건 대부분 `&amp;` 다. */
    private fun unescape(v: String): String =
        if (v.indexOf('&') < 0) v
        else v.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&amp;", "&")

    private fun parse(xml: String, kind: String, ym: String, sgg: String): List<Deal> {
        val out = ArrayList<Deal>()
        var i = xml.indexOf("<item>")
        while (i >= 0) {
            val end = xml.indexOf("</item>", i)
            if (end < 0) break
            val umd = tag(xml, i, end, "umdNm")
            val jibun = tag(xml, i, end, "jibun")
            val aptName = tag(xml, i, end, "aptNm")
            val buildingName = listOf("aptNm", "mhouseNm", "offiNm")
                .firstNotNullOfOrNull { tag(xml, i, end, it).ifEmpty { null } } ?: ""
            // 아파트는 aptSeq, 나머지는 지번이 식별자다. 단독·다가구는 지번조차 없어
            // 동 단위로만 묶인다(개별 건물을 지도에 못 찍는다).
            val seq = when {
                aptName.isNotEmpty() -> tag(xml, i, end, "aptSeq")
                jibun.isNotEmpty() -> "$sgg|$umd|$jibun"
                else -> "$sgg|$umd|DONG"
            }
            val name = buildingName.ifEmpty { "$umd ${tag(xml, i, end, "houseType")}".trim() }
            if (name.isNotEmpty() && seq.isNotEmpty()) {
                val amount = tag(xml, i, end, "dealAmount").ifEmpty { tag(xml, i, end, "deposit") }
                out += Deal(
                    aptSeq = seq,
                    name = name,
                    sggCd = sgg,
                    umdNm = umd,
                    jibun = jibun,
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

            // 월세와 전세를 나눠 담는다. 자취 타겟은 월세가 실제 기준이라 둘 다 필요하다.
            val jeonse = group.filter { it.monthlyManwon == 0 && it.kind != "APT_TRADE" }
            val wolse = group.filter { it.monthlyManwon > 0 }
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
                "kind" to latest.kind,
                "saleMedianManwon" to median(sale.map { it.amountManwon }),
                "jeonseMedianManwon" to median(jeonse.map { it.amountManwon }),
                // 동 집계와 같은 이유로 보증금·월세 중위값을 따로 뽑아 붙이지 않는다.
                // 환산월세의 중위를 보증금 1,000만원 기준으로 되돌린다.
                "wolseDepositManwon" to wolseAtBase(wolse)?.let { BASE_DEPOSIT },
                "wolseMonthlyManwon" to wolseAtBase(wolse),
                "areaMinM2" to group.mapNotNull { it.areaM2.takeIf { a -> a > 0 } }.minOrNull(),
                "areaMaxM2" to group.mapNotNull { it.areaM2.takeIf { a -> a > 0 } }.maxOrNull(),
            )
        }.sortedByDescending { it["deals"] as Int }

    /**
     * 전용면적으로 나눈 방 종류.
     *
     * 실거래가 API 는 방 개수를 주지 않는다. 면적이 유일한 단서다. 경계는 시장에서
     * 통용되는 값에 맞췄다 — 원룸은 대개 10평(33㎡) 이하, 투룸이 15평(50㎡)까지다.
     * 정확한 분류가 아니라 **비교 가능한 구간**을 만드는 게 목적이다.
     */
    /**
     * 저장해둔 원자료로 동 집계만 다시 만든다.
     *
     * 집계 기준을 바꿀 때마다 1,386번씩 다시 받을 이유가 없다. 45분이 30초가 된다.
     * 원자료를 남겨둔 이유가 이것이다.
     */
    fun reaggregate(rawFiles: List<File>, outDir: File) {
        writeDongs(loadRaw(rawFiles), outDir)
    }

    /**
     * 이미 만들어 둔 **건물** 집계의 시세만 원자료로 다시 계산해 덮는다.
     *
     * 주소·건축년도는 원자료에 없어서 새로 만들 수 없다. 대신 그 값들은 바뀌지 않으므로
     * 기존 파일을 살려 두고 가격 필드만 갈아 끼운다. 45분짜리 재수집을 피하는 방법이다.
     */
    fun repriceComplexes(rawFiles: List<File>, complexFile: File) {
        require(complexFile.exists()) { "건물 집계가 없다: ${complexFile.absolutePath}" }
        val mapper = ObjectMapper().registerKotlinModule()
        val bySeq = loadRaw(rawFiles).groupBy { it.aptSeq }

        val root = mapper.readTree(complexFile)
        val arr = root["complexes"] as com.fasterxml.jackson.databind.node.ArrayNode
        var patched = 0
        var missing = 0
        for (node in arr) {
            val obj = node as com.fasterxml.jackson.databind.node.ObjectNode
            val group = bySeq[obj["aptSeq"].asText()]
            if (group == null) { missing++; continue }
            val monthly = wolseAtBase(group.filter { it.monthlyManwon > 0 })
            if (monthly == null) {
                obj.putNull("wolseDepositManwon"); obj.putNull("wolseMonthlyManwon")
            } else {
                obj.put("wolseDepositManwon", BASE_DEPOSIT)
                obj.put("wolseMonthlyManwon", monthly)
            }
            val j = group.filter { it.monthlyManwon == 0 && it.kind != "APT_TRADE" }
                .map { it.amountManwon }.filter { it > 0 }.sorted()
            if (j.isEmpty()) obj.putNull("jeonseMedianManwon")
            else obj.put("jeonseMedianManwon", j[j.size / 2])
            obj.put("deals", group.size)

            // 한 건물에도 원룸과 쓰리룸이 섞여 있다. 원룸을 찾는 사람에게 그 건물의
            // 쓰리룸 값을 보여주면 화면 전체가 거짓이 된다. 동 집계와 같은 구간으로
            // 나눠 담고, 어떤 구간을 볼지는 화면이 정한다.
            val rooms = obj.putObject("rooms")
            for (rt in listOf("ONE", "TWO", "THREE")) {
                val bucket = group.filter { roomType(it.areaM2) == rt }
                if (bucket.isEmpty()) continue
                val o = rooms.putObject(rt)
                o.put("n", bucket.size)
                val m = wolseAtBase(bucket.filter { it.monthlyManwon > 0 })
                if (m == null) o.putNull("m") else o.put("m", m)
                val jj = bucket.filter { it.monthlyManwon == 0 }
                    .map { it.amountManwon }.filter { it > 0 }.sorted()
                if (jj.isEmpty()) o.putNull("j") else o.put("j", jj[jj.size / 2])
            }
            patched++
        }
        mapper.writeValue(complexFile, root)
        println("      건물 $patched 곳 시세 갱신 (원자료에 없는 곳 $missing)")
    }

    /**
     * 원자료 JSONL 을 읽는다. 여러 파일을 합칠 수 있어야 한다 — data.go.kr 은 API 별로
     * 일일 한도가 따로 걸려서, 한 종류만 나중에 다시 받아 붙이는 일이 생긴다.
     *
     * ⚠️ 파일 **안에서는** 중복을 지우면 안 된다. 같은 오피스텔에서 같은 달에 같은
     * 면적·같은 가격 계약이 여러 건 나오는 건 흔하고, 오히려 그게 그 동네의 대표
     * 가격이다. 지우면 중위값이 정확히 가장 흔한 구간에서 깎인다(실제로 46,388건이
     * 사라졌다). 파일 사이에서만 거른다.
     */
    private fun loadRaw(rawFiles: List<File>): List<Deal> {
        val mapper = ObjectMapper().registerKotlinModule()
        val deals = ArrayList<Deal>(400_000)
        val seenBefore = HashSet<String>()
        for (rawFile in rawFiles) {
            require(rawFile.exists()) { "원자료가 없다: ${rawFile.absolutePath}" }
            val thisFile = HashSet<String>()
            var dup = 0
            rawFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val n = mapper.readTree(line)
                    val id = "${n["seq"].asText()}|${n["ym"].asText()}|${n["area"].asDouble()}|" +
                        "${n["amt"].asInt()}|${n["mon"].asInt()}"
                    thisFile += id
                    if (id in seenBefore) { dup++; continue }
                    deals += Deal(
                        aptSeq = n["seq"].asText(), name = n["name"].asText(),
                        sggCd = n["sgg"].asText(), umdNm = n["umd"].asText(),
                        jibun = "", roadNm = "", roadBonbun = "", roadBubun = "", buildYear = "",
                        areaM2 = n["area"].asDouble(),
                        amountManwon = n["amt"].asInt(), monthlyManwon = n["mon"].asInt(),
                        kind = n["kind"].asText(), yearMonth = n["ym"].asText(),
                    )
                }
            }
            seenBefore += thisFile
            println("      ${rawFile.name}: 누적 ${"%,d".format(deals.size)}건 (앞 파일과 중복 $dup 제외)")
        }
        return deals
    }

    /** 거래 원자료를 JSONL 로. 한 줄 한 건이라 스트리밍으로 다시 읽을 수 있다. */
    private fun writeRaw(deals: List<Deal>, outDir: File) {
        val f = File(outDir, "deals-raw.jsonl")
        f.parentFile?.mkdirs()
        val mapper = ObjectMapper().registerKotlinModule()
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            for (d in deals) {
                w.write(
                    mapper.writeValueAsString(
                        mapOf(
                            "seq" to d.aptSeq, "name" to d.name, "sgg" to d.sggCd,
                            "umd" to d.umdNm, "area" to d.areaM2, "amt" to d.amountManwon,
                            "mon" to d.monthlyManwon, "kind" to d.kind, "ym" to d.yearMonth,
                        ),
                    ),
                )
                w.newLine()
            }
        }
        println("      원자료 ${"%,d".format(deals.size)}건 -> ${f.absolutePath} (${f.length() / 1024 / 1024}MB)")
    }

    private fun roomType(areaM2: Double): String? = when {
        areaM2 <= 0.0 -> null
        areaM2 <= 33.0 -> "ONE"
        areaM2 <= 50.0 -> "TWO"
        else -> "THREE"
    }

    /**
     * 동 단위 시세를 따로 낸다.
     *
     * 건물 단위 중위값은 거래가 두세 건뿐이라 흔들린다. "이 빌라 전세 1.2억"은
     * 우연히 그 집이 그랬다는 뜻에 가깝다. 반면 "역삼동 원룸 월세 중위 65만"은
     * 수백 건이 받쳐서 실제 판단에 쓸 수 있다. 자취 타겟이 정말 알고 싶은 건
     * 개별 건물이 아니라 **이 동네가 얼마인가**이므로 이쪽이 주 데이터다.
     */
    /**
     * 환산월세 = 월세 + 보증금을 월세로 바꾼 값.
     *
     * 전월세전환율은 지역·시기마다 다르지만 수도권 기준 연 5.5% 안팎이다. 정확한
     * 금액을 맞추려는 게 아니라 **보증금이 다른 매물을 한 줄로 세우기 위한 것**이라
     * 고정값으로 충분하다.
     */
    private fun convertedRent(d: Deal): Double =
        d.monthlyManwon + d.amountManwon * RATE / 12.0

    /**
     * 환산월세 중위를 보증금 1,000만원 기준 월세로 되돌린 값(만원). 월세 거래가 없으면 null.
     *
     * 건물이든 동이든 같은 계산을 쓴다. 기준이 다르면 확대했을 때 숫자가 튀어서,
     * 같은 곳을 보는데 값이 달라지는 것처럼 읽힌다.
     */
    private fun wolseAtBase(wolse: List<Deal>): Int? {
        if (wolse.isEmpty()) return null
        val conv = wolse.map { convertedRent(it) }.sorted()
        val mid = conv[conv.size / 2]
        return Math.round(mid - BASE_DEPOSIT * RATE / 12.0).toInt().coerceAtLeast(1)
    }

    /** 전월세전환율. 수도권 연 5.5% 안팎. */
    private const val RATE = 0.055

    /** 비교 기준 보증금(만원). 원룸 시장에서 가장 흔한 단위라 여기에 맞춘다. */
    private const val BASE_DEPOSIT = 1000

    private fun writeDongs(deals: List<Deal>, outDir: File) {
        fun median(v: List<Int>): Int? =
            v.filter { it > 0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        val rows = deals.groupBy { "${it.sggCd}|${it.umdNm}" }.map { (key, group) ->
            val rooms = LinkedHashMap<String, Map<String, Any?>>()
            for (rt in listOf("ONE", "TWO", "THREE")) {
                val bucket = group.filter { roomType(it.areaM2) == rt }
                if (bucket.isEmpty()) continue
                val jeonse = bucket.filter { it.monthlyManwon == 0 }
                val wolse = bucket.filter { it.monthlyManwon > 0 }

                // 보증금 중위값과 월세 중위값을 따로 뽑아 짝지으면 **없는 매물**이 만들어진다.
                // 보증금 1.2억에 월 29만 같은 조합은 반전세 몇 건이 한쪽 중위값을 끌어올린
                // 결과지, 그 동네 원룸의 모습이 아니다.
                //
                // 그래서 환산월세(보증금을 월세로 환산해 더한 값)의 중위값을 구하고, 그것을
                // **보증금 1,000만원 기준 월세**로 다시 환산해 내보낸다. 실제 거래 하나를
                // 그대로 쓰지 않는 이유는, 그 한 건의 보증금 구조가 우연이기 때문이다
                // (화곡동 중위 거래가 8,130/40 이면 그 동네가 그렇다는 뜻이 아니다).
                // 같은 보증금으로 맞춰야 동네끼리 비교가 된다.
                val conv = wolse.map { convertedRent(it) }.sorted()
                val midConv = if (conv.isEmpty()) null else conv[conv.size / 2]
                rooms[rt] = mapOf(
                    "deals" to bucket.size,
                    "jeonse" to median(jeonse.map { it.amountManwon }),
                    "jeonseN" to jeonse.size,
                    "wolseDeposit" to midConv?.let { BASE_DEPOSIT },
                    "wolseMonthly" to midConv?.let {
                        Math.round(it - BASE_DEPOSIT * RATE / 12.0).toInt().coerceAtLeast(1)
                    },
                    "wolseConverted" to midConv?.let { Math.round(it).toInt() },
                    "wolseN" to wolse.size,
                )
            }
            mapOf(
                "key" to key,
                "sggCd" to group[0].sggCd,
                "umdNm" to group[0].umdNm,
                "deals" to group.size,
                "rooms" to rooms,
            )
        }.sortedByDescending { it["deals"] as Int }

        val f = File(outDir, "dongs-raw.json")
        f.parentFile?.mkdirs()
        ObjectMapper().registerKotlinModule().writeValue(f, mapOf("dongs" to rows))
        println("      동 ${rows.size}개 -> ${f.absolutePath} (${f.length() / 1024}KB)")
    }
}
