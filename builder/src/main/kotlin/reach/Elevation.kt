package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 동네가 역보다 얼마나 높은지.
 *
 * 자취에서 언덕은 월세 몇 만원보다 크게 체감된다. 짐을 들고, 장을 보고, 술 마시고
 * 매일 걸어 올라가는 사람에게 "역에서 40m 오르막"은 집을 고르는 기준이 된다.
 * 그런데 지도만 봐서는 절대 알 수 없다 — 평면에는 높이가 없다.
 *
 * 호갱노노가 경사도를 보여주지만 아파트 단지 기준이라 이 각도는 아니다. 우리는
 * **가장 가까운 역과의 고도차**를 쓴다. 통근 도구니까 기준점이 역인 게 맞고,
 * 절대 고도(북한산이 높다)가 아니라 매일 오르내리는 차이가 실제 부담이다.
 *
 * 고도는 [opentopodata](https://www.opentopodata.org) 의 SRTM 30m 을 쓴다. 무료·무인증에
 * 한 번에 100지점이라 DEM 타일(49MB)을 직접 받아 GeoTIFF 를 파싱하는 것보다 훨씬 싸다.
 *
 * ⚠️ **SRTM 은 지표가 아니라 지표면 모델이다.** 레이더가 건물 옥상에서 반사되므로
 * 도심에서 고도가 부풀려진다. 하필 우리가 관심 있는 곳이 가장 심하다 — 종각역이
 * 해발 93m 로 나왔는데 실제는 30m 남짓이다(+63m).
 *
 * 그래서 한 점만 읽지 않고 **주변 3×3 을 100m 간격으로 읽어 최솟값을 지면으로 본다.**
 * 건물은 국소 최대값이라 200m 범위 안에는 도로나 공터가 섞이기 때문이다. 검증해 보니
 * 평균 절대 오차가 25.5m → 5.8m 로 줄었다(종각 +63→+9, 강남 +20→0, 시청 +24→+8).
 * 표본이 9배가 되지만 그래도 216번 호출이라 하루 한도(1,000) 안에 든다.
 */
object Elevation {

    private const val API = "https://api.opentopodata.org/v1/srtm30m"
    private const val BATCH = 99   // 3의 배수여야 한 지점의 9개 표본이 배치에 걸쳐 쪼개지지 않는다

    /** 지면 추정용 표본 간격(m). 한 블록을 덮을 만큼은 넓어야 도로가 섞인다. */
    private const val PROBE_M = 100.0

    /** 이 이상 떨어진 역은 "가장 가까운 역"이라 부를 수 없다. 걸어갈 거리가 아니다. */
    private const val MAX_STATION_M = 2500.0

    fun run(manifestFile: File, dongFile: File, cacheFile: File) {
        val mapper = ObjectMapper().registerKotlinModule()
        require(manifestFile.exists()) { "manifest 가 없다: ${manifestFile.absolutePath}" }
        require(dongFile.exists()) { "동 데이터가 없다: ${dongFile.absolutePath}. 먼저 --mode donggeo" }

        val cache: MutableMap<String, Double> =
            if (cacheFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                (mapper.readValue(cacheFile, Map::class.java) as Map<String, Double>).toMutableMap()
            } else mutableMapOf()
        println("      캐시 ${cache.size}지점")

        val stations = mapper.readTree(manifestFile)["stations"].map {
            Triple(it["name"].asText(), it["lon"].asDouble(), it["lat"].asDouble())
        }
        val dongRoot = mapper.readTree(dongFile)
        val dongs = dongRoot["dongs"]

        val need = LinkedHashSet<Pair<Double, Double>>()
        for ((_, lon, lat) in stations) if (key(lon, lat) !in cache) need += lon to lat
        for (d in dongs) {
            val lon = d["lon"].asDouble(); val lat = d["lat"].asDouble()
            if (key(lon, lat) !in cache) need += lon to lat
        }
        println("      조회 필요 ${need.size}지점 (${(need.size + BATCH - 1) / BATCH}회 호출)")

        if (need.isNotEmpty()) {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
            val list = need.toList()
            var done = 0
            // 한 지점당 9표본. 배치 하나에 지점 11개(99표본)씩 들어간다.
            for (chunk in list.chunked(BATCH / 9)) {
                val probes = chunk.flatMap { (lon, lat) -> ring(lon, lat) }
                val got = fetch(client, probes)
                if (got == null) {
                    System.err.println("      ! 배치 실패 — 여기까지 저장하고 중단")
                    break
                }
                for ((i, p) in chunk.withIndex()) {
                    val nine = got.subList(i * 9, i * 9 + 9).filterNotNull()
                    if (nine.isNotEmpty()) cache[key(p.first, p.second)] = nine.min()
                }
                done += chunk.size
                if (done % 500 == 0 || done == list.size) {
                    println("      $done/${list.size}")
                    cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
                }
                Thread.sleep(1100)   // 공개 API 는 초당 1회다. 지킨다.
            }
            cacheFile.parentFile?.mkdirs(); mapper.writeValue(cacheFile, cache)
        }

        // 동마다 가장 가까운 역을 찾아 고도차를 낸다
        var withSlope = 0
        for (d in dongs) {
            val obj = d as ObjectNode
            val lon = obj["lon"].asDouble(); val lat = obj["lat"].asDouble()
            val elev = cache[key(lon, lat)] ?: continue
            obj.put("elev", Math.round(elev).toInt())

            var bestD = Double.MAX_VALUE
            var best: Triple<String, Double, Double>? = null
            for (st in stations) {
                val dist = Geo.haversineMeters(lat, lon, st.third, st.second)
                if (dist < bestD) { bestD = dist; best = st }
            }
            val st = best ?: continue
            if (bestD > MAX_STATION_M) continue
            val stElev = cache[key(st.second, st.third)] ?: continue
            obj.put("station", st.first)
            obj.put("stationM", Math.round(bestD).toInt())
            obj.put("climb", Math.round(elev - stElev).toInt())
            withSlope++
        }
        mapper.writeValue(dongFile, dongRoot)
        println("      동 ${dongs.size()}개 중 $withSlope 개에 고도차 기록 -> ${dongFile.name}")

        val climbs = dongs.mapNotNull { it["climb"]?.asInt() }.sorted()
        if (climbs.isNotEmpty()) {
            fun q(p: Double) = climbs[(climbs.size * p).toInt().coerceAtMost(climbs.size - 1)]
            println("      오르막 분포(m): 최소 ${climbs.first()} · 25% ${q(0.25)} · 중위 ${q(0.5)} · 75% ${q(0.75)} · 최대 ${climbs.last()}")
        }
    }

    /** 한 지점 주변 3×3 표본. 가운데가 원래 좌표다. */
    private fun ring(lon: Double, lat: Double): List<Pair<Double, Double>> {
        val dLat = PROBE_M / 111_000.0
        val dLon = PROBE_M / (111_000.0 * Math.cos(Math.toRadians(lat)))
        val out = ArrayList<Pair<Double, Double>>(9)
        for (i in -1..1) for (j in -1..1) out += (lon + j * dLon) to (lat + i * dLat)
        return out
    }

    /** 좌표를 캐시 키로. 소수 5자리(약 1m)면 같은 지점으로 봐도 된다. */
    private fun key(lon: Double, lat: Double) =
        "%.5f,%.5f".format(lat, lon)

    private fun fetch(client: HttpClient, pts: List<Pair<Double, Double>>): List<Double?>? {
        // ⚠️ `|` 는 URI 에서 허용되지 않는 문자다. 파이썬 urllib 은 그냥 통과시키지만
        // 자바 URI.create 는 예외를 던진다. 인코딩해서 넘긴다.
        val locs = pts.joinToString("%7C") { "%.6f,%.6f".format(it.second, it.first) }
        repeat(3) { attempt ->
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create("$API?locations=$locs"))
                        .timeout(Duration.ofSeconds(40)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                if (res.statusCode() == 200) {
                    val root = ObjectMapper().readTree(res.body())
                    if (root["status"]?.asText() != "OK") return null
                    return root["results"].map { r ->
                        r["elevation"]?.takeIf { !it.isNull }?.asDouble()
                    }
                }
                // 429 는 초당 제한. 기다렸다 다시 하면 된다.
                Thread.sleep(if (res.statusCode() == 429) 2500L * (attempt + 1) else 800L)
            } catch (e: Exception) {
                // 조용히 삼키면 왜 실패했는지 알 수 없다. 실제로 URI 문법 오류를
                // "네트워크 문제"로 오해해 한참 헤맸다.
                System.err.println("      ! ${e.javaClass.simpleName}: ${e.message}")
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        return null
    }
}
