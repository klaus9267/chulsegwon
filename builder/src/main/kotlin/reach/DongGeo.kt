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
 * 동에 좌표를 붙인다.
 *
 * 건물 단위 시세는 거래가 두세 건뿐이라 흔들린다. "이 빌라 전세 1.2억"은 우연히 그
 * 집이 그랬다는 뜻에 가깝다. 반면 "역삼동 원룸 월세 중위 50만"은 1,376건이 받친다.
 * 자취 타겟이 정말 알고 싶은 건 개별 건물이 아니라 **이 동네가 얼마인가**라서
 * 동이 주 데이터고, 건물은 확대했을 때 보조로 쓴다.
 *
 * 동은 1,768개뿐이라 지오코딩이 2분이면 끝난다. 건물 6만 개는 두 시간이 걸린다.
 */
object DongGeo {

    private val SIDO = mapOf("11" to "서울", "28" to "인천", "41" to "경기")

    /** 시군구 코드 -> 이름. 카카오에 "서울 역삼동"만 던지면 동명이동에서 엉뚱한 데로 간다. */
    private fun sggName(cd: String, mapper: ObjectMapper, codeFile: File): Map<String, String> =
        if (codeFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(codeFile, Map::class.java) as Map<String, String>
        } else emptyMap()

    fun run(key: String, inFile: File, outFile: File, cacheFile: File, sggNameFile: File) {
        val mapper = ObjectMapper().registerKotlinModule()
        require(inFile.exists()) { "동 집계가 없다: ${inFile.absolutePath}" }

        val dongs = mapper.readTree(inFile)["dongs"]
        val names = sggName("", mapper, sggNameFile)

        val cache: MutableMap<String, Map<String, Any?>> =
            if (cacheFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                (mapper.readValue(cacheFile, Map::class.java) as Map<String, Map<String, Any?>>).toMutableMap()
            } else mutableMapOf()

        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        var hit = 0; var miss = 0; var queried = 0

        for (d in dongs) {
            val k = d["key"].asText()
            if (cache.containsKey(k)) continue
            val sgg = d["sggCd"].asText()
            val sido = SIDO[sgg.take(2)] ?: ""
            val gu = names[sgg] ?: ""
            val umd = d["umdNm"].asText()

            // 구 이름까지 붙여야 동명이동(중앙동·신흥동 등)이 갈린다.
            val found = sequenceOf("$sido $gu $umd".trim(), "$sido $umd".trim())
                .distinct()
                .firstNotNullOfOrNull { q -> search(client, key, q) }

            queried++
            if (found != null) {
                hit++
                cache[k] = mapOf("lon" to found.first, "lat" to found.second, "matched" to found.third)
            } else {
                miss++
                cache[k] = mapOf("lon" to null, "lat" to null, "matched" to null)
            }
            if (queried % 200 == 0) {
                println("      조회 $queried (성공 $hit / 실패 $miss)")
                cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
            }
            Thread.sleep(35)
        }
        cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
        println("      조회 $queried (성공 $hit / 실패 $miss)")

        val out = ArrayList<Map<String, Any?>>()
        for (d in dongs) {
            val c = cache[d["key"].asText()] ?: continue
            val lon = c["lon"] as? Double ?: continue
            val lat = c["lat"] as? Double ?: continue
            val rooms = LinkedHashMap<String, Any?>()
            d["rooms"].fields().forEach { (rt, v) ->
                rooms[rt] = mapOf(
                    "n" to v["deals"].asInt(),
                    "jeonse" to v["jeonse"]?.takeIf { !it.isNull }?.asInt(),
                    "deposit" to v["wolseDeposit"]?.takeIf { !it.isNull }?.asInt(),
                    "monthly" to v["wolseMonthly"]?.takeIf { !it.isNull }?.asInt(),
                )
            }
            out += mapOf(
                "name" to d["umdNm"].asText(),
                "gu" to (names[d["sggCd"].asText()] ?: ""),
                "lon" to lon, "lat" to lat,
                "deals" to d["deals"].asInt(),
                "rooms" to rooms,
            )
        }
        outFile.parentFile?.mkdirs()
        mapper.writeValue(outFile, mapOf("dongs" to out))
        println("      좌표 붙은 동 ${out.size}개 -> ${outFile.absolutePath} (${outFile.length() / 1024}KB)")
    }

    private fun search(client: HttpClient, key: String, query: String): Triple<Double, Double, String>? {
        val url = "https://dapi.kakao.com/v2/local/search/address.json?size=1&query=" +
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
                    val first = ObjectMapper().readTree(res.body())["documents"]?.firstOrNull()
                        ?: return null
                    val lon = first["x"]?.asText()?.toDoubleOrNull() ?: return null
                    val lat = first["y"]?.asText()?.toDoubleOrNull() ?: return null
                    return Triple(lon, lat, first["address_name"]?.asText() ?: "")
                }
                if (res.statusCode() == 429) Thread.sleep(1000L * (attempt + 1)) else return null
            } catch (e: Exception) {
                Thread.sleep(300L * (attempt + 1))
            }
        }
        return null
    }
}
