package reach

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `frequencies.txt` 를 **명시적 시각표**로 펼친다.
 *
 * **왜 만드나 — 우리 RAPTOR 를 독립 검증하기 위해서다.**
 *
 * 지금 우리의 유일한 바깥 기준은 카카오맵인데, 카카오 대조는 두 가지를 못 가른다:
 * "우리가 만든 GTFS 자료가 틀린 것"과 "우리 RAPTOR 로직이 틀린 것". 둘 다 똑같이
 * "우리 값이 X초 다름"으로 나온다. 그래서 **우리 RAPTOR 는 한 번도 독립적으로
 * 검증된 적이 없다** — 시간축 뒤집기([TransitData.mirrored]), 도보 환승 전파,
 * 라운드 상한 5회, 배차 위상 계산 같은 게 미묘하게 틀려도 안 보인다.
 *
 * 펼친 피드를 우리 RAPTOR 와 OTP2 에 **똑같이** 먹이면 그게 갈린다. 시각이 파일에
 * 박혀 있으니 위상이 같고, 남는 차이는 순수하게 라우팅 로직 차이다.
 *
 * **왜 펼쳐야 하나.** frequency 피드를 그냥 주면 OTP2 는 자기 방식으로 편성을
 * 만든다. 우리는 노선별 해시 위상을 쓴다. 그러면 차이가 로직 탓인지 위상 탓인지
 * 또 못 가른다 — 검증의 목적이 무너진다.
 *
 * **크기.** 정차 약 1,800만 행 · 0.8GB. OTP 에게는 평범한 규모다(국가 단위 피드가
 * 이만하다). 배치 시점에 만들고 버리는 물건이라 저장소에 안 들어간다.
 */
object GtfsExpand {

    /** 재현 가능한 zip. 같은 입력이면 같은 바이트가 나와야 내용 해시로 비교할 수 있다. */
    private const val FIXED_TIME = 1_577_836_800_000L

    private class Stop(val id: String, val arr: Int, val dep: Int, val seq: Int)

    fun run(inFile: File, outFile: File) {
        require(inFile.exists()) { "GTFS 가 없다: ${inFile.absolutePath}" }

        ZipFile(inFile).use { zip ->
            val names = zip.entries().toList().map { it.name }

            fun lines(name: String): List<String> {
                val e = zip.getEntry(name) ?: return emptyList()
                return zip.getInputStream(e).bufferedReader(Charsets.UTF_8)
                    .readLines().filter { it.isNotBlank() }
            }

            // 운행별 정차. 첫 정류장 기준 상대초로 두면 창 시작에 더하기만 하면 된다.
            val stLines = lines("stop_times.txt")
            require(stLines.isNotEmpty()) { "stop_times.txt 가 없다" }
            val stHead = split(stLines[0])
            val cTrip = stHead.indexOf("trip_id")
            val cArr = stHead.indexOf("arrival_time")
            val cDep = stHead.indexOf("departure_time")
            val cStop = stHead.indexOf("stop_id")
            val cSeq = stHead.indexOf("stop_sequence")
            require(cTrip >= 0 && cArr >= 0 && cStop >= 0) { "stop_times.txt 열이 모자라다" }

            val seq = LinkedHashMap<String, MutableList<Stop>>()
            for (i in 1 until stLines.size) {
                val c = split(stLines[i])
                val t = c.getOrNull(cTrip) ?: continue
                val a = hms(c.getOrNull(cArr)) ?: continue
                val d = if (cDep >= 0) (hms(c.getOrNull(cDep)) ?: a) else a
                val s = c.getOrNull(cStop) ?: continue
                val n = if (cSeq >= 0) (c.getOrNull(cSeq)?.toIntOrNull() ?: 0) else 0
                seq.getOrPut(t) { ArrayList(48) } += Stop(s, a, d, n)
            }
            for (v in seq.values) v.sortBy { it.seq }

            // 운행별 창 (start, end, headway)
            val fLines = lines("frequencies.txt")
            require(fLines.isNotEmpty()) { "frequencies.txt 가 없다 — 이미 시각표 피드다" }
            val fHead = split(fLines[0])
            val fTrip = fHead.indexOf("trip_id")
            val fStart = fHead.indexOf("start_time")
            val fEnd = fHead.indexOf("end_time")
            val fHead2 = fHead.indexOf("headway_secs")
            val freq = LinkedHashMap<String, MutableList<IntArray>>()
            for (i in 1 until fLines.size) {
                val c = split(fLines[i])
                val t = c.getOrNull(fTrip) ?: continue
                val s = hms(c.getOrNull(fStart)) ?: continue
                val e = hms(c.getOrNull(fEnd)) ?: continue
                val h = c.getOrNull(fHead2)?.toIntOrNull() ?: continue
                if (h <= 0 || e < s) continue
                freq.getOrPut(t) { ArrayList(4) } += intArrayOf(s, e, h)
            }

            val tLines = lines("trips.txt")
            val tHead = split(tLines[0])
            val tTrip = tHead.indexOf("trip_id")
            require(tTrip >= 0) { "trips.txt 에 trip_id 가 없다" }

            val trips = StringBuilder(1 shl 20).append(tLines[0]).append('\n')
            val times = StringBuilder(1 shl 24)
                .append("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")

            var made = 0L
            var rows = 0L
            var skipped = 0
            for (i in 1 until tLines.size) {
                val cells = split(tLines[i])
                val trip = cells.getOrNull(tTrip) ?: continue
                val stops = seq[trip]
                val wins = freq[trip]
                if (stops == null || stops.size < 2 || wins == null) { skipped++; continue }
                val base = stops[0].arr
                var n = 0
                for (w in wins) {
                    var t = w[0]
                    while (t <= w[1]) {
                        // 원본 id 를 남겨야 어느 패턴에서 나온 편성인지 되짚을 수 있다.
                        val id = "$trip#$n"
                        for (k in cells.indices) {
                            if (k > 0) trips.append(',')
                            trips.append(if (k == tTrip) id else cells[k])
                        }
                        trips.append('\n')
                        for ((k, s) in stops.withIndex()) {
                            times.append(id).append(',')
                                .append(hhmmss(t + s.arr - base)).append(',')
                                .append(hhmmss(t + s.dep - base)).append(',')
                                .append(s.id).append(',').append(k + 1).append('\n')
                            rows++
                        }
                        made++; n++
                        t += w[2]
                    }
                }
            }

            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered(1 shl 20)).use { out ->
                for (name in names) {
                    // 시각표를 박았으니 배차 파일은 빼야 한다. 남겨두면 OTP 가 그걸로
                    // 편성을 **또** 만들어 같은 차가 두 번 다니게 된다.
                    if (name == "frequencies.txt" || name == "stop_times.txt" ||
                        name == "trips.txt") continue
                    val e = zip.getEntry(name) ?: continue
                    out.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                    zip.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("trips.txt").apply { time = FIXED_TIME })
                out.write(trips.toString().toByteArray(Charsets.UTF_8))
                out.closeEntry()
                out.putNextEntry(ZipEntry("stop_times.txt").apply { time = FIXED_TIME })
                out.write(times.toString().toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            println("      운행 ${"%,d".format(made)}개 · 정차 ${"%,d".format(rows)}행" +
                (if (skipped > 0) " · 배차가 없어 뺀 운행 ${skipped}개" else ""))
            println("      -> ${outFile.absolutePath}  ${"%,d".format(outFile.length() / 1024 / 1024)}MB")
        }
    }

    /** 따옴표를 존중하는 CSV 한 줄 쪼개기. 노선명에 쉼표가 들어간다. */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** `25:30:00` 처럼 24를 넘는 표기를 허용한다. */
    private fun hms(v: String?): Int? {
        val p = (v ?: "").split(':')
        if (p.size != 3) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = p[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private fun hhmmss(sec: Int): String =
        "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
}
