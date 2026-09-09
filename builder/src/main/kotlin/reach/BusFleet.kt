package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 노선에 **지금 몇 대가 돌고 있는지** 센다. 시간대별 배차를 재기 위해서다.
 *
 * **왜 필요한가.** 우리 버스 배차는 **노선당 하루 한 값**이다(서울 `term`,
 * 경기 GBIS `주중배차간격`). 그런데 이 제품이 보는 건 출근 시간대다. 출근 피크에
 * 간선버스가 5분으로 다녀도 우리는 하루 평균을 쓴다. 지하철만 시간대별 6구간을
 * 갖고 있다([Headways.WEEKDAY]).
 *
 * 어떤 공개 API 도 "시간대별 배차"를 안 준다. 대신 **차량 위치**는 준다
 * (`getBusPosByRtid`, 노선당 호출 1회). 그리고
 *
 * ```
 * 배차 = 왕복 운행시간 ÷ 운행 대수
 * ```
 *
 * 이므로, 같은 노선을 시각만 바꿔 세면 **왕복 운행시간이 약분된다**:
 *
 * ```
 * 배차(T) = 공시배차 × 중앙대수 ÷ 그 시각 대수
 * ```
 *
 * 검산: 143번(62.2km)이 15:24 에 41대였다. 왕복 약 4시간이면 240÷41 = 5.9분이고
 * 공시 `term` 이 6 이다. 맞는다.
 *
 * **표본만 센다.** 전 노선 × 시간대는 한도를 넘는다([SeoulBus.snapshot] 의 주석 —
 * 관측상 하루 약 2,000회고 속도 수집이 이미 1,900 을 쓴다). 노선 유형별로
 * 대표 표본만 세서 **유형별 시간대 배율**을 배우고, 그걸 전 노선에 적용한다.
 * 지하철이 [Headways.LINE_MULTIPLIER] 로 하는 것과 같은 구조다.
 */
object BusFleet {

    private const val B = "http://ws.bus.go.kr/api/rest"

    /** 경기·인천은 TAGO 다. 서울만 `ws.bus.go.kr` 이 따로 있다. */
    private const val T = "https://apis.data.go.kr/1613000/BusLcInfoInqireService"

    /**
     * 경기·인천 노선의 현재 운행 대수.
     *
     * TAGO `getRouteAcctoBusLcList` 는 `cityCode` 와 `routeId` 를 함께 받는다.
     * 반환 항목이 곧 운행중 차량이라 서울처럼 `isrunyn` 을 거를 필요가 없다.
     *
     * ⚠️ GBIS 마을버스 892개는 TAGO 에 없어 위치도 없다. 그 노선들은 같은 유형의
     * 배율을 쓴다 — 애초에 배율은 유형 단위라 문제가 안 된다.
     */
    fun snapshotGyeonggi(key: String, busDir: File, outDir: File, limit: Int = 60) {
        val mapper = ObjectMapper().registerKotlinModule()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        val routes = readAll(File(busDir, "routes.jsonl"), mapper)
        require(routes.isNotEmpty()) { "경기 노선 목록이 없다. 먼저 --mode bus 를 돌릴 것" }

        val fleetDir = File(outDir, "fleet-gg")
        fleetDir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(fleetDir, "$stamp.jsonl")

        val byType = routes.groupBy { (it["type"] as? String) ?: "?" }
        val cursorFile = File(fleetDir, "cursor.txt")
        val cursor = cursorFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val perType = (limit / byType.size).coerceAtLeast(1)
        val take = byType.values.flatMap { list ->
            (0 until minOf(perType, list.size)).map { list[(cursor + it).mod(list.size)] }
        }
        println("      경기 이번 몫: ${take.size}개 (유형 ${byType.size}개 × ${perType}) / 전체 ${routes.size}")

        var ok = 0
        var vehicles = 0
        val lines = ArrayList<Map<String, Any?>>(take.size)
        for (r in take) {
            val id = r["id"] as? String ?: continue
            val city = r["city"] as? String ?: continue
            val body = fetch(
                client,
                "$T/getRouteAcctoBusLcList?serviceKey=$key&cityCode=$city&routeId=$id" +
                    "&numOfRows=200&pageNo=1&_type=json",
            ) ?: continue
            // 항목 수만 필요하다. 파싱을 얕게 해서 200대짜리 노선도 싸게 센다.
            val n = Regex("\"vehicleno\"").findAll(body).count()
            if (n > 0) {
                lines += mapOf("id" to id, "no" to r["no"], "type" to r["type"], "n" to n)
                vehicles += n
                ok++
            }
        }
        out.bufferedWriter().use { w ->
            for (l in lines) { w.write(mapper.writeValueAsString(l)); w.write("\n") }
        }
        cursorFile.writeText(((cursor + perType).mod(1 shl 20)).toString())
        println("      경기 노선 ${ok}개 · 운행중 차량 ${"%,d".format(vehicles)}대 -> ${out.name}")
    }

    /**
     * 서울 노선의 현재 운행 대수.
     *
     * 커서를 돌려 매번 다른 몫을 맡는 것은 속도 수집과 같은 방식인데, **여기서는
     * 유형별로 고르게 뽑는다.** 시간대 배율을 유형별로 배울 것이라, 한 시간대에
     * 한 유형만 잔뜩 찍히면 그 시간대의 다른 유형을 영영 못 본다.
     */
    fun snapshot(key: String, outDir: File, limit: Int = 60) {
        val mapper = ObjectMapper().registerKotlinModule()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        val routes = readAll(File(outDir, "routes.jsonl"), mapper)
        require(routes.isNotEmpty()) { "노선 목록이 없다. 먼저 --mode seoulbus 를 돌릴 것" }

        val fleetDir = File(outDir, "fleet")
        fleetDir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(fleetDir, "$stamp.jsonl")

        // 유형별로 나눠 각 유형에서 커서만큼 돌아가며 뽑는다.
        val byType = routes.groupBy { (it["type"] as? String) ?: "?" }
        val cursorFile = File(fleetDir, "cursor.txt")
        val cursor = cursorFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val perType = (limit / byType.size).coerceAtLeast(1)
        val take = byType.values.flatMap { list ->
            (0 until minOf(perType, list.size)).map { list[(cursor + it).mod(list.size)] }
        }
        println("      이번 몫: ${take.size}개 (유형 ${byType.size}개 × ${perType}) / 전체 ${routes.size}")

        var ok = 0
        var vehicles = 0
        val lines = ArrayList<Map<String, Any?>>(take.size)
        for (r in take) {
            val id = r["id"] as? String ?: continue
            val xml = fetch(client, "$B/buspos/getBusPosByRtid?serviceKey=$key&busRouteId=$id")
                ?: continue
            // isrunyn=1 만 센다. 차고지에 선 차를 세면 배차가 짧게 나온다.
            val running = ITEM.findAll(xml).count { m ->
                tag(m.groupValues[1], "isrunyn") == "1"
            }
            if (running > 0) {
                lines += mapOf("id" to id, "no" to r["no"], "type" to r["type"], "n" to running)
                vehicles += running
                ok++
            }
        }
        out.bufferedWriter().use { w ->
            for (l in lines) { w.write(mapper.writeValueAsString(l)); w.write("\n") }
        }
        cursorFile.writeText(((cursor + perType).mod(1 shl 20)).toString())
        println("      노선 ${ok}개 · 운행중 차량 ${"%,d".format(vehicles)}대 -> ${out.name}")
    }

    private val ITEM = Regex("<itemList>(.*?)</itemList>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(body: String, name: String): String =
        Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)?.trim() ?: ""

    private fun fetch(client: HttpClient, url: String): String? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(25)).GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (res.statusCode() == 200) res.body() else null
    } catch (e: Exception) {
        null
    }

    private fun readAll(f: File, mapper: ObjectMapper): List<Map<String, Any?>> {
        if (!f.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return f.readLines().filter { it.isNotBlank() }
            .map { mapper.readValue(it, Map::class.java) as Map<String, Any?> }
    }
}
