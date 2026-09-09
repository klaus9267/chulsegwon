package reach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * 버스 배차의 **시간대 배율**.
 *
 * **왜 필요한가.** 우리 버스 배차는 노선당 하루 한 값이다(서울 `term`, 경기 GBIS
 * `주중배차간격`). 그런데 이 제품이 보는 건 출근·퇴근 시간대다. 출근 피크에
 * 간선버스가 5분으로 다녀도 우리는 하루 평균을 쓴다. 지하철만 시간대별 6구간을
 * 갖고 있다([Headways.WEEKDAY]) — 버스가 노선 수로 40배인데.
 *
 * 어떤 공개 API 도 시간대별 배차를 안 준다. 대신 **운행 대수**는 준다([BusFleet]).
 * `배차 = 왕복시간 ÷ 대수` 이므로 같은 노선을 시각만 바꿔 세면 왕복시간이 약분되고
 * **대수의 비**가 곧 **배차의 역비**다.
 *
 * ```
 * 배율(유형, 시간대) = 그 유형의 전체 중앙대수 ÷ 그 시간대 중앙대수
 * 배차(시간대) = 공시배차 × 배율
 * ```
 *
 * **표본이 모자라면 아무것도 하지 않는다.** 배율 1.0 이 곧 지금 동작이라,
 * 자료가 쌓이기 전에는 결과가 한 바이트도 안 바뀐다.
 */
object BusHeadway {

    /** 지하철과 같은 구간 경계를 쓴다. 같은 화면에 그려지는 값이라 축이 맞아야 한다. */
    val BUCKETS = listOf(
        330 to 420,    // 05:30~07:00
        420 to 540,    // 07:00~09:00 출근 피크
        540 to 1020,   // 09:00~17:00
        1020 to 1200,  // 17:00~20:00 퇴근 피크
        1200 to 1380,  // 20:00~23:00
        1380 to 1480,  // 23:00~24:40
    )

    fun label(i: Int): String {
        val (a, b) = BUCKETS[i]
        return "%02d:%02d~%02d:%02d".format(a / 60, a % 60, (b / 60) % 24, b % 60)
    }

    /** 한 시간대에 표본이 이보다 적으면 그 칸은 안 믿는다. */
    private const val MIN_SAMPLES = 12

    class Profile(
        /** `유형 -> 시간대별 배율`. 없는 유형·칸은 1.0. */
        private val byType: Map<String, DoubleArray>,
        val note: String,
    ) {
        val types get() = byType.keys

        /** [minutes] 시각(자정 기준 분)이 속한 칸의 배율. 모르면 1.0. */
        fun factor(type: String, minutes: Int): Double {
            val a = byType[type] ?: return 1.0
            val i = bucketOf(minutes)
            if (i < 0) return 1.0
            val v = a[i]
            return if (v > 0) v else 1.0
        }

        fun isEmpty() = byType.isEmpty()
    }

    fun bucketOf(minutes: Int): Int {
        val m = if (minutes >= 1440) minutes - 1440 + 1440 else minutes
        for ((i, b) in BUCKETS.withIndex()) if (m >= b.first && m < b.second) return i
        return -1
    }

    fun load(f: File): Profile {
        if (!f.exists()) return Profile(emptyMap(), "프로파일 없음 — 시간대 배율 안 씀")
        return try {
            @Suppress("UNCHECKED_CAST")
            val m = ObjectMapper().readValue(f, Map::class.java) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val t = (m["byType"] as? Map<String, List<Number>>) ?: emptyMap()
            Profile(
                t.mapValues { (_, v) -> DoubleArray(BUCKETS.size) { i -> v.getOrNull(i)?.toDouble() ?: 1.0 } },
                m["note"] as? String ?: f.name,
            )
        } catch (e: Exception) {
            System.err.println("      ! 배차 프로파일을 못 읽었다 (${e.message}) — 안 쓴다")
            Profile(emptyMap(), "읽기 실패")
        }
    }

    /**
     * `fleet` 아래의 스냅샷을 모아 유형별 시간대 배율을 낸다.
     *
     * 파일명이 `yyyyMMdd-HHmm.jsonl` 이라 거기서 시각을 읽는다.
     */
    fun fit(fleetDir: File, out: File) {
        val mapper = ObjectMapper().registerKotlinModule()
        val files = fleetDir.listFiles { f -> f.name.endsWith(".jsonl") }?.sorted() ?: emptyList()
        require(files.isNotEmpty()) { "운행대수 스냅샷이 없다: ${fleetDir.absolutePath}" }

        // 유형 -> 시간대 -> 노선별 대수 목록
        val acc = HashMap<String, Array<MutableList<Int>>>()
        var rows = 0
        for (f in files) {
            val hm = Regex("(\\d{8})-(\\d{2})(\\d{2})").find(f.name) ?: continue
            val minutes = hm.groupValues[2].toInt() * 60 + hm.groupValues[3].toInt()
            val bi = bucketOf(minutes)
            if (bi < 0) continue
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                @Suppress("UNCHECKED_CAST")
                val o = mapper.readValue(line, Map::class.java) as Map<String, Any?>
                val type = o["type"] as? String ?: continue
                val n = (o["n"] as? Number)?.toInt() ?: continue
                acc.getOrPut(type) { Array(BUCKETS.size) { ArrayList() } }[bi] += n
                rows++
            }
        }

        val byType = LinkedHashMap<String, List<Double>>()
        val lines = ArrayList<String>()
        for ((type, buckets) in acc.toSortedMap()) {
            val all = buckets.toList().flatten().sorted()
            if (all.size < MIN_SAMPLES * 2) continue
            val base = all[all.size / 2].toDouble()
            if (base <= 0) continue
            val f = buckets.map { b ->
                if (b.size < MIN_SAMPLES) 1.0
                else {
                    val med = b.sorted()[b.size / 2].toDouble()
                    if (med <= 0) 1.0 else base / med       // 대수가 많으면 배차가 짧다
                }
            }
            byType[type] = f.map { Math.round(it * 100) / 100.0 }
            lines += "  %-4s %s  (표본 %d)".format(
                type, f.joinToString(" ") { "%.2f".format(it) }, all.size)
        }

        val note = "운행대수 스냅샷 ${files.size}개 · ${rows}행 · " +
            "유형 ${byType.size}개에서 배율을 얻음"
        out.parentFile?.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(
            out,
            mapOf(
                "byType" to byType,
                "buckets" to BUCKETS.indices.map { label(it) },
                "note" to note,
                "meaning" to "배차(시간대) = 공시배차 × 이 배율. 1.0 이면 하루 평균 그대로.",
            ),
        )
        println("      $note")
        lines.forEach { println(it) }
        if (byType.isEmpty()) {
            println("      아직 배율을 낼 표본이 없다 — 이 파일이 있어도 결과는 안 바뀐다")
        }
        println("      -> ${out.absolutePath}")
    }
}
