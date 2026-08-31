package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

private fun parseHm(s: String): Int {
    val (h, m) = s.split(":").map { it.toInt() }
    return h * 3600 + m * 60
}

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    val gml = File(opts["--gml"] ?: "data/raw/metro_graph.gml")
    val outDir = File(opts["--out"] ?: "data/out")
    val stepMin = (opts["--step-min"] ?: "10").toInt()
    val startSec = parseHm(opts["--start"] ?: "05:30")
    val endSec = parseHm(opts["--end"] ?: "24:40")
    val capMinutes = (opts["--cap-min"] ?: "180").toInt()
    val transferOverhead = (opts["--transfer-overhead-sec"] ?: "90").toInt()
    val originLimit = (opts["--origins"] ?: "0").toInt()   // 0 = 전부. 실측용.

    if (opts["--mode"] == "reprice") {
        Deals.repriceComplexes(
            rawFiles = (opts["--in"] ?: "data/raw/rent/deals-raw.jsonl")
                .split(",").map { File(it.trim()) },
            complexFile = File(opts["--complexes"] ?: "data/raw/rent/complexes-raw.json"),
        )
        return
    }

    if (opts["--mode"] == "reagg") {
        Deals.reaggregate(
            rawFiles = (opts["--in"] ?: "data/raw/rent/deals-raw.jsonl")
                .split(",").map { File(it.trim()) },
            outDir = File(opts["--out"] ?: "data/raw/rent"),
        )
        return
    }

    if (opts["--mode"] == "places") {
        val key = System.getenv("KAKAO_REST_KEY")
            ?: error("KAKAO_REST_KEY 가 없다. .env 를 읽고 실행할 것")
        val kw = opts["--keyword"] ?: "백화점"
        Places.run(key, kw, File(opts["--out"] ?: "data/raw/places-$kw.json"))
        return
    }

    if (opts["--mode"] == "amenity") {
        val key = System.getenv("KAKAO_REST_KEY")
            ?: error("KAKAO_REST_KEY 가 없다. .env 를 읽고 실행할 것")
        Amenities.run(
            key = key,
            inFile = File(opts["--in"] ?: "web/public/data/dongs.json"),
            outFile = File(opts["--out"] ?: "web/public/data/amenities.json"),
            cacheFile = File(opts["--cache"] ?: "data/raw/rent/amenity-cache.json"),
            placeDir = File(opts["--places"] ?: "data/raw"),
        )
        return
    }

    if (opts["--mode"] == "donggeo") {
        val key = System.getenv("KAKAO_REST_KEY")
            ?: error("KAKAO_REST_KEY 가 없다. .env 를 읽고 실행할 것")
        DongGeo.run(
            key = key,
            inFile = File(opts["--in"] ?: "data/raw/rent/dongs-raw.json"),
            outFile = File(opts["--out"] ?: "web/public/data/dongs.json"),
            cacheFile = File(opts["--cache"] ?: "data/raw/rent/dong-cache.json"),
            sggNameFile = File(opts["--sgg"] ?: "data/raw/sgg-names.json"),
        )
        return
    }

    if (opts["--mode"] == "geocode") {
        // 카카오 REST 키. 프론트 번들에 들어가면 안 되므로 VITE_ 를 쓰지 않는다.
        val kakao = System.getenv("KAKAO_REST_KEY")
            ?: File(".env").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("KAKAO_REST_KEY=") }?.substringAfter("=")
            ?: error("KAKAO_REST_KEY 가 없다 (.env 또는 환경변수)")
        Geocode.run(
            key = kakao.trim(),
            inFile = File(opts["--in"] ?: "data/raw/deals/complexes-raw.json"),
            outFile = File(opts["--out"] ?: "web/public/data/complexes.json"),
            cacheFile = File(opts["--cache"] ?: "data/raw/deals/geocode-cache.json"),
            limit = (opts["--limit"] ?: "0").toInt(),
        )
        return
    }

    if (opts["--mode"] == "deals") {
        // 키는 인자로 받지 않는다. 셸 히스토리와 프로세스 목록에 남기 때문이다.
        val key = System.getenv("DATA_GO_KR_KEY")
            ?: File(".env").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("DATA_GO_KR_KEY=") }?.substringAfter("=")
            ?: error("DATA_GO_KR_KEY 가 없다 (.env 또는 환경변수)")
        Deals.run(
            key = key.trim(),
            months = (opts["--months"] ?: "12").toInt(),
            outDir = File(opts["--out"] ?: "data/raw/deals"),
            sggFilter = opts["--sgg"]?.split(","),
            rental = opts["--rental"] == "true",
        )
        return
    }

    require(gml.exists()) { "GML 이 없다: ${gml.absolutePath}" }

    println("[1/5] 그래프 로드: ${gml.name}")
    val network = GmlLoader.load(gml, transferOverhead)
    println("      역 ${network.stationCount} / 승강장 ${network.platformCount} / " +
        "구간 ${network.trackEdges.size} / 환승 ${network.transferEdges.size}")
    val chains = network.lineSequences()
    println("      노선 ${chains.size}개, 체인 ${chains.values.sumOf { it.size }}개")

    if (opts["--mode"] == "render") {
        val tt = TimetableBuilder.synthesize(network)
        Render.run(
            network = network,
            router = Router(network, tt),
            originName = opts["--from"] ?: "강남",
            atSec = parseHm(opts["--at"] ?: "08:40"),
            arriveBy = (opts["--direction"] ?: "arrive") == "arrive",
            budgetMinutes = (opts["--budget"] ?: "40").toInt(),
            walkCapMinutes = (opts["--walk"] ?: "15").toInt(),
            out = File(opts["--out"] ?: "data/out/reach.svg"),
        )
        return
    }

    if (opts["--mode"] == "compare") {
        val ref = File(opts["--reference"] ?: "tools/reference-kakao.json")
        require(ref.exists()) { "기준값 파일이 없다: ${ref.absolutePath}" }
        val tt = TimetableBuilder.synthesize(network)
        Compare.run(network, Router(network, tt), ref)
        return
    }

    if (opts["--mode"] == "diag") {
        Diagnostics.lines(network)
        Diagnostics.transfers(network)
        Diagnostics.duplicateNames(network)
        val tt = TimetableBuilder.synthesize(network)
        val r = Router(network, tt)
        opts["--explain"]?.let { pair ->
            val (f, t) = pair.split(">")
            Diagnostics.explain(network, r, f, t, parseHm(opts["--at"] ?: "08:00"))
        }
        Diagnostics.sampleRoutes(
            network, r, opts["--from"] ?: "강남", parseHm(opts["--at"] ?: "08:00"),
            (opts["--to"] ?: "역삼,잠실,서울역,홍대입구,여의도,건대입구,수원,인천,상봉,안산,의정부,판교").split(","),
        )
        return
    }

    println("[2/5] 합성 시간표 생성")
    val timetable = TimetableBuilder.synthesize(network)
    println("      연결 ${"%,d".format(timetable.size)}개")

    println("[3/5] 슬롯 구성 (${stepMin}분 간격)")
    val slots = buildList {
        var t = startSec
        while (t <= endSec) {
            add(Slot(size, Direction.ARRIVE_BY, t)); t += stepMin * 60
        }
        t = startSec
        while (t <= endSec) {
            add(Slot(size, Direction.DEPART_AT, t)); t += stepMin * 60
        }
    }
    println("      슬롯 ${slots.size}개 (도착기준 + 출발기준)")

    val router = Router(network, timetable)
    val origins = if (originLimit > 0) minOf(originLimit, network.stationCount) else network.stationCount

    println("[4/5] 행렬 계산: 출발역 $origins × 슬롯 ${slots.size} = ${"%,d".format(origins * slots.size)}회 탐색")
    val matrixDir = File(outDir, "matrix")
    matrixDir.mkdirs()
    val done = AtomicInteger()
    val started = System.currentTimeMillis()

    IntStream.range(0, origins).parallel().forEach { origin ->
        val rows = Array(slots.size) { ByteArray(network.stationCount) }
        for (slot in slots) {
            val seconds = when (slot.direction) {
                Direction.ARRIVE_BY -> router.travelTimesArrivingBy(origin, slot.secondsOfDay)
                Direction.DEPART_AT -> router.travelTimesDepartingAt(origin, slot.secondsOfDay)
            }
            val row = rows[slot.index]
            for (s in seconds.indices) row[s] = MatrixWriter.toMinuteByte(seconds[s], capMinutes)
        }
        MatrixWriter.write(File(matrixDir, "$origin.bin"), slots, network.stationCount, rows)
        val n = done.incrementAndGet()
        if (n % 50 == 0 || n == origins) {
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            println("      $n/$origins  (%.1fs)".format(elapsed))
        }
    }

    println("[5/5] 매니페스트 작성")
    val mapper = ObjectMapper().registerKotlinModule()
    val manifest = mapOf(
        "version" to MatrixWriter.VERSION,
        "generatedBy" to "synthetic-headway-model",
        "warning" to "실제 시간표가 아니라 배차간격 추정치로 만든 합성 시간표다. KTDB GTFS 도착 시 교체할 것.",
        "capMinutes" to capMinutes,
        "transferOverheadSeconds" to transferOverhead,
        "slots" to slots.map {
            mapOf("index" to it.index, "direction" to it.direction.name,
                  "secondsOfDay" to it.secondsOfDay, "label" to it.label)
        },
        "stations" to network.stations.map {
            mapOf("index" to it.index, "name" to it.name, "lat" to it.lat, "lon" to it.lon,
                  "lines" to it.platforms.map { p -> network.platforms[p].line }.distinct())
        },
    )
    mapper.writerWithDefaultPrettyPrinter().writeValue(File(outDir, "manifest.json"), manifest)

    val bytes = matrixDir.listFiles()?.sumOf { it.length() } ?: 0
    println("완료: ${outDir.absolutePath}")
    println("      matrix ${"%,d".format(bytes)} bytes / manifest ${File(outDir, "manifest.json").length()} bytes")
}
