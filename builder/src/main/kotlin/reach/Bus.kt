package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * TAGO 에서 버스 노선·정류장·배차간격을 모은다.
 *
 * GTFS 로 내보내기 위한 재료다. 여섯 파일 중 다섯(`stops` `routes` `trips`
 * `calendar` `frequencies`)이 여기서 나오고, 남는 `stop_times` 만 [BusTrace] 가
 * 차량 위치를 관찰해 채운다.
 *
 * ⚠️ **TAGO 에는 서울이 없다.** 도시코드 138개를 받아보면 세종·부산·대구·인천·
 * 광주·대전·울산·제주와 시군 단위뿐이고 서울특별시가 빠져 있다. 서울은 서울시가
 * 자체 운영하므로 따로 받아야 한다(`data.go.kr` 15000332 또는 서울열린데이터광장).
 *
 * **재개 가능해야 한다.** 노선 상세와 경유정류소는 노선 수만큼 호출이라 경기만 해도
 * 5,000번이 넘는다. 실거래가 수집이 도중에 죽어 45분을 날린 적이 있어서(HANDOFF §7-2)
 * 노선 단위로 JSONL 에 append 하고, 다시 돌리면 이미 받은 노선은 건너뛴다.
 */
object Bus {

    private const val B = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService"
    private const val S = "https://apis.data.go.kr/1613000/BusSttnInfoInqireService"
    private const val PAGE = 1000

    /**
     * 경기도 시군 전체 31개.
     *
     * TAGO 도시코드는 법정동 코드가 아니라 **자체 체계**다. 연속이라고 가정해
     * 31010~31300 을 넣었더니 31280·31290·31300 은 존재하지 않고 여주(31320)·
     * 가평(31370)·양평(31380)이 빠졌다. `getCtyCodeList` 로 받은 실제 목록이다.
     */
    val GYEONGGI = listOf(
        "31010", "31020", "31030", "31040", "31050", "31060", "31070", "31080",
        "31090", "31100", "31110", "31120", "31130", "31140", "31150", "31160",
        "31170", "31180", "31190", "31200", "31210", "31220", "31230", "31240",
        "31250", "31260", "31270", "31320", "31350", "31370", "31380",
    )

    /**
     * 인천. 서울은 TAGO 에 없어서 여기 못 넣는다.
     *
     * ⚠️ 이 상수는 오래 **선언만 되고 안 쓰였다.** 그래서 인천 버스가 통째로 빠져
     * 있었고, 강화군 46개·옹진군 9개 동네가 "800m 안에 정류장이 없음"으로
     * 도달 불가가 됐다(동네 1,768개 중 63개가 인천이다). 서울 API 가 `1300인천`
     * 같은 노선 38개를 주긴 하는데 그건 **서울을 지나는 것만**이라 인천 안쪽이 비었다.
     */
    const val INCHEON = "23"

    /** 우리가 다루는 범위. 서울은 별도 API 라 [SeoulBus] 가 맡는다. */
    val CAPITAL_AREA = GYEONGGI + INCHEON

    fun run(key: String, cities: List<String>, outDir: File) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        outDir.mkdirs()
        val mapper = ObjectMapper().registerKotlinModule()

        // ── 1) 정류장 ────────────────────────────────────────────
        val stopsFile = File(outDir, "stops.jsonl")
        val haveStops = doneKeys(stopsFile, "city")
        for (city in cities) {
            if (city in haveStops) continue
            var page = 1
            var got = 0
            var complete = true
            val rows = ArrayList<Map<String, Any?>>()
            while (true) {
                val xml = fetch(client, "$S/getSttnNoList?serviceKey=$key&cityCode=$city" +
                    "&numOfRows=$PAGE&pageNo=$page")
                // ⚠️ 실패와 "더 없음"을 구분해야 한다. 둘 다 break 하면 부분 수집이
                // 완료로 기록되고, 재개해도 이미 받은 걸로 쳐서 영영 안 채워진다.
                // 실제로 화성이 정확히 1,000개에서 끊겼다.
                if (xml == null) { complete = false; break }
                val items = items(xml)
                for (it in items) {
                    rows += mapOf(
                        "city" to city,
                        "id" to tag(it, "nodeid"),
                        "name" to tag(it, "nodenm"),
                        "no" to tag(it, "nodeno"),
                        "lat" to tag(it, "gpslati").toDoubleOrNull(),
                        "lon" to tag(it, "gpslong").toDoubleOrNull(),
                    )
                }
                got += items.size
                if (items.size < PAGE) break
                page++
                Thread.sleep(60)
            }
            if (!complete) {
                System.err.println("      ! 정류장 $city 페이징 중단 — 기록하지 않고 다음 실행에서 다시 받는다")
                continue
            }
            appendAll(stopsFile, rows, mapper)
            println("      정류장 $city : ${"%,d".format(got)}")
        }

        // ── 2) 노선 목록 ─────────────────────────────────────────
        val routesFile = File(outDir, "routes.jsonl")
        val haveRoutes = doneKeys(routesFile, "city")
        for (city in cities) {
            if (city in haveRoutes) continue
            var page = 1
            var complete = true
            val rows = ArrayList<Map<String, Any?>>()
            while (true) {
                val xml = fetch(client, "$B/getRouteNoList?serviceKey=$key&cityCode=$city" +
                    "&numOfRows=$PAGE&pageNo=$page")
                if (xml == null) { complete = false; break }
                val items = items(xml)
                for (it in items) {
                    rows += mapOf(
                        "city" to city,
                        "id" to tag(it, "routeid"),
                        "no" to tag(it, "routeno"),
                        "type" to tag(it, "routetp"),
                        "from" to tag(it, "startnodenm"),
                        "to" to tag(it, "endnodenm"),
                    )
                }
                if (items.size < PAGE) break
                page++
                Thread.sleep(60)
            }
            if (!complete) {
                System.err.println("      ! 노선 $city 페이징 중단 — 기록하지 않는다")
                continue
            }
            appendAll(routesFile, rows, mapper)
            println("      노선 $city : ${rows.size}")
        }

        val routes = readAll(routesFile, mapper)
        println("      노선 합계 ${"%,d".format(routes.size)}")

        // ── 3) 노선 상세 — 배차간격은 목록에 없고 여기에만 있다 ──
        val detailFile = File(outDir, "route-detail.jsonl")
        val haveDetail = doneKeys(detailFile, "id")
        var n = 0
        for (r in routes) {
            val id = r["id"] as? String ?: continue
            if (id in haveDetail) continue
            val city = r["city"] as String
            val xml = fetch(client, "$B/getRouteInfoIem?serviceKey=$key&cityCode=$city&routeId=$id")
            val it = xml?.let { items(it).firstOrNull() }
            if (it != null) {
                appendAll(detailFile, listOf(mapOf(
                    "id" to id,
                    "city" to city,
                    "no" to tag(it, "routeno"),
                    "type" to tag(it, "routetp"),
                    "first" to tag(it, "startvehicletime"),
                    "last" to tag(it, "endvehicletime"),
                    // 평일/토/일 배차가 따로 온다. GTFS calendar 를 요일별로 나눌 수 있다.
                    "headwayWeekday" to tag(it, "intervaltime").toIntOrNull(),
                    "headwaySat" to tag(it, "intervalsattime").toIntOrNull(),
                    "headwaySun" to tag(it, "intervalsuntime").toIntOrNull(),
                )), mapper)
            }
            if (++n % 200 == 0) println("      노선 상세 $n/${routes.size}")
            Thread.sleep(45)
        }

        // ── 4) 노선별 경유 정류장 순서 — GTFS trips/stop_times 의 뼈대 ──
        val seqFile = File(outDir, "route-stops.jsonl")
        val haveSeq = doneKeys(seqFile, "id")
        n = 0
        for (r in routes) {
            val id = r["id"] as? String ?: continue
            if (id in haveSeq) continue
            val city = r["city"] as String
            val xml = fetch(client, "$B/getRouteAcctoThrghSttnList?serviceKey=$key" +
                "&cityCode=$city&routeId=$id&numOfRows=$PAGE&pageNo=1")
            // ⚠️ 좌표와 이름을 여기서 같이 챙긴다. 정류장 목록(getSttnNoList)은 시군 단위라
            // **경기 광역버스가 지나는 서울 정류장이 어느 목록에도 없다**. 실제로 참조된
            // 정류장의 7.7%(2,593개)가 그렇게 비었는데, 그 좌표가 이 응답에 이미 들어 있었다.
            // 목록을 더 받을 게 아니라 오는 걸 버리지 않으면 되는 문제였다.
            val seq = xml?.let { items(it) }?.map { s ->
                mapOf(
                    "stop" to tag(s, "nodeid"),
                    "name" to tag(s, "nodenm"),
                    "ord" to tag(s, "nodeord").toIntOrNull(),
                    "lat" to tag(s, "gpslati").toDoubleOrNull(),
                    "lon" to tag(s, "gpslong").toDoubleOrNull(),
                    // 상·하행이 한 노선에 섞여 온다. GTFS 에서는 trip 을 방향별로 나눠야 한다.
                    "up" to tag(s, "updowncd").toIntOrNull(),
                )
            } ?: emptyList()
            if (seq.isNotEmpty()) {
                appendAll(seqFile, listOf(mapOf("id" to id, "city" to city, "stops" to seq)), mapper)
            }
            if (++n % 200 == 0) println("      경유정류장 $n/${routes.size}")
            Thread.sleep(45)
        }

        println("      완료 -> ${outDir.absolutePath}")
        for (f in listOf(stopsFile, routesFile, detailFile, seqFile)) {
            println("        ${f.name.padEnd(20)} ${"%,d".format(countLines(f))}줄")
        }
    }

    // ── 도구 ────────────────────────────────────────────────────

    /** 이미 받은 키. 재개할 때 건너뛰려고 읽는다. */
    private fun doneKeys(f: File, field: String): Set<String> {
        if (!f.exists()) return emptySet()
        val out = HashSet<String>()
        val pat = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        f.forEachLine { line -> pat.find(line)?.let { out += it.groupValues[1] } }
        return out
    }

    private fun countLines(f: File) = if (f.exists()) f.readLines().size else 0

    private fun appendAll(f: File, rows: List<Map<String, Any?>>, mapper: ObjectMapper) {
        if (rows.isEmpty()) return
        f.parentFile?.mkdirs()
        f.appendText(rows.joinToString("\n", postfix = "\n") { mapper.writeValueAsString(it) })
    }

    private fun readAll(f: File, mapper: ObjectMapper): List<Map<String, Any?>> {
        if (!f.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return f.readLines().filter { it.isNotBlank() }
            .map { mapper.readValue(it, Map::class.java) as Map<String, Any?> }
    }

    /** `<item>` 블록들. XML 파서를 쓰지 않는 이유는 [Deals] 와 같다 — 응답이 단순하고 건수가 많다. */
    private fun items(xml: String): List<String> {
        val out = ArrayList<String>()
        var i = xml.indexOf("<item>")
        while (i >= 0) {
            val e = xml.indexOf("</item>", i)
            if (e < 0) break
            out += xml.substring(i + 6, e)
            i = xml.indexOf("<item>", e)
        }
        return out
    }

    private fun tag(block: String, name: String): String {
        val s = block.indexOf("<$name>")
        if (s < 0) return ""
        val e = block.indexOf("</$name>", s)
        if (e < 0) return ""
        return unescape(block.substring(s + name.length + 2, e).trim())
    }

    /** [Deals] 에서 배운 것 — 태그 사이를 잘라 쓰면 엔티티를 직접 풀어야 한다. */
    private fun unescape(v: String): String =
        if (v.indexOf('&') < 0) v
        else v.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&amp;", "&")

    private fun fetch(client: HttpClient, url: String): String? {
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                val body = res.body()
                if (res.statusCode() == 200 && !body.contains("<errMsg>")) return body
                if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) {
                    System.err.println("      ! 일일 한도 초과. 여기까지 저장됐고 내일 이어서 돌리면 된다")
                    return null
                }
                Thread.sleep(500L * (attempt + 1))
            } catch (e: Exception) {
                System.err.println("      ! ${e.javaClass.simpleName}: ${e.message}")
                Thread.sleep(600L * (attempt + 1))
            }
        }
        return null
    }
}
