package reach

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
 * 카테고리 코드가 없는 시설의 목록을 한 번 만들어 둔다.
 *
 * 왜 이렇게까지 하냐면, 반경 검색이 이 종류에는 통하지 않기 때문이다.
 * `백화점` 을 키워드로 반경 검색하면 강남역 3km 안에서 879건이 나온다. 백화점이
 * 879개일 리 없고, **백화점 안에 입점한 매장들**이다("금강 롯데백화점노원점",
 * 분류는 `패션 > 구두,신발`). 거리순으로 정렬해도 상위 15개가 전부 입점 매장이라
 * 정작 백화점 본체에 닿지 못한다.
 *
 * 그래서 지역을 격자로 훑어 **분류가 실제로 백화점인 것**만 모아 목록을 만들고,
 * 거리는 그 목록으로 직접 계산한다. 한 번 모아두면 이후에는 API 가 필요 없다.
 */
object Places {

    /** 수도권을 덮는 범위. 검색은 사각형(rect)으로 한다. */
    private const val WEST = 126.45
    private const val EAST = 127.60
    private const val SOUTH = 36.95
    private const val NORTH = 37.95

    /** 격자 한 칸의 크기(도). 약 4.4km × 5.5km. 한 칸에 45건을 넘기지 않을 크기. */
    private const val STEP = 0.05

    fun run(key: String, keyword: String, outFile: File) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val mapper = ObjectMapper().registerKotlinModule()
        val found = LinkedHashMap<String, Map<String, Any?>>()

        var tiles = 0
        var lon = WEST
        while (lon < EAST) {
            var lat = SOUTH
            while (lat < NORTH) {
                tiles++
                // 한 칸에서 최대 3쪽(45건). 카카오 페이징 상한이 45라 그 이상은 못 본다.
                for (page in 1..3) {
                    val docs = search(client, key, keyword, lon, lat, lon + STEP, lat + STEP, page)
                        ?: break
                    if (docs.isEmpty()) break
                    for (d in docs) {
                        val cat = d["category_name"]?.asText().orEmpty()
                        // 이름이 아니라 **분류**로 거른다. 입점 매장은 분류가 패션·식품이다.
                        if (!cat.trimEnd().endsWith(keyword)) continue
                        val id = d["id"]?.asText() ?: continue
                        found[id] = mapOf(
                            "name" to d["place_name"]?.asText(),
                            "lon" to d["x"]?.asText()?.toDoubleOrNull(),
                            "lat" to d["y"]?.asText()?.toDoubleOrNull(),
                        )
                    }
                    if (docs.size < 15) break
                }
                if (tiles % 50 == 0) println("      격자 $tiles 칸, 찾은 곳 ${found.size}")
                lat += STEP
            }
            lon += STEP
        }

        val list = found.values.filter { it["lon"] != null && it["lat"] != null }
        outFile.parentFile?.mkdirs()
        mapper.writeValue(outFile, mapOf("keyword" to keyword, "places" to list))
        println("      격자 $tiles 칸 훑음, $keyword ${list.size}곳 -> ${outFile.absolutePath}")
        for (p in list.take(8)) println("        · ${p["name"]}")
    }

    private fun search(
        client: HttpClient,
        key: String,
        keyword: String,
        w: Double,
        s: Double,
        e: Double,
        n: Double,
        page: Int,
    ): List<com.fasterxml.jackson.databind.JsonNode>? {
        val url = "https://dapi.kakao.com/v2/local/search/keyword.json" +
            "?query=" + URLEncoder.encode(keyword, Charsets.UTF_8) +
            "&rect=$w,$s,$e,$n&size=15&page=$page"
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "KakaoAK $key")
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                Thread.sleep(30)
                if (res.statusCode() == 200) {
                    return ObjectMapper().readTree(res.body())["documents"]?.toList() ?: emptyList()
                }
                // 페이지 상한을 넘기면 400 이 온다. 오류가 아니라 "여기까지"라는 뜻이다.
                if (res.statusCode() == 400) return null
                if (res.statusCode() == 429) Thread.sleep(1000L * (attempt + 1)) else return null
            } catch (e: Exception) {
                Thread.sleep(300L * (attempt + 1))
            }
        }
        return null
    }
}
