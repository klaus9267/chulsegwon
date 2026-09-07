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
 * 서울 버스. TAGO 가 아니라 서울시 자체 API 를 쓴다.
 *
 * **TAGO 에 서울이 없다.** 도시코드 138개를 받아보면 세종·부산·대구·인천·광주·대전·
 * 울산·제주와 시군뿐이고 서울특별시가 빠져 있다. 서울은 `ws.bus.go.kr` 이 따로 있는데,
 * 다행히 **같은 data.go.kr 키로 붙는다** — 서울열린데이터광장에 따로 가입할 필요가 없다.
 *
 * 그리고 서울 쪽이 TAGO 보다 데이터가 낫다:
 *
 * | | 서울 | TAGO(경기) |
 * |---|---|---|
 * | 방향 | `direction` ✅ | `updowncd` 가 전부 null ❌ |
 * | 구간 거리 | `fullSectDist` ✅ | ❌ |
 * | 구간 속도 | `sectSpd` ✅ (실시간) | ❌ |
 *
 * `sectSpd` 가 GTFS `stop_times` 의 재료다. 차량을 1분마다 추적해 정류장 통과를 관찰하는
 * 대신, **노선당 한 번 호출로 전 구간의 현재 속도**를 얻는다. 요청량이 300배 줄어든다.
 * 다만 30초 만에 104구간 중 12개가 바뀔 만큼 실시간이라, 시간대별로 여러 번 찍어
 * 평균을 내야 한다. 그 반복이 [snapshot] 이다.
 */
object SeoulBus {

    private const val B = "http://ws.bus.go.kr/api/rest"

    /**
     * 노선 목록과 노선별 정류장.
     *
     * `strSrch` 를 빈 값으로 주면 전 노선 1,363개가 한 번에 온다. 노선번호로 검색하는
     * API 인데 빈 검색어가 전체를 뜻한다 — 문서에 없어서 찍어보고 알았다.
     */
    fun collect(key: String, outDir: File) {
        outDir.mkdirs()
        val mapper = ObjectMapper().registerKotlinModule()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

        val routesFile = File(outDir, "routes.jsonl")
        if (!routesFile.exists()) {
            val xml = fetch(client, "$B/busRouteInfo/getBusRouteList?serviceKey=$key&strSrch=")
                ?: error("서울 노선 목록을 못 받았다")
            val rows = items(xml).map {
                mapOf(
                    "id" to tag(it, "busRouteId"),
                    "no" to tag(it, "busRouteNm"),
                    "type" to tag(it, "routeType"),
                    "from" to tag(it, "stStationNm"),
                    "to" to tag(it, "edStationNm"),
                    "corp" to tag(it, "corpNm"),
                    "lengthM" to tag(it, "length").toDoubleOrNull()?.let { m -> (m * 1000).toInt() },
                    // 첫차·막차는 `20260907040000` 형태라 시각만 뽑는다.
                    "first" to tag(it, "firstBusTm").takeLast(6).take(4),
                    "last" to tag(it, "lastBusTm").takeLast(6).take(4),
                    // 배차간격(분). GTFS frequencies.txt 가 된다.
                    "headway" to tag(it, "term").toIntOrNull(),
                )
            }
            appendAll(routesFile, rows, mapper)
            println("      서울 노선 ${rows.size}개")
        }

        val routes = readAll(routesFile, mapper)
        val seqFile = File(outDir, "route-stops.jsonl")
        val have = doneKeys(seqFile, "id")
        var n = 0
        for (r in routes) {
            val id = r["id"] as? String ?: continue
            if (id in have) continue
            val xml = fetch(client, "$B/busRouteInfo/getStaionByRoute?serviceKey=$key&busRouteId=$id")
            val stops = xml?.let { items(it) }?.map { s ->
                mapOf(
                    "stop" to tag(s, "station"),
                    "ars" to tag(s, "arsId"),
                    "name" to tag(s, "stationNm"),
                    "ord" to tag(s, "seq").toIntOrNull(),
                    "lat" to tag(s, "gpsY").toDoubleOrNull(),
                    "lon" to tag(s, "gpsX").toDoubleOrNull(),
                    // 방향이 온다. GTFS 는 trip 을 방향별로 나눠야 하는데 경기는 이게 없다.
                    "dir" to tag(s, "direction"),
                    // 앞 정류장에서 여기까지의 도로 거리(m). 좌표 직선거리보다 정확하다.
                    "distM" to tag(s, "fullSectDist").toDoubleOrNull()?.toInt(),
                )
            } ?: emptyList()
            if (stops.isNotEmpty()) {
                appendAll(seqFile, listOf(mapOf("id" to id, "stops" to stops)), mapper)
            }
            if (++n % 100 == 0) println("      노선별 정류장 $n/${routes.size}")
            Thread.sleep(40)
        }
        println("      완료 · 노선 ${countLines(routesFile)} · 정류장열 ${countLines(seqFile)}")
    }

    /**
     * 구간 속도 스냅샷 하나.
     *
     * 노선마다 `getStaionByRoute` 를 한 번 부르면 그 순간 전 구간의 속도가 온다.
     * 시각을 붙여 append 만 한다 — 나중에 시간대별로 묶어 평균을 낸다.
     *
     * 스케줄러가 시간대마다 이걸 부른다. 한 번에 노선 1,363개면 약 1분이고,
     * 하루 6번이면 8,178 요청이라 일일 한도(10,000) 안에 든다.
     */
    fun snapshot(key: String, outDir: File) {
        outDir.mkdirs()
        val mapper = ObjectMapper().registerKotlinModule()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        val routes = readAll(File(outDir, "routes.jsonl"), mapper)
        require(routes.isNotEmpty()) { "노선 목록이 없다. 먼저 --mode seoulbus 를 돌릴 것" }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(File(outDir, "speed"), "$stamp.jsonl")
        out.parentFile.mkdirs()

        var n = 0
        var rows = 0
        for (r in routes) {
            val id = r["id"] as? String ?: continue
            val xml = fetch(client, "$B/busRouteInfo/getStaionByRoute?serviceKey=$key&busRouteId=$id")
                ?: continue
            val spd = items(xml).mapNotNull { s ->
                val ord = tag(s, "seq").toIntOrNull() ?: return@mapNotNull null
                val v = tag(s, "sectSpd").toIntOrNull() ?: return@mapNotNull null
                val d = tag(s, "fullSectDist").toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                if (v <= 0 || d <= 0) null else intArrayOf(ord, d, v).toList()
            }
            if (spd.isNotEmpty()) {
                appendAll(out, listOf(mapOf("id" to id, "s" to spd)), mapper)
                rows += spd.size
            }
            n++
            Thread.sleep(35)
        }
        println("      스냅샷 $stamp · 노선 $n · 구간 ${"%,d".format(rows)} -> ${out.name}")
    }

    // ── 도구 ────────────────────────────────────────────────────

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

    private fun items(xml: String): List<String> {
        val out = ArrayList<String>()
        var i = xml.indexOf("<itemList>")
        while (i >= 0) {
            val e = xml.indexOf("</itemList>", i)
            if (e < 0) break
            out += xml.substring(i + 10, e)
            i = xml.indexOf("<itemList>", e)
        }
        return out
    }

    private fun tag(block: String, name: String): String {
        val s = block.indexOf("<$name>")
        if (s < 0) return ""
        val e = block.indexOf("</$name>", s)
        if (e < 0) return ""
        return block.substring(s + name.length + 2, e).trim()
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }

    private fun fetch(client: HttpClient, url: String): String? {
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                val b = res.body()
                if (res.statusCode() == 200 && b.contains("<itemList>")) return b
                if (b.contains("LIMITED_NUMBER") || b.contains("초과")) {
                    System.err.println("      ! 일일 한도 초과 — 여기까지 저장됐고 내일 이어서 돌리면 된다")
                    return null
                }
                Thread.sleep(400L * (attempt + 1))
            } catch (e: Exception) {
                System.err.println("      ! ${e.javaClass.simpleName}: ${e.message}")
                Thread.sleep(800L * (attempt + 1))
            }
        }
        return null
    }
}
