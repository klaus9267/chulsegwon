package reach

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Overpass 로 수도권 보행 도로를 받는다.
 *
 * **왜 PBF 가 아닌가.** 한국 PBF 를 주는 미러를 못 찾았다 — Geofabrik 은 이 환경에서
 * 503 이고(다른 호스트는 전부 정상), openstreetmap.fr 의 asia 에는 29개국뿐 한국이 없고,
 * BBBike 는 서울 한 도시만 있는데 그 범위가 126.58~127.31 로 수도권을 못 덮는다.
 *
 * 대신 Overpass 는 **필요한 것만** 준다. 우리가 쓰는 건 `highway` 웨이와 그 노드뿐이고
 * 그건 OSM 전체의 일부다. 건물·상점·경계는 받지 않는다.
 *
 * **타일로 나눈다.** 수도권을 한 번에 요청하면 공개 인스턴스의 메모리 한도에 걸린다.
 * 0.1°(약 9×11km) 씩 나누면 밀집 지역도 한 타일 18MB 에 10초다.
 *
 * **재개 가능하다.** 받은 타일은 건너뛴다. 이 프로젝트에서 같은 실수를 세 번 했다 —
 * 실거래가 45분, 버스 정류장 페이징, 그리고 도시코드. 이번엔 처음부터 넣는다.
 */
object Overpass {

    /** 수도권 + 여유. 경기 외곽(연천·가평·양평)까지 덮는다. */
    private const val WEST = 126.45
    private const val EAST = 127.60
    private const val SOUTH = 36.95
    private const val NORTH = 38.00

    /** 타일 한 변(도). 0.1° 면 약 9km × 11km. */
    private const val STEP = 0.1

    /**
     * 살아 있는 인스턴스만.
     *
     * 처음엔 kumi.systems 를 같이 넣었는데 **응답이 없는 서버였다.** 재시도마다 거기로
     * 번갈아 보내느라 절반을 버렸고, 타일당 67초가 나왔다. `/api/status` 를 찍어보고서야
     * 알았다 — 실패를 "붐빈다"로 읽으면 죽은 서버를 계속 두드리게 된다.
     *
     * overpass-api.de 는 동시 2개를 허용한다(`Rate limit: 2`). 그래서 레인을 3개 둔다 —
     * 그쪽 2개와 mail.ru 1개. 명시된 한도 안이라 예의에 어긋나지 않는다.
     */
    private val LANES = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    fun fetch(outDir: File) {
        outDir.mkdirs()
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val tiles = ArrayList<Triple<Int, Double, Double>>()
        var idx = 0
        var s = SOUTH
        while (s < NORTH) {
            var w = WEST
            while (w < EAST) {
                tiles += Triple(idx++, s, w)
                w += STEP
            }
            s += STEP
        }
        println("      타일 ${tiles.size}개 (${STEP}° · 약 9×11km)")

        val todo = java.util.concurrent.ConcurrentLinkedQueue(
            tiles.filter {
                val f = File(outDir, "t%03d.json".format(it.first))
                !(f.exists() && f.length() > 100)
            },
        )
        val already = tiles.size - todo.size
        println("      이미 받은 것 $already · 받을 것 ${todo.size}")

        val done = java.util.concurrent.atomic.AtomicInteger(already)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        val t0 = System.currentTimeMillis()

        val workers = LANES.mapIndexed { lane, endpoint ->
            Thread {
                while (true) {
                    val (i, south, west) = todo.poll() ?: break
                    val f = File(outDir, "t%03d.json".format(i))
                    val body = request(client, endpoint, query(south, west, south + STEP, west + STEP))
                    if (body == null) {
                        System.err.println("      ! 타일 $i 실패(레인 $lane) — 다음 실행에서 다시 받는다")
                        failed.incrementAndGet()
                        continue
                    }
                    f.writeText(body, Charsets.UTF_8)
                    bytes.addAndGet(f.length())
                    val d = done.incrementAndGet()
                    val mins = (System.currentTimeMillis() - t0) / 60000.0
                    val left = todo.size
                    val eta = if (d > already && mins > 0) left * mins / (d - already) else 0.0
                    println("      $d/${tiles.size}  타일 $i  ${"%,d".format(f.length() / 1024)}KB" +
                        "  남은 $left  예상 ${"%.0f".format(eta)}분")
                    // 공개 인스턴스다. 레인마다 간격을 둔다.
                    Thread.sleep(1500)
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }

        println("      완료 ${done.get()}/${tiles.size} (실패 ${failed.get()}) · " +
            "${"%,d".format(bytes.get() / 1024 / 1024)}MB")
        if (failed.get() > 0) println("      → 다시 실행하면 실패한 타일만 받는다")
    }

    /**
     * 보행 가능한 도로만.
     *
     * 서버에서 걸러야 받는 양이 준다. 고속도로·자동차전용도로와 명시적으로 막힌 길을 뺀다.
     * 판정 기준은 [Walk.isWalkable] 과 같아야 한다 — 한쪽만 고치면 조용히 어긋난다.
     */
    private fun query(s: Double, w: Double, n: Double, e: Double): String = """
        [out:json][timeout:300];
        (
          way["highway"]
             ["highway"!~"^(motorway|motorway_link|trunk|trunk_link|construction|proposed|raceway|bus_guideway)${'$'}"]
             ["foot"!="no"]
             ["access"!~"^(no|private)${'$'}"]
             ($s,$w,$n,$e);
        );
        out body qt;
        >;
        out skel qt;
    """.trimIndent()

    private fun request(client: HttpClient, url: String, q: String): String? {
        val payload = "data=" + URLEncoder.encode(q, Charsets.UTF_8)
        for (attempt in 0 until 4) {
            try {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "chulsegwon-builder/0.1 (github.com/klaus9267/chulsegwon)")
                        .timeout(Duration.ofMinutes(6))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                when (res.statusCode()) {
                    200 -> if (res.body().contains("\"elements\"")) return res.body()
                    // 429 는 슬롯이 없다는 뜻, 504 는 서버가 붐빈다는 뜻. 둘 다 기다리면 된다.
                    429, 504 -> {
                        System.err.println("      … ${res.statusCode()} — ${(attempt + 1) * 30}초 대기")
                        Thread.sleep(30_000L * (attempt + 1))
                    }
                    else -> {
                        System.err.println("      ! HTTP ${res.statusCode()}: " +
                            res.body().take(120).replace("\n", " "))
                        Thread.sleep(5_000)
                    }
                }
            } catch (ex: Exception) {
                System.err.println("      ! ${ex.javaClass.simpleName}: ${ex.message}")
                Thread.sleep(10_000L * (attempt + 1))
            }
        }
        return null
    }
}
