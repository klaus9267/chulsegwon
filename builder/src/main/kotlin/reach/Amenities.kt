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
 * 동네마다 편의시설이 몇 개나 있는지 센다.
 *
 * 통근 시간만으로 좁히면 후보가 200개 동네다. 그다음 질문은 "그중 살 만한 데는
 * 어디냐"인데, 자취에서 그건 대부분 **생활 인프라**다. 편의점이 걸어서 있는지,
 * 아플 때 갈 병원이 있는지, 장은 어디서 보는지.
 *
 * 다른 서비스는 이걸 지도 위 아이콘으로 보여준다. 아이콘은 켜고 끄는 것이지
 * 거르는 게 아니라서, 조건에 안 맞는 동네를 지워주지는 못한다. 우리는 **필터**로
 * 쓴다. 그러려면 눈으로 세는 게 아니라 숫자가 있어야 한다.
 *
 * 반경 800m 는 걸어서 10분 남짓이다. 자취에서 "가깝다"의 실질적 경계.
 */
object Amenities {

    private const val RADIUS_M = 800

    /** 자취 기준으로 실제 판단에 쓰이는 것만. 지도 아이콘을 다 옮겨올 이유가 없다. */
    val CATEGORIES = listOf(
        "CS2" to "편의점",
        "MT1" to "대형마트",
        "SW8" to "지하철역",
        "HP8" to "병원",
        "FD6" to "음식점",
    )

    /**
     * 카테고리 코드가 없는 시설은 [Places] 가 만들어 둔 목록으로 **거리**를 잰다.
     *
     * 반경 검색을 쓰지 않는 이유는 [Places] 에 적어 두었다 — "백화점" 키워드는 백화점
     * 안에 입점한 매장 수백 개를 돌려주고, 거리순 상위가 전부 그것들이라 본체에 닿지
     * 못한다. 목록이 있으면 거리는 그냥 계산이고 API 도 필요 없다.
     *
     * 개수가 아니라 거리인 것도 의도다. 사용자가 궁금한 건 "백화점 몇 개"가 아니라
     * "백화점이 갈 만한가"다.
     */
    private val PLACE_SETS = listOf("DEPT" to "백화점")

    fun run(key: String, inFile: File, outFile: File, cacheFile: File, placeDir: File) {
        val mapper = ObjectMapper().registerKotlinModule()
        require(inFile.exists()) { "동 좌표가 없다: ${inFile.absolutePath}. 먼저 --mode donggeo" }
        val dongs = mapper.readTree(inFile)["dongs"]

        val cache: MutableMap<String, Int> =
            if (cacheFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                (mapper.readValue(cacheFile, Map::class.java) as Map<String, Int>).toMutableMap()
            } else mutableMapOf()
        println("      캐시 ${cache.size}건 로드")

        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        var queried = 0
        val total = dongs.size() * CATEGORIES.size

        // 목록 기반 시설은 API 를 쓰지 않는다. 없으면 그 항목만 빠진다.
        val placeDistance = HashMap<String, List<DoubleArray>>()
        for ((code, kw) in PLACE_SETS) {
            val f = File(placeDir, "places-${if (code == "DEPT") "dept" else code.lowercase()}.json")
            if (!f.exists()) {
                println("      ! $kw 목록이 없다(${f.name}). --mode places 를 먼저 돌릴 것")
                continue
            }
            val pts = mapper.readTree(f)["places"].map {
                doubleArrayOf(it["lon"].asDouble(), it["lat"].asDouble())
            }
            placeDistance[code] = pts
            println("      $kw ${pts.size}곳 로드")
        }

        for (d in dongs) {
            val name = "${d["gu"].asText()}|${d["name"].asText()}"
            val lon = d["lon"].asDouble()
            val lat = d["lat"].asDouble()
            for ((code, _) in CATEGORIES) {
                val ck = "$name|$code"
                if (cache.containsKey(ck)) continue
                cache[ck] = count(client, key, code, lon, lat) ?: -1
                queried++
                if (queried % 500 == 0) {
                    println("      조회 $queried / 최대 $total")
                    cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
                }
                Thread.sleep(35)
            }

        }
        cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
        println("      조회 $queried 건 완료")

        val out = LinkedHashMap<String, Map<String, Int>>()
        for (d in dongs) {
            val name = "${d["gu"].asText()}|${d["name"].asText()}"
            val row = LinkedHashMap<String, Int>()
            for ((code, _) in CATEGORIES) {
                val v = cache["$name|$code"] ?: continue
                if (v >= 0) row[code] = v
            }
            for ((code, _) in PLACE_SETS) {
                val v = placeDistance[code]
                    ?.let { pts -> nearest(pts, d["lon"].asDouble(), d["lat"].asDouble()) }
                    ?: continue
                row[code] = v
            }
            if (row.isNotEmpty()) out[name] = row
        }
        outFile.parentFile?.mkdirs()
        mapper.writeValue(outFile, out)
        println("      동 ${out.size}개 -> ${outFile.absolutePath} (${outFile.length() / 1024}KB)")

        // 임계값을 코드에 박으면 데이터가 바뀔 때마다 거짓말이 된다. 분포에서 뽑는다.
        for ((code, label) in CATEGORIES + PLACE_SETS) {
            val v = out.values.mapNotNull { it[code] }.sorted()
            if (v.isEmpty()) continue
            fun q(p: Double) = v[(v.size * p).toInt().coerceAtMost(v.size - 1)]
            println("      $label($code) 중위 ${q(0.5)} · 25% ${q(0.25)} · 75% ${q(0.75)} · 최대 ${v.last()}")
        }
    }

    /** 목록에서 가장 가까운 곳까지 거리(m). 목록이 비면 null. */
    private fun nearest(points: List<DoubleArray>, lon: Double, lat: Double): Int? {
        var best = Double.MAX_VALUE
        for (p in points) {
            val d = Geo.haversineMeters(lat, lon, p[1], p[0])
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) null else Math.round(best).toInt()
    }

    /** 반경 안 개수. `total_count` 만 필요하므로 `size=1` 로 최소한만 받는다. */
    private fun count(
        client: HttpClient,
        key: String,
        code: String,
        lon: Double,
        lat: Double,
    ): Int? {
        val url = "https://dapi.kakao.com/v2/local/search/category.json" +
            "?category_group_code=$code&x=$lon&y=$lat&radius=$RADIUS_M&size=1"
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "KakaoAK $key")
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                if (res.statusCode() == 200) {
                    return ObjectMapper().readTree(res.body())["meta"]?.get("total_count")?.asInt()
                }
                if (res.statusCode() == 429) Thread.sleep(1000L * (attempt + 1)) else return null
            } catch (e: Exception) {
                Thread.sleep(300L * (attempt + 1))
            }
        }
        return null
    }
}
