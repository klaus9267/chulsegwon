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

    if (opts["--mode"] == "seoulbus" || opts["--mode"] == "seoulspeed") {
        val key = System.getenv("DATA_GO_KR_KEY")
            ?: error("DATA_GO_KR_KEY 가 없다. .env 를 읽고 실행할 것")
        val dir = File(opts["--out"] ?: "data/raw/seoul-bus")
        if (opts["--mode"] == "seoulbus") SeoulBus.collect(key, dir)
        else SeoulBus.snapshot(key, dir, limit = (opts["--limit"] ?: "380").toInt())
        return
    }

    if (opts["--mode"] == "raptor") {
        val t0 = System.currentTimeMillis()
        val data = TransitData.load(
            File(opts["--subway"] ?: "data/out/gtfs-subway.zip"),
            File(opts["--bus"] ?: "data/out/gtfs-seoul-gyeonggi.zip"),
        )
        val added = data.linkNearbyStops(
            maxMeters = (opts["--walklink"] ?: "400").toDouble(),
        )
        println("      ${data.describe()}  (${System.currentTimeMillis() - t0}ms)")
        println("      가까운 정류장 사이 도보 환승 ${"%,d".format(added)}개 생성")

        val fromName = opts["--from"] ?: "강남"
        // 출발점: 이름이 맞는 정류장 전부. 접근 도보는 아직 안 붙였다.
        val origins = (0 until data.stopCount)
            .filter { data.stopNames[it].substringBefore(' ') == fromName }
            .associateWith { parseHm(opts["--at"] ?: "08:00") }
        require(origins.isNotEmpty()) { "출발 정류장을 못 찾았다: $fromName" }
        println("      출발 '$fromName' 정류장 ${origins.size}개")

        val at = parseHm(opts["--at"] ?: "08:00")
        val cap = (opts["--budget"] ?: "90").toInt()
        // 출근은 "T 까지 도착"이라 시간축을 뒤집는다. 그러면 동네 1,768개에서
        // 직장 하나로 가는 many-to-one 이 직장 하나에서 나가는 one-to-many 가 된다.
        val arriveBy = (opts["--direction"] ?: "depart") == "arrive"
        val net = if (arriveBy) data.mirrored() else data
        val depart = if (arriveBy) -at else at
        val seed = origins.keys.associateWith { depart }
        val r = Raptor(net)
        val t1 = System.currentTimeMillis()
        val best = r.run(seed, depart + cap * 60)
        println("      탐색 ${System.currentTimeMillis() - t1}ms" +
            (if (arriveBy) "  (${opts["--at"] ?: "08:00"} 까지 도착 기준)" else "  (출발 기준)"))

        val reach = best.count { it < Raptor.INF }
        println("      ${cap}분 안에 닿는 정류장 ${"%,d".format(reach)} / ${"%,d".format(data.stopCount)}")
        // 이름이 아니라 **좌표**로 확인한다. 이름 앞자리로 고르면 엉뚱한 동네의
        // 같은 이름 정류장이 잡혀 "안산 42분" 같은 값이 나온다.
        // --to "이름@위도,경도;이름@위도,경도" 로 직접 찍을 수 있다. 카카오·네이버
        // 길찾기와 맞대볼 때 쓴다 — 붙박이 표본은 서울 도심 편향이라 마을버스가
        // 실제로 쓰이는 자리를 안 건드린다.
        val custom = (opts["--to"] ?: "").split(';').mapNotNull { spec ->
            val at = spec.indexOf('@')
            if (at <= 0) return@mapNotNull null
            val ll = spec.substring(at + 1).split(',')
            if (ll.size != 2) return@mapNotNull null
            Triple(spec.substring(0, at), ll[0].trim().toDouble(), ll[1].trim().toDouble())
        }
        val probes = if (custom.isNotEmpty()) custom else listOf(
            Triple("홍대입구", 37.5572, 126.9245), Triple("잠실", 37.5133, 127.1001),
            Triple("수원역", 37.2659, 127.0001), Triple("의정부역", 37.7383, 127.0470),
            Triple("판교역", 37.3948, 127.1112), Triple("일산 대화", 37.6763, 126.7476),
            Triple("안산 중앙", 37.3149, 126.8386), Triple("인천역", 37.4762, 126.6169),
            Triple("광교중앙", 37.2995, 127.0453), Triple("동탄역", 37.2007, 127.0982),
        )
        for ((name, la, lo) in probes) {
            var bv = Raptor.INF
            for (i in 0 until data.stopCount) {
                if (data.stopLat[i] == 0.0) continue
                if (Geo.haversineMeters(la, lo, data.stopLat[i], data.stopLon[i]) > 400) continue
                if (best[i] < bv) bv = best[i]
            }
            println("        %-10s %s".format(name,
                if (bv >= Raptor.INF) "도달 못함" else "${(bv - depart) / 60}분"))
        }
        return
    }

    if (opts["--mode"] == "oracle") {
        Oracle.run(
            File(opts["--subway"] ?: "data/out/gtfs-subway.zip"),
            File(opts["--bus"] ?: "data/out/gtfs-seoul-gyeonggi.zip"),
            opts["--from"] ?: "강남",
            opts["--at"] ?: "08:00",
            File(opts["--out"] ?: "data/out/verify/oracle.csv"),
        )
        return
    }

    if (opts["--mode"] == "gtfsexpand") {
        GtfsExpand.run(
            File(opts["--in"] ?: "data/out/gtfs-seoul-gyeonggi.zip"),
            File(opts["--out"] ?: "data/out/gtfs-expanded.zip"),
        )
        return
    }

    if (opts["--mode"] == "gtfscheck") {
        GtfsCheck.run(File(opts["--in"] ?: "data/out/gtfs-seoul-gyeonggi.zip"))
        return
    }

    if (opts["--mode"] == "gtfs") {
        Gtfs.export(
            gyeonggiDir = File(opts["--gyeonggi"] ?: "data/raw/bus"),
            seoulDir = File(opts["--seoul"] ?: "data/raw/seoul-bus"),
            villageDir = File(opts["--village"] ?: "data/raw/gbis"),
            outFile = File(opts["--out"] ?: "data/out/gtfs-seoul-gyeonggi.zip"),
            calibrationFile = File(opts["--calibration"] ?: "data/calibration.json"),
        )
        return
    }

    if (opts["--mode"] == "osmfetch") {
        Overpass.fetch(File(opts["--out"] ?: "data/raw/osm/tiles"))
        return
    }

    if (opts["--mode"] == "walk") {
        val bb = opts["--bbox"]?.split(",")?.map { it.trim().toDouble() }
        Walk.build(
            pbf = File(opts["--pbf"] ?: "data/raw/osm/south-korea.osm.pbf"),
            outDir = File(opts["--out"] ?: "data/raw/osm"),
            bbox = bb?.let { Walk.Bbox(it[0], it[1], it[2], it[3]) },
            check = opts.containsKey("--check"),
        )
        return
    }

    if (opts["--mode"] == "bus") {
        val key = System.getenv("DATA_GO_KR_KEY")
            ?: error("DATA_GO_KR_KEY 가 없다. .env 를 읽고 실행할 것")
        val cities = opts["--cities"]?.split(",")?.map { it.trim() } ?: Bus.CAPITAL_AREA
        Bus.run(key, cities, File(opts["--out"] ?: "data/raw/bus"))
        return
    }

    if (opts["--mode"] == "elevation") {
        Elevation.run(
            manifestFile = File(opts["--manifest"] ?: "web/public/data/manifest.json"),
            dongFile = File(opts["--dongs"] ?: "web/public/data/dongs.json"),
            cacheFile = File(opts["--cache"] ?: "data/raw/rent/elevation-cache.json"),
        )
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

    // 감도 스윕용. 평상시엔 안 준다.
    opts["--rounds"]?.toIntOrNull()?.let {
        Raptor.MAX_ROUNDS = it
        println("      ⚠️ 승차 상한을 ${it} 로 바꿔 돈다 (감도 측정)")
    }

    if (opts["--mode"] == "dongmatrix") {
        DongMatrix.build(
            network = network,
            subwayGtfs = File(opts["--subway"] ?: "data/out/gtfs-subway.zip"),
            busGtfs = File(opts["--bus"] ?: "data/out/gtfs-seoul-gyeonggi.zip"),
            dongsJson = File(opts["--dongs"] ?: "web/public/data/dongs.json"),
            outDir = File(opts["--out"] ?: "data/out/reach2"),
            capMinutes = (opts["--cap"] ?: "120").toInt(),
            walkGraph = File(opts["--walkgraph"] ?: "data/raw/osm/walk-graph.bin"),
            // 배차 위상을 몇 번 뽑아 중앙값을 쓸지. 1 이면 예전과 같다.
            // 4 면 표본 6개 기준 중앙값과 90%가 2분 이내로 붙는다(실측).
            phases = (opts["--phases"] ?: "4").toInt(),
            maxPerStop = (opts["--maxperstop"] ?: "64").toInt(),
        )
        return
    }

    if (opts["--mode"] == "gtfssubway") {
        SubwayGtfs.export(network, File(opts["--out"] ?: "data/out/gtfs-subway.zip"))
        return
    }

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
