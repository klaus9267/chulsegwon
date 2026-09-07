package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 수집한 버스 데이터를 GTFS 로 내보낸다.
 *
 * **왜 GTFS 인가.** 우리 파이프라인은 GTFS 를 먹지 않는다 — [TimetableBuilder] 가
 * 배차간격에서 시간표를 합성한다. 그런데도 만드는 이유는 셋이다:
 *
 * 1. **공개 GTFS 가 멈춰 있다.** KTDB 자료가 2023-03 이후 갱신이 없다. 최신 수도권
 *    GTFS 를 공개하면 OTP2·R5 를 쓰는 사람들이 실제로 쓴다
 * 2. **검증 도구가 생긴다.** MobilityData 의 GTFS validator 가 정합성을 기계적으로
 *    잡아준다. 자체 포맷에는 그런 게 없다
 * 3. 나중에 엔진을 바꿀 여지
 *
 * **frequency-based 로 만든다.** `frequencies.txt` 를 쓰면 `stop_times.txt` 에
 * **노선당 대표 운행 하나의 상대 시각**만 담고 실제 편성은 배차간격으로 생성된다.
 * 우리가 이미 하는 구조와 같고, 파일이 수십 배 작아진다. 그래서 필요한 건
 * "전 노선 모든 편성의 시각"이 아니라 **노선별 정류장 간 소요시간** 한 벌뿐이다.
 *
 * 그 소요시간을 어디서 얻느냐가 이 파일의 대부분이다 — [SpeedTable] 을 볼 것.
 */
object Gtfs {

    /**
     * 정류장 하나를 지나는 데 붙는 시간(초).
     *
     * 이름은 "정차"지만 실제로 담는 건 넷이다 — **감속 · 정차 · 가속 · 신호**.
     * `sectSpd` 는 정류장 사이를 달리는 순항 속도라 이 넷이 통째로 빠져 있다.
     *
     * **처음엔 20초로 뒀다가 카카오맵 대조에서 틀린 게 드러났다**(ADR-26).
     * 구간 830개를 대보니 우리 소요시간이 체계적으로 30% 짧았고, 학습셋 절반으로
     * 값을 맞춰보니 58초가 나왔다. 평가셋(나머지 OD 17개)에서 편향 −298초 → −16초,
     * ±3분 이내 44% → 77%.
     *
     * ⚠️ **주의.** "주행을 1.7배 느리게" 해도 오차가 비슷하게 줄어든다 —
     * 이 자료만으로는 둘이 구분되지 않는다(MAE 지형이 대각선 골짜기다).
     * 그래서 **잰 값은 건드리지 않고 재본 적 없는 쪽에 보정을 넣었다.**
     * 낮 시간대 `sectSpd` 가 쌓이면 순항 속도가 제 값을 찾고, 그때 이 값도
     * 다시 맞춰야 한다. **그래서 상수가 아니라 [loadCalibration] 이 읽는 값이다** —
     * `tools/gtfs_match.py --fit` 이 카카오 기준값에 맞춰 다시 계산해 파일에 쓰고,
     * 다음 실행이 그걸 집어 든다. 아래는 그 파일이 없을 때의 값이다.
     */
    private const val DWELL_SEC_DEFAULT = 58

    /**
     * 경기 구간 거리 보정.
     *
     * 경기 API 는 구간 거리를 안 준다. 좌표 직선거리로 대신한다.
     *
     * 처음엔 **1.01** 을 썼다. 서울 구간에서 도로거리 ÷ 직선거리의 중앙값을 잰
     * 값이었는데, 그건 **구간 하나짜리 중앙값**이라 짧은 구간이 표를 지배했다.
     * 카카오가 주는 실제 도로거리와 **여러 구간을 이어 붙인 단위**로 대보니
     * 서울 1.07 · 경기 1.06 이다(구간 825개). 사람이 실제로 타는 단위가 후자라
     * 그쪽을 쓴다. 이 값도 보정 파일이 덮어쓴다.
     */
    private const val DETOUR_DEFAULT = 1.07

    /**
     * 노선 유형별 표정속도(km/h). **맨 마지막 수단**이다.
     *
     * 처음엔 이 표만 썼는데, 8829번(이천터미널→인천공항)이 편도 480분으로 나왔다.
     * 87개 정류장 중 39개 구간이 3km 를 넘는 고속도로 구간인데 거기에 경기버스
     * 표정속도 22km/h 를 먹인 결과였다. 데이터가 틀린 게 아니라 **모델이 틀렸다** —
     * 속도는 노선 유형만이 아니라 **정류장 간격**에 달려 있다. [SpeedModel] 을 볼 것.
     */
    private val FALLBACK_KMH = mapOf(
        "공항버스" to 40.0, "광역버스" to 30.0, "직행좌석버스" to 30.0, "좌석버스" to 24.0,
        "간선버스" to 19.0, "일반버스" to 19.0, "지선버스" to 16.0,
        "마을버스" to 14.0, "순환버스" to 15.0, "농어촌버스" to 22.0,
        "인천버스" to 22.0, "경기버스" to 22.0,
    )
    private const val DEFAULT_KMH = 19.0

    /**
     * 바깥 기준값에 맞춰 다시 계산되는 값들.
     *
     * **왜 파일로 빼나.** 이 파이프라인은 스스로 좋아지도록 만든 것이다. 수집이
     * 하루 다섯 번 돌면서 속도 표본이 바뀌면 정류장 통과 비용도 따라 바뀌어야 하는데,
     * 상수로 두면 사람이 코드를 고쳐야 한다. 그러면 안 고쳐진다.
     *
     * `tools/gtfs_match.py --fit` 이 카카오 기준값(구간 800여 개)에 맞춰 계산해
     * 여기 쓰고, 다음 생성이 집어 든다. 값의 움직임은 `progress.csv` 에 남는다.
     */
    private class Calibration(val dwellSec: Int, val detour: Double, val note: String)

    private fun loadCalibration(f: File): Calibration {
        if (!f.exists()) return Calibration(DWELL_SEC_DEFAULT, DETOUR_DEFAULT, "기본값 (보정 파일 없음)")
        return try {
            @Suppress("UNCHECKED_CAST")
            val m = ObjectMapper().readValue(f, Map::class.java) as Map<String, Any?>
            Calibration(
                (m["dwellSec"] as? Number)?.toInt() ?: DWELL_SEC_DEFAULT,
                (m["detour"] as? Number)?.toDouble() ?: DETOUR_DEFAULT,
                m["note"] as? String ?: f.name,
            )
        } catch (e: Exception) {
            // 보정 파일이 깨졌다고 생성이 멈추면 안 된다. 기본값으로 계속 간다.
            System.err.println("      ! 보정 파일을 못 읽었다 (${e.message}) — 기본값을 쓴다")
            Calibration(DWELL_SEC_DEFAULT, DETOUR_DEFAULT, "기본값 (보정 파일 손상)")
        }
    }

    /** GPS 잡음을 자른다. 실제로 200km/h 짜리 구간이 찍힌다. */
    private const val MIN_KMH = 5.0
    private const val MAX_KMH = 80.0

    private class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

    /** 하나의 방향 운행. `dists[k]` 는 `stops[k-1] → stops[k]` 거리(m), 없으면 -1. */
    private class Trip(
        val routeId: String, val srcRouteId: String, val routeType: String,
        val dir: Int, val stops: List<String>, val ords: List<Int>, val dists: List<Int>,
    )

    private class Route(
        val id: String, val no: String, val type: String,
        val first: String, val last: String,
        val hwWeekday: Int?, val hwSat: Int?, val hwSun: Int?,
    )

    /**
     * 첫차·막차 시각을 `HHmm` 으로 정규화한다. 못 읽으면 null.
     *
     * 서울 API 는 `20260907043000` 처럼 주는데 자릿수가 어긋난 노선이 하나 있었다 —
     * M6439인천의 첫차가 `5000시` 로 읽혔고, 그게 `start_time > end_time` 이 돼
     * 검증기에 걸렸다. 값 하나 때문에 파일 전체가 거부되는 게 GTFS 라서, 읽히는지
     * 여기서 확인하고 안 읽히면 기본값으로 보낸다.
     *
     * 27시까지 허용하는 건 심야버스 때문이다. 첫차 23시 막차 03시인 노선이 실제로 있다.
     */
    private fun hhmm(v: String?): String? {
        val d = (v ?: "").filter { it.isDigit() }
        if (d.length != 4) return null
        val h = d.substring(0, 2).toInt()
        val m = d.substring(2, 4).toInt()
        return if (h in 0..27 && m in 0..59) d else null
    }

    // ── 실측 속도 ────────────────────────────────────────────────

    /**
     * 서울 `sectSpd` 스냅샷에서 구간별 속도를 뽑는다.
     *
     * **왜 스냅샷을 모으나.** `getStaionByRoute` 는 노선 하나를 부르면 그 순간 전 구간의
     * 속도를 준다. 차량을 1분마다 추적해 정류장 통과를 관찰하는 방식보다 요청이 300배
     * 적다. 대신 그 값은 **그 순간**이라, 30초 만에 104구간 중 12개가 바뀐다. 그래서
     * 하루 다섯 번씩 며칠 찍어 **중앙값**을 쓴다. 평균이 아니라 중앙값인 이유는
     * 사고나 GPS 튐 한 번이 평균을 통째로 망가뜨리기 때문이다.
     *
     * 스냅샷이 쌓일수록 실측 비율이 올라간다. 그게 이 수집 스케줄의 성과 지표다.
     */
    class SpeedTable(dir: File, mapper: ObjectMapper) {
        private val perSegment = HashMap<String, MutableList<Int>>()
        var snapshots = 0; private set

        /** 구간 길이. 속도 곡선을 학습할 때 x축이 된다. */
        private val distOf = HashMap<String, Int>()

        private fun remember(key: String, meters: Int) { distOf[key] = meters }

        init {
            val files = File(dir, "speed").listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
            snapshots = files.size
            for (f in files.sortedBy { it.name }) {
                for (line in f.readLines()) {
                    if (line.isBlank()) continue
                    @Suppress("UNCHECKED_CAST")
                    val row = mapper.readValue(line, Map::class.java) as Map<String, Any?>
                    val id = row["id"] as? String ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val segs = row["s"] as? List<List<Number>> ?: continue
                    for (s in segs) {
                        if (s.size < 3) continue
                        val kmh = s[2].toDouble()
                        if (kmh < MIN_KMH || kmh > MAX_KMH) continue
                        val key = "$id:${s[0].toInt()}"
                        perSegment.getOrPut(key) { ArrayList(4) } += kmh.toInt()
                        remember(key, s[1].toInt())
                    }
                }
            }
        }

        /** 그 구간의 실측 속도 중앙값. 관측이 없으면 null. */
        fun kmh(routeId: String, ord: Int): Double? {
            val v = perSegment["$routeId:$ord"] ?: return null
            if (v.isEmpty()) return null
            return v.sorted()[v.size / 2].toDouble()
        }

        val segments: Int get() = perSegment.size

        /** 학습용 원자료. (노선유형, 구간거리m, 속도km/h) */
        fun observations(typeOf: (String) -> String?): List<Triple<String, Int, Int>> {
            val out = ArrayList<Triple<String, Int, Int>>(perSegment.size)
            for ((k, v) in perSegment) {
                val t = typeOf(k.substringBefore(':')) ?: continue
                val d = distOf[k] ?: continue
                out += Triple(t, d, v.sorted()[v.size / 2])
            }
            return out
        }
    }

    // ── 속도 모델 ────────────────────────────

    /**
     * 정류장 간격에서 속도를 예측한다.
     *
     * **이게 이 파일에서 제일 중요한 판단이다.** 버스 속도를 노선 유형만으로 정하면
     * 고속도로를 타는 광역버스가 골목을 도는 마을버스와 같은 취급을 받는다. 실제로
     * 그것 때문에 편도 8시간짜리 운행이 나왔다.
     *
     * 그런데 "고속도로 구간인가"를 알려면 도로망이 필요하다. 없어도 되는 대리 지표가
     * **정류장 간격**이다. 정류장을 3km 마다 세우는 구간은 정의상 고속 구간이고,
     * 200m 마다 세우는 구간은 아니다. 서울 실측 33,535구간이 그 관계를 그대로 보여준다:
     *
     * ```
     *    0~200m  15km/h        2000~3500m   47km/h
     *  200~400m  21km/h        6000~10000m  63km/h
     *  400~700m  30km/h       10000m~       57km/h
     * ```
     *
     * 그래서 **표를 쓰지 않고 실측에서 이 곡선을 학습한다.** 스냅샷이 쌓일수록
     * 곡선이 정확해지고, 그게 수집 스케줄을 계속 돌리는 이유다.
     *
     * 두 가지를 규제한다:
     * * 칸마다 관측이 100개는 있어야 유형별로 쓴다. 아니면 거리만으로 본다
     * * **거리가 늘수록 느려지지는 않는다**고 본다. 3.5~6km 칸이 그 아래 칸보다
     *   느리게 나오는데 관측이 102개뿐이라 잡음으로 판단했다. 누적 최대로 눌러둔다
     */
    class SpeedModel(obs: List<Triple<String, Int, Int>>) {
        /** 칸 경계(m). 로그에 가깝게 나눴다 — 짧은 쪽이 압도적으로 많아서다. */
        private val edges = intArrayOf(200, 400, 700, 1200, 2000, 3500, 6000, 10000, Int.MAX_VALUE)
        private val byTypeBucket = HashMap<String, DoubleArray>()
        private val byBucket: DoubleArray
        var typedCurves = 0; private set

        private fun bucket(m: Double): Int {
            for (i in edges.indices) if (m < edges[i]) return i
            return edges.size - 1
        }

        private fun curve(rows: List<Triple<String, Int, Int>>, minN: Int): DoubleArray? {
            val cells = Array(edges.size) { ArrayList<Int>() }
            for ((_, d, v) in rows) cells[bucket(d.toDouble())] += v
            val out = DoubleArray(edges.size) { -1.0 }
            var any = false
            for (i in edges.indices) {
                val c = cells[i]
                if (c.size < minN) continue
                out[i] = c.sorted()[c.size / 2].toDouble()
                any = true
            }
            if (!any) return null
            // 아래에서 위로: 빈 칸은 바로 아래 칸 값으로 메우고, 거꾸로 가면 눌러준다
            var last = -1.0
            for (i in edges.indices) {
                if (out[i] < 0 || out[i] < last) out[i] = last
                last = out[i]
            }
            // 맨 앞이 비어 있으면 위쪽 값으로 메운다
            var next = -1.0
            for (i in edges.indices.reversed()) {
                if (out[i] < 0) out[i] = next else next = out[i]
            }
            return out
        }

        init {
            byBucket = curve(obs, 30) ?: DoubleArray(edges.size) { -1.0 }
            for ((t, rows) in obs.groupBy { it.first }) {
                curve(rows, 100)?.let { byTypeBucket[t] = it; typedCurves++ }
            }
        }

        /** 이 유형·이 거리의 예상 주행속도(km/h). 학습 못 했으면 null. */
        fun kmh(type: String, meters: Double): Double? {
            val b = bucket(meters)
            byTypeBucket[type]?.get(b)?.takeIf { it > 0 }?.let { return it }
            return byBucket[b].takeIf { it > 0 }
        }

        fun describe(): String = edges.indices.joinToString(" ") { i ->
            val lo = if (i == 0) 0 else edges[i - 1]
            val v = byBucket[i]
            (if (lo >= 1000) "${lo / 1000}km" else "${lo}m") + ":" + (if (v > 0) "${v.toInt()}" else "·")
        }
    }

    // ── 진입점 ───────────────────────────────────────────────────

    fun export(gyeonggiDir: File, seoulDir: File, outFile: File, calibrationFile: File) {
        val cal = loadCalibration(calibrationFile)
        println("      보정: 정류장 통과 ${cal.dwellSec}초 · 구간거리 ×${"%.3f".format(cal.detour)} — ${cal.note}")
        val mapper = ObjectMapper().registerKotlinModule()
        val stops = LinkedHashMap<String, Stop>()
        val routes = LinkedHashMap<String, Route>()
        val trips = ArrayList<Trip>()

        val speed = SpeedTable(seoulDir, mapper)
        println("      실측 속도: 스냅샷 ${speed.snapshots}개 · 구간 ${"%,d".format(speed.segments)}개")

        // 순서가 중요하다. 경기 노선 정보를 먼저 알아야 서울 목록에서 어떤 게
        // 중복인지 판단할 수 있고, 그 판단이 나와야 경기 정류장열을 건너뛸 수 있다.
        loadGyeonggiRoutes(gyeonggiDir, mapper, routes)
        val handover = seoulRoutes(seoulDir, mapper, routes)
        loadSeoulStops(seoulDir, mapper, stops, routes, trips, handover)
        loadGyeonggiStops(gyeonggiDir, mapper, stops, routes, trips, handover.values.toHashSet())

        println("      정류장 ${"%,d".format(stops.size)} · 노선 ${"%,d".format(routes.size)}" +
            " · 운행 ${"%,d".format(trips.size)}")

        // 실측에서 "정류장 간격 → 속도" 곡선을 학습한다. 노선 유형을 알아야 하므로
        // 노선을 다 읽은 뒤에 만든다.
        val model = SpeedModel(speed.observations { rid -> routes["S$rid"]?.type })
        println("      속도 곡선(구간길이:km/h) ${model.describe()}")
        write(outFile, stops, routes, trips, speed, model, cal)
    }

    // ── 서울 ────────────────────────────────────────────────────

    /**
     * 서울 노선 목록. 반환값은 **경기와 겹치는 노선의 인계표** 다.
     *
     * ⚠️ 서울 API 는 서울을 지나는 경기·인천 노선도 함께 준다. 597개가 경기 노선이라
     * 그냥 두면 같은 버스가 두 대로 세어져 배차가 두 배가 되고 도달권이 실제보다 넓어진다.
     *
     * 처음엔 노선번호로 짝을 지으려 했는데 서울은 외부 노선에 도시명을 붙여 준다 —
     * `1-1김포`, `1002화성`. 597개 중 4개밖에 안 맞았다. 그러다 id 를 봤다:
     *
     * ```
     * 서울 208000024  ↔  경기 GGB208000024
     * ```
     *
     * **서울 id 앞에 `GGB` 를 붙이면 경기 id 다.** 597개 중 549개가 이 규칙으로 붙고,
     * 인천 38개는 하나도 안 붙는다(우리한테 인천 데이터가 없으니 맞는 결과다).
     * 좌표로 대조해봐도 같은 노선 전체를 가리킨다 — 정류장 수 비 1.00, 겹침 93~100%.
     *
     * 그래서 **버리는 게 아니라 합친다.** 그 549개는 서울판이 더 낫다:
     *
     * | | 서울판 | 경기판 |
     * |---|---|---|
     * | 방향 | 498개 노선에서 상·하행 구분 ✅ | 전부 null → 추측으로 잘라야 함 |
     * | 구간거리 | 549개 전부 ✅ | 없음 → 좌표 직선거리로 대신 |
     * | 요일별 배차 | ❌ 한 값뿐 | 평일·토·일 ✅ |
     *
     * 그래서 **노선 정보는 경기 것, 정류장 기하는 서울 것**을 쓴다.
     *
     * @return 서울 원본 id → 경기 노선 id
     */
    private fun seoulRoutes(
        dir: File, mapper: ObjectMapper, routes: MutableMap<String, Route>,
    ): Map<String, String> {
        val handover = HashMap<String, String>()
        val rf = File(dir, "routes.jsonl")
        if (!rf.exists()) { println("      서울 데이터 없음 — 건너뜀"); return handover }

        var kept = 0
        for (r in readAll(rf, mapper)) {
            val src = r["id"] as? String ?: continue
            val gid = "GGGB$src"          // "G" 접두 + 경기 원본 id "GGB…"
            if (routes.containsKey(gid)) { handover[src] = gid; continue }
            val hw = (r["headway"] as? Number)?.toInt()
            routes["S$src"] = Route(
                id = "S$src", no = r["no"] as? String ?: "", type = seoulType(r["type"] as? String),
                first = hhmm(r["first"] as? String) ?: "0500",
                last = hhmm(r["last"] as? String) ?: "2300",
                // 서울은 요일별 배차를 안 준다. 한 값을 세 요일에 그대로 쓴다.
                hwWeekday = hw, hwSat = hw, hwSun = hw,
            )
            kept++
        }
        println("      서울 노선 ${"%,d".format(kept)}개 · 경기와 같은 노선 ${"%,d".format(handover.size)}개는" +
            " 경기 노선정보 + 서울 기하로 합침")
        return handover
    }

    private fun loadSeoulStops(
        dir: File, mapper: ObjectMapper,
        stops: MutableMap<String, Stop>, routes: MutableMap<String, Route>,
        trips: MutableList<Trip>, handover: Map<String, String>,
    ) {
        for (row in readAll(File(dir, "route-stops.jsonl"), mapper)) {
            val src = row["id"] as? String ?: continue
            val id = handover[src] ?: "S$src"
            val route = routes[id] ?: continue
            @Suppress("UNCHECKED_CAST")
            val list = row["stops"] as? List<Map<String, Any?>> ?: continue
            for (s in list) {
                val sid = "S" + (s["stop"] as? String ?: continue)
                val la = (s["lat"] as? Number)?.toDouble() ?: continue
                val lo = (s["lon"] as? Number)?.toDouble() ?: continue
                if (la <= 0 || lo <= 0) continue
                stops.putIfAbsent(sid, Stop(sid, s["name"] as? String ?: sid, la, lo))
            }
            // 서울은 `direction`(종점 이름)이 온다. 방향별로 운행을 나눈다.
            for ((di, group) in list.groupBy { it["dir"] as? String ?: "" }.values.withIndex()) {
                val o = group.filter { (it["lat"] as? Number)?.toDouble() ?: 0.0 > 0.0 }
                    .sortedBy { (it["ord"] as? Number)?.toInt() ?: 0 }
                if (o.size < 2) continue
                trips += Trip(
                    routeId = id, srcRouteId = src, routeType = route.type,
                    dir = di.coerceAtMost(1),
                    stops = o.map { "S" + (it["stop"] as String) },
                    ords = o.map { (it["ord"] as? Number)?.toInt() ?: 0 },
                    dists = o.map { (it["distM"] as? Number)?.toInt() ?: -1 },
                )
            }
        }
    }

    /** 서울 `routeType` 은 숫자 코드다. */
    private fun seoulType(code: String?): String = when (code) {
        "1" -> "공항버스"; "2" -> "마을버스"; "3" -> "간선버스"; "4" -> "지선버스"
        "5" -> "순환버스"; "6" -> "광역버스"; "7" -> "인천버스"; "8" -> "경기버스"
        else -> "일반버스"
    }

    // ── 경기 ────────────────────────────────────────────────────

    private fun loadGyeonggiRoutes(
        dir: File, mapper: ObjectMapper, routes: MutableMap<String, Route>,
    ) {
        val rf = File(dir, "routes.jsonl")
        if (!rf.exists()) { println("      경기 데이터 없음 — 건너뜀"); return }

        val detail = readAll(File(dir, "route-detail.jsonl"), mapper).associateBy { it["id"] as String }
        for (r in readAll(rf, mapper)) {
            val src = r["id"] as? String ?: continue
            val d = detail[src]
            routes["G$src"] = Route(
                id = "G$src", no = r["no"] as? String ?: "", type = r["type"] as? String ?: "일반버스",
                first = hhmm(d?.get("first") as? String) ?: "0500",
                last = hhmm(d?.get("last") as? String) ?: "2300",
                hwWeekday = (d?.get("headwayWeekday") as? Number)?.toInt(),
                hwSat = (d?.get("headwaySat") as? Number)?.toInt(),
                hwSun = (d?.get("headwaySun") as? Number)?.toInt(),
            )
        }
    }

    private fun loadGyeonggiStops(
        dir: File, mapper: ObjectMapper,
        stops: MutableMap<String, Stop>, routes: MutableMap<String, Route>,
        trips: MutableList<Trip>, fromSeoul: Set<String>,
    ) {
        var split = 0
        var handed = 0
        for (row in readAll(File(dir, "route-stops.jsonl"), mapper)) {
            val id = "G" + (row["id"] as? String ?: continue)
            // 서울판 기하를 쓰기로 한 노선이면 여기선 건너뛴다
            if (id in fromSeoul) { handed++; continue }
            val route = routes[id] ?: continue
            @Suppress("UNCHECKED_CAST")
            val list = row["stops"] as? List<Map<String, Any?>> ?: continue
            for (s in list) {
                val sid = "G" + (s["stop"] as? String ?: continue)
                val la = (s["lat"] as? Number)?.toDouble() ?: continue
                val lo = (s["lon"] as? Number)?.toDouble() ?: continue
                if (la <= 0 || lo <= 0) continue
                stops.putIfAbsent(sid, Stop(sid, s["name"] as? String ?: sid, la, lo))
            }
            val o = list.filter { (it["lat"] as? Number)?.toDouble() ?: 0.0 > 0.0 }
                .sortedBy { (it["ord"] as? Number)?.toInt() ?: 0 }
            if (o.size < 2) continue
            val parts = splitAtTurnaround(o, stops)
            if (parts.size > 1) split++
            for ((di, part) in parts.withIndex()) {
                if (part.size < 2) continue
                trips += Trip(
                    routeId = id, srcRouteId = row["id"] as String, routeType = route.type,
                    dir = di.coerceAtMost(1),
                    stops = part.map { "G" + (it["stop"] as String) },
                    ords = part.map { (it["ord"] as? Number)?.toInt() ?: 0 },
                    dists = part.map { -1 },   // 경기는 구간 거리를 안 준다. 좌표로 잰다
                )
            }
        }
        println("      경기: 서울 기하를 쓴 노선 ${"%,d".format(handed)}개 · " +
            "방향이 없어 반환점에서 자른 노선 ${"%,d".format(split)}개")
    }

    /**
     * 왕복이 이어붙은 정류장 열을 방향별로 자른다.
     *
     * 경기 노선은 `updowncd` 가 전부 null 이라 상·하행 구분이 없다. 300번 노선은
     * 정류장이 163개인데 82번째가 기점에서 가장 멀고(30.9km) 마지막은 기점과 0.0km 다 —
     * 왕복이 한 줄에 들어 있다는 뜻이다. 이걸 안 자르면 "기점에서 종점까지 60분"이
     * "기점 → 종점 → 기점 120분"으로 계산돼 도달권이 절반으로 줄어든다.
     *
     * 기점에서 가장 먼 지점을 반환점으로 본다. 다만 그 지점이 열의 끝(80% 이후)이나
     * 앞(10% 이전)에 있으면, 또는 종점이 기점으로 돌아오지 않으면 편도로 판단한다.
     */
    private fun splitAtTurnaround(
        ordered: List<Map<String, Any?>>, stops: Map<String, Stop>,
    ): List<List<Map<String, Any?>>> {
        fun at(i: Int): Stop? {
            val sid = ordered[i]["stop"] as? String ?: return null
            return stops["G$sid"]
        }
        val first = at(0) ?: return listOf(ordered)
        var far = 0
        var farD = 0.0
        for (i in ordered.indices) {
            val s = at(i) ?: continue
            val d = Geo.haversineMeters(first.lat, first.lon, s.lat, s.lon)
            if (d > farD) { farD = d; far = i }
        }
        if (far >= ordered.size * 0.8 || far <= ordered.size * 0.1) return listOf(ordered)
        val end = at(ordered.size - 1) ?: return listOf(ordered)
        if (Geo.haversineMeters(first.lat, first.lon, end.lat, end.lon) > farD * 0.35) {
            return listOf(ordered)
        }
        return listOf(ordered.subList(0, far + 1), ordered.subList(far, ordered.size))
    }

    // ── 쓰기 ────────────────────────────────────────────────────

    private fun write(
        outFile: File, stops: Map<String, Stop>, routes: Map<String, Route>,
        trips: List<Trip>, speed: SpeedTable, model: SpeedModel, cal: Calibration,
    ) {
        outFile.parentFile?.mkdirs()

        // 배차를 모르는 노선은 같은 유형의 중앙값으로 채운다. 지어내는 게 아니라
        // 같은 데이터 안에서 유추하는 것이고, 빼버리면 그 노선이 통째로 사라진다.
        // ⚠️ 0 을 빼고 센다. 서울 노선 112개가 배차를 0 으로 주는데, 공항버스처럼
        // 그런 노선이 많은 유형은 **중앙값 자체가 0** 이 돼서 대체값이 다시 0 이 된다.
        // 그러면 `headway_secs=0` 짜리가 475건 나오고 GTFS 로선 읽을 수 없는 파일이 된다.
        val positive = { v: Int? -> v?.takeIf { it > 0 } }
        val medianByType = routes.values.mapNotNull { r -> positive(r.hwWeekday)?.let { r.type to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.sorted()[v.size / 2] }
        val allMedian = routes.values.mapNotNull { positive(it.hwWeekday) }.sorted()
            .let { if (it.isEmpty()) 20 else it[it.size / 2] }
        var guessed = 0

        fun headway(r: Route, day: Char): Int {
            val v = positive(when (day) { 'S' -> r.hwSat; 'U' -> r.hwSun; else -> r.hwWeekday })
                ?: positive(r.hwWeekday)
            if (v != null) return v
            guessed++
            return (medianByType[r.type] ?: allMedian).coerceAtLeast(1)
        }

        var measured = 0
        var modelled = 0
        var estimated = 0

        ZipOutputStream(outFile.outputStream().buffered(1 shl 20)).use { zip ->

            entry(zip, "agency.txt",
                "agency_id,agency_name,agency_url,agency_timezone,agency_lang\n" +
                    "chulsegwon,출세권 수집분 (서울·경기 버스),https://klaus9267.github.io/chulsegwon/,Asia/Seoul,ko\n")

            entry(zip, "stops.txt", buildString {
                append("stop_id,stop_name,stop_lat,stop_lon\n")
                for (s in stops.values) {
                    append(s.id).append(',').append(csv(s.name)).append(',')
                        .append(s.lat).append(',').append(s.lon).append('\n')
                }
            })

            entry(zip, "routes.txt", buildString {
                append("route_id,agency_id,route_short_name,route_long_name,route_type\n")
                for (r in routes.values) {
                    // GTFS route_type 3 = 버스.
                    append(r.id).append(",chulsegwon,").append(csv(r.no)).append(',')
                        .append(csv(r.type)).append(",3\n")
                }
            })

            // 요일별 배차가 다르면 서비스를 나눠야 한다. 같으면 DAILY 하나로 묶어
            // stop_times 를 세 번 쓰지 않는다 — 그 파일이 전체 용량의 대부분이다.
            entry(zip, "calendar.txt",
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                    "DAILY,1,1,1,1,1,1,1,20260101,20271231\n" +
                    "WD,1,1,1,1,1,0,0,20260101,20271231\n" +
                    "SA,0,0,0,0,0,1,0,20260101,20271231\n" +
                    "SU,0,0,0,0,0,0,1,20260101,20271231\n")

            val tripRows = StringBuilder("route_id,service_id,trip_id,direction_id\n")
            val stopTimes = StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")
            val freqs = StringBuilder("trip_id,start_time,end_time,headway_secs,exact_times\n")

            for ((i, t) in trips.withIndex()) {
                val r = routes[t.routeId] ?: continue
                val wd = headway(r, 'W'); val sa = headway(r, 'S'); val su = headway(r, 'U')
                val services =
                    if (wd == sa && sa == su) listOf("DAILY" to wd)
                    else listOf("WD" to wd, "SA" to sa, "SU" to su)

                // 상대 시각을 한 번만 만든다. 서비스가 여러 개여도 소요시간은 같다.
                val times = ArrayList<Int>(t.stops.size)
                var sec = 0
                for (k in t.stops.indices) {
                    if (k > 0) {
                        val meters = segmentMeters(t, k, stops, cal.detour)
                        // 이 구간을 실제로 재본 적이 있으면 그걸 쓰고, 없으면 같은
                        // 간격의 구간들이 보통 얼마나 빠른지로 채운다.
                        val kmh = speed.kmh(t.srcRouteId, t.ords[k])?.also { measured++ }
                            ?: model.kmh(t.routeType, meters)?.also { modelled++ }
                            ?: (FALLBACK_KMH[t.routeType] ?: DEFAULT_KMH).also { estimated++ }
                        sec += (meters / (kmh * 1000.0 / 3600.0)).toInt() + cal.dwellSec
                    }
                    times += sec
                }

                for (sv in services) {
                    val tripId = "${t.routeId}_${t.dir}_${i}_${sv.first}"
                    tripRows.append(t.routeId).append(',').append(sv.first).append(',')
                        .append(tripId).append(',').append(t.dir).append('\n')
                    // GTFS 는 stop_times 가 trip_id 로 묶여 있어 운행마다 한 벌씩 필요하다.
                    for (k in t.stops.indices) {
                        val hhmmss = hms(times[k])
                        stopTimes.append(tripId).append(',').append(hhmmss).append(',')
                            .append(hhmmss).append(',').append(t.stops[k]).append(',')
                            .append(k + 1).append('\n')
                    }
                    // exact_times=0 : 시각표가 아니라 "이 간격으로 다닌다"는 뜻
                    freqs.append(tripId).append(',').append(hm(r.first)).append(',')
                        .append(hmAfter(r.last, r.first)).append(',')
                        .append(sv.second * 60).append(",0\n")
                }
            }

            entry(zip, "trips.txt", tripRows.toString())
            entry(zip, "stop_times.txt", stopTimes.toString())
            entry(zip, "frequencies.txt", freqs.toString())
            entry(zip, "transfers.txt", transfers(stops))
        }

        val total = measured + modelled + estimated
        val pct = if (total == 0) 0.0 else measured * 100.0 / total
        println("      구간 소요시간: 실측 ${"%,d".format(measured)} · 곡선 ${"%,d".format(modelled)}" +
            " · 고정표 ${"%,d".format(estimated)} (실측 ${"%.1f".format(pct)}%)")
        if (guessed > 0) println("      배차를 몰라 유형 중앙값으로 채운 횟수 ${"%,d".format(guessed)}")
        println("      -> ${outFile.absolutePath}  ${"%,d".format(outFile.length() / 1024)}KB")
    }

    /**
     * 서울 정류장과 경기 정류장이 사실 같은 자리인 경우를 이어준다.
     *
     * 두 기관의 id 체계가 달라서, 같은 정류장 기둥이 `S105000123` 과 `G228001456` 로
     * 따로 들어 있다. 이걸 안 이어주면 **경기 버스에서 내려 서울 버스로 갈아타는 경로가
     * 아예 안 나온다** — 수도권 통근의 상당수가 그건데.
     *
     * 같은 기관 안의 근접 정류장(길 건너편 등)은 굳이 넣지 않는다. 라우터가 자체
     * 도보 그래프로 알아서 잇고, 넣으면 파일만 몇 배로 커진다.
     */
    private fun transfers(stops: Map<String, Stop>): String {
        val nearM = 60.0
        val walkMps = 1.1
        val cell = 0.0008   // 위도 0.0008° ≈ 89m
        val grid = HashMap<Long, MutableList<Stop>>()
        for (s in stops.values) {
            if (!s.id.startsWith("G")) continue
            val key = (Math.floor(s.lat / cell).toLong() shl 32) xor Math.floor(s.lon / cell).toLong()
            grid.getOrPut(key) { ArrayList(4) } += s
        }
        val sb = StringBuilder("from_stop_id,to_stop_id,transfer_type,min_transfer_time\n")
        var n = 0
        for (s in stops.values) {
            if (!s.id.startsWith("S")) continue
            val gy = Math.floor(s.lat / cell).toLong()
            val gx = Math.floor(s.lon / cell).toLong()
            var best: Stop? = null
            var bestD = nearM
            for (dy in -1..1) for (dx in -1..1) {
                for (o in grid[(((gy + dy) shl 32) xor (gx + dx))] ?: continue) {
                    val d = Geo.haversineMeters(s.lat, s.lon, o.lat, o.lon)
                    if (d < bestD) { bestD = d; best = o }
                }
            }
            val o = best ?: continue
            val t = (bestD / walkMps).toInt() + 30
            // transfer_type=2 : 갈아타려면 최소 이만큼 걸린다
            sb.append(s.id).append(',').append(o.id).append(",2,").append(t).append('\n')
            sb.append(o.id).append(',').append(s.id).append(",2,").append(t).append('\n')
            n++
        }
        println("      서울↔경기 같은 자리 정류장 ${"%,d".format(n)}쌍 연결")
        return sb.toString()
    }

    /**
     * 구간 거리. 서울은 API 가 주고(직선거리와 오차 중앙값 12m), 경기는 좌표로 잰다.
     *
     * API 값이라도 **직선거리보다 짧으면 버린다.** 도로가 직선보다 짧을 수는 없다.
     * 이 검사가 없을 때 799번의 한 구간이 직선 5.6km 를 26초에 가는 걸로 나왔다 —
     * 시속 777km. 값 하나가 틀린 건데 그게 검증기까지 살아남았다.
     */
    private fun segmentMeters(t: Trip, k: Int, stops: Map<String, Stop>, detour: Double): Double {
        val a = stops[t.stops[k - 1]]
        val b = stops[t.stops[k]]
        val straight = if (a != null && b != null)
            Geo.haversineMeters(a.lat, a.lon, b.lat, b.lon) else 0.0
        val given = t.dists.getOrElse(k) { -1 }
        if (given > 0 && given >= straight) return given.toDouble()
        if (a == null || b == null) return 400.0
        return (straight * detour).coerceAtLeast(30.0)
    }

    private fun hms(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)

    /** `0430` → `04:30:00`. */
    private fun hm(v: String): String {
        val s = v.filter { it.isDigit() }.padStart(4, '0').take(4)
        return "${s.substring(0, 2)}:${s.substring(2, 4)}:00"
    }

    /**
     * 막차 시각. 자정을 넘으면 GTFS 는 **25:30:00** 처럼 24를 넘겨 적는다 —
     * 그게 "운행일 기준"이라는 뜻이고, 안 그러면 `end_time < start_time` 이라
     * 검증기가 튕긴다. 실제로 0430 출발 0110 막차인 노선이 있다.
     */
    private fun hmAfter(v: String, after: String): String {
        val a = v.filter { it.isDigit() }.padStart(4, '0').take(4)
        val b = after.filter { it.isDigit() }.padStart(4, '0').take(4)
        var h = a.substring(0, 2).toInt()
        val am = h * 60 + a.substring(2, 4).toInt()
        val bm = b.substring(0, 2).toInt() * 60 + b.substring(2, 4).toInt()
        if (am <= bm) h += 24
        return "%02d:%s:00".format(h, a.substring(2, 4))
    }

    private fun csv(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n'))
            "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"" else v

    private fun entry(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readAll(f: File, mapper: ObjectMapper): List<Map<String, Any?>> {
        if (!f.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return f.readLines().filter { it.isNotBlank() }
            .map { mapper.readValue(it, Map::class.java) as Map<String, Any?> }
    }
}
