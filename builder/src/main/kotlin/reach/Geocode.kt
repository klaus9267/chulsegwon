package reach

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 단지에 좌표를 채운다.
 *
 * 실거래가 API 는 좌표를 주지 않는다. `법정동코드 + 지번 + 도로명 + 단지명` 뿐이라
 * 지도에 찍으려면 이 단계가 필요하다. 카카오 Local 을 쓰는 이유는 이미 갖고 있는
 * 키로 되고(추가 발급 불필요) 하루 10만 건이라 여유롭기 때문이다.
 *
 * **캐시가 핵심이다.** 단지 좌표는 변하지 않으므로 `aptSeq` 로 캐시해 두면 다음
 * 배치부터는 신규 단지만 조회한다. 수도권 2만 단지를 매달 다시 조회할 이유가 없다.
 */
object Geocode {

    private const val BASE = "https://dapi.kakao.com/v2/local/search"

    /** 시군구 코드 앞 2자리 -> 시도 이름. 주소 앞에 붙여야 전국에서 모호하지 않다. */
    private val SIDO = mapOf("11" to "서울", "28" to "인천", "41" to "경기")

    private class Hit(val lon: Double, val lat: Double, val matched: String, val via: String)

    fun run(key: String, inFile: File, outFile: File, cacheFile: File, limit: Int) {
        val mapper = ObjectMapper().registerKotlinModule()
        require(inFile.exists()) { "수집 결과가 없다: ${inFile.absolutePath}. 먼저 --mode deals 를 돌릴 것" }

        val root = mapper.readTree(inFile)
        val complexes = root["complexes"] as com.fasterxml.jackson.databind.node.ArrayNode

        val cache: MutableMap<String, Map<String, Any?>> =
            if (cacheFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                (mapper.readValue(cacheFile, Map::class.java) as Map<String, Map<String, Any?>>).toMutableMap()
            } else mutableMapOf()
        println("      캐시 ${cache.size}건 로드")

        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        var hit = 0
        var miss = 0
        var fromCache = 0
        var queried = 0

        for (node in complexes) {
            val seq = node["aptSeq"].asText()
            if (cache.containsKey(seq)) { fromCache++; continue }
            if (limit > 0 && queried >= limit) break

            val sido = SIDO[node["sggCd"].asText().take(2)] ?: ""
            val name = node["name"].asText()
            val road = node["roadAddress"]?.asText().orEmpty()
            val jibun = node["jibunAddress"]?.asText().orEmpty()

            // 도로명이 가장 정확하다. 없으면 지번, 그것도 안 되면 단지명으로 찾는다.
            val found = sequenceOf(
                road.takeIf { it.isNotBlank() }?.let { Triple("address", "$sido $it", "road") },
                jibun.takeIf { it.isNotBlank() }?.let { Triple("address", "$sido $it", "jibun") },
                Triple("keyword", "$sido $name", "name"),
            ).filterNotNull().firstNotNullOfOrNull { (ep, q, via) ->
                search(client, key, ep, q)?.let { Hit(it.first, it.second, it.third, via) }
            }

            queried++
            if (found != null) {
                hit++
                cache[seq] = mapOf(
                    "lon" to found.lon, "lat" to found.lat,
                    "matched" to found.matched, "via" to found.via,
                )
            } else {
                miss++
                cache[seq] = mapOf("lon" to null, "lat" to null, "matched" to null, "via" to "fail")
            }
            if (queried % 100 == 0) {
                println("      조회 $queried (성공 $hit / 실패 $miss)")
                cacheFile.parentFile?.mkdirs()
                mapper.writeValue(cacheFile, cache)   // 중간 저장. 끊겨도 처음부터 다시 안 한다.
            }
            Thread.sleep(35)   // 초당 30건 정도. 쿼터는 넉넉하지만 예의는 지킨다.
        }

        cacheFile.parentFile?.mkdirs()
        mapper.writeValue(cacheFile, cache)
        println("      조회 $queried (성공 $hit / 실패 $miss), 캐시 재사용 $fromCache")

        // 좌표가 붙은 단지만 내보낸다. 좌표 없는 건 지도에 못 쓴다.
        val out = ArrayList<Map<String, Any?>>()
        for (node in complexes) {
            val c = cache[node["aptSeq"].asText()] ?: continue
            val lon = c["lon"] as? Double ?: continue
            val lat = c["lat"] as? Double ?: continue
            out += mapOf(
                "name" to node["name"].asText(),
                "lon" to lon,
                "lat" to lat,
                "sale" to node["saleMedianManwon"]?.takeIf { !it.isNull }?.asInt(),
                "jeonse" to node["jeonseMedianManwon"]?.takeIf { !it.isNull }?.asInt(),
                "deals" to node["deals"].asInt(),
                "buildYear" to node["buildYear"].asText(),
            )
        }
        outFile.parentFile?.mkdirs()
        mapper.writeValue(outFile, mapOf("complexes" to out))
        println("      좌표 붙은 단지 ${out.size}개 -> ${outFile.absolutePath} (${outFile.length() / 1024}KB)")
    }

    /** 성공하면 (lon, lat, 매칭된 주소). */
    private fun search(
        client: HttpClient,
        key: String,
        endpoint: String,
        query: String,
    ): Triple<Double, Double, String>? {
        val url = "$BASE/$endpoint.json?size=1&query=" +
            URLEncoder.encode(query, Charsets.UTF_8)
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "KakaoAK $key")
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                if (res.statusCode() == 200) {
                    val docs: JsonNode = ObjectMapper().readTree(res.body())["documents"]
                    val first = docs?.firstOrNull() ?: return null
                    val lon = first["x"]?.asText()?.toDoubleOrNull() ?: return null
                    val lat = first["y"]?.asText()?.toDoubleOrNull() ?: return null
                    val label = first["address_name"]?.asText()
                        ?: first["road_address_name"]?.asText()
                        ?: first["place_name"]?.asText() ?: ""
                    return Triple(lon, lat, label)
                }
                if (res.statusCode() == 429) Thread.sleep(1000L * (attempt + 1)) else return null
            } catch (e: Exception) {
                Thread.sleep(300L * (attempt + 1))
            }
        }
        return null
    }
}
