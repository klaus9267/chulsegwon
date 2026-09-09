package reach

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 지하철 망을 GTFS 로 내보낸다.
 *
 * **왜 지금.** [ADR-3] 은 "버스가 들어와 정류장이 5만 개가 되면 직접 짜지 말고
 * R5 로 넘긴다"고 적어뒀다. 지금이 그 시점이고, R5 는 GTFS + OSM 을 먹는다.
 * 버스 GTFS 는 만들었는데 지하철은 아직 GML + 합성 시간표라 못 먹인다.
 *
 * **무엇을 옮기나.** 이 파일은 [TimetableBuilder] 가 만드는 것과 **같은 모델**을
 * GTFS 문법으로 다시 쓴 것이다. 새 추정이 끼어들면 안 된다 — 그 모델은 카카오맵
 * 대조로 편향 −0.9분까지 맞춰놓은 것이라, 여기서 값이 달라지면 그 검증이 무효가 된다.
 * 그래서 구간 소요시간은 [Headways.segmentSeconds], 배차는 [Headways.WEEKDAY] 를
 * 그대로 쓴다.
 *
 * **승강장을 정류장으로, 역을 `parent_station` 으로.**
 * 그래프 노드가 (역, 노선)인 이유([ADR-4])가 여기서도 그대로 산다 — 역 하나로 합치면
 * 2호선↔신분당선 환승 도보시간이 사라져 도달권이 낙관적으로 나온다. GTFS 는 이걸
 * `location_type` 과 `transfers.txt` 로 정확히 표현할 수 있다.
 *
 * **위상 문제는 GTFS 쪽이 오히려 깔끔하다.** 우리 합성 시간표는 구간마다 따로
 * 출발을 찍어서 노선 중간 역이 엉뚱한 시간대의 배차를 물려받는 문제가 있었고,
 * 그걸 위상 정렬로 고쳤다. GTFS `frequencies` 는 **운행 전체를 통째로** k×배차만큼
 * 밀어서 만들기 때문에 그 문제가 애초에 생기지 않는다.
 */
object SubwayGtfs {

    /** 광역철도는 GTFS 로 2(Rail), 도시철도는 1(Subway). 라우터가 환승 규칙을 다르게 준다. */
    private val RAIL_LINES = listOf("경의중앙", "경춘", "수인분당", "경강", "서해", "공항철도")

    fun export(network: Network, outFile: File) {
        outFile.parentFile?.mkdirs()
        val meters = HashMap<Long, Double>(network.trackEdges.size * 2)
        for (e in network.trackEdges) {
            meters[key(e.from, e.to)] = e.meters
            meters[key(e.to, e.from)] = e.meters
        }

        var trips = 0
        var stopTimeRows = 0
        var freqRows = 0

        ZipOutputStream(outFile.outputStream().buffered(1 shl 20)).use { zip ->
            entry(zip, "agency.txt",
                "agency_id,agency_name,agency_url,agency_timezone,agency_lang\n" +
                    "chulsegwon-metro,출세권 수집분 (수도권 전철),https://klaus9267.github.io/chulsegwon/,Asia/Seoul,ko\n")

            // 역(location_type=1)과 승강장(0). 승강장이 실제 정차 지점이고,
            // 같은 역의 승강장끼리는 transfers.txt 가 도보시간을 준다.
            entry(zip, "stops.txt", buildString {
                append("stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station\n")
                for (s in network.stations) {
                    append("ST").append(s.index).append(',').append(csv(s.name)).append(',')
                        .append(s.lat).append(',').append(s.lon).append(",1,\n")
                }
                for (p in network.platforms) {
                    val st = network.stations[p.stationIndex]
                    append("PF").append(p.index).append(',')
                        .append(csv("${st.name} ${p.line}")).append(',')
                        .append(p.lat).append(',').append(p.lon).append(",0,ST")
                        .append(p.stationIndex).append('\n')
                }
            })

            val lines = network.platforms.map { it.line }.distinct().sorted()
            entry(zip, "routes.txt", buildString {
                append("route_id,agency_id,route_short_name,route_long_name,route_type\n")
                for (l in lines) {
                    val type = if (RAIL_LINES.any { l.contains(it) }) 2 else 1
                    append("L").append(safe(l)).append(",chulsegwon-metro,").append(csv(l))
                        .append(',').append(csv(l)).append(',').append(type).append('\n')
                }
            })

            entry(zip, "calendar.txt",
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                    "WD,1,1,1,1,1,0,0,20260101,20271231\n")

            val tripRows = StringBuilder("route_id,service_id,trip_id,direction_id\n")
            val stopTimes = StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")
            val freqs = StringBuilder("trip_id,start_time,end_time,headway_secs,exact_times\n")

            var loopTrips = 0
            for ((line, chains) in network.lineSequences()) {
                val multiplier = Headways.multiplierFor(line)
                // 체인마다, 그리고 그 안에 순환 구간이 있으면 두 바퀴짜리를 하나 더.
                val runs = ArrayList<Pair<String, List<Int>>>()
                for ((ci, chain) in chains.withIndex()) {
                    if (chain.size < 2) continue
                    runs += "$ci" to chain
                    val loop = circularPart(chain)
                    if (loop != null) {
                        // 두 바퀴. 절단점을 가로지르는 통행이 여기서 표현된다.
                        runs += "${ci}c" to (loop + loop.drop(1))
                        loopTrips++
                    }
                }
                for ((ci, chain) in runs) {
                    for ((di, dir) in listOf(chain, chain.asReversed()).withIndex()) {
                        val tripId = "L${safe(line)}_${ci}_$di"
                        tripRows.append("L").append(safe(line)).append(",WD,")
                            .append(tripId).append(',').append(di).append('\n')

                        var sec = 0
                        for ((k, pf) in dir.withIndex()) {
                            if (k > 0) {
                                val m = meters[key(dir[k - 1], pf)] ?: 900.0
                                sec += Headways.segmentSeconds(m)
                            }
                            val t = hms(sec)
                            stopTimes.append(tripId).append(',').append(t).append(',')
                                .append(t).append(",PF").append(pf).append(',')
                                .append(k + 1).append('\n')
                            stopTimeRows++
                        }

                        for (p in Headways.WEEKDAY) {
                            val hw = (p.headwaySec * multiplier).toInt().coerceAtLeast(60)
                            freqs.append(tripId).append(',').append(hms(p.startSec)).append(',')
                                .append(hms(p.endSec)).append(',').append(hw).append(",1\n")
                            freqRows++
                        }
                        trips++
                    }
                }
            }

            if (loopTrips > 0) println("      순환 구간을 두 바퀴로 따로 내보낸 노선 ${loopTrips}개")
            entry(zip, "trips.txt", tripRows.toString())
            entry(zip, "stop_times.txt", stopTimes.toString())
            entry(zip, "frequencies.txt", freqs.toString())

            // 같은 역 안의 노선 간 환승. 이게 없으면 라우터가 환승을 공짜로 본다.
            entry(zip, "transfers.txt", buildString {
                append("from_stop_id,to_stop_id,transfer_type,min_transfer_time\n")
                for (t in network.transferEdges) {
                    append("PF").append(t.from).append(",PF").append(t.to)
                        .append(",2,").append(t.walkSeconds).append('\n')
                }
            })
        }

        println("      역 ${"%,d".format(network.stations.size)} · 승강장 ${"%,d".format(network.platforms.size)}" +
            " · 노선 ${network.platforms.map { it.line }.distinct().size}")
        println("      운행 ${"%,d".format(trips)} · 정차 ${"%,d".format(stopTimeRows)}" +
            " · 배차 ${"%,d".format(freqRows)} · 환승 ${"%,d".format(network.transferEdges.size)}")
        println("      -> ${outFile.absolutePath}  ${"%,d".format(outFile.length() / 1024)}KB")
    }

    /**
     * 체인 안의 순환 구간을 찾는다. 없으면 null.
     *
     * 같은 노드가 두 번 나오면 그 사이가 한 바퀴다. 2호선 체인은
     * `까치산…도림천 [신도림 …한 바퀴… 신도림]` 이라 신도림이 두 번 나온다.
     */
    private fun circularPart(chain: List<Int>): List<Int>? {
        val seen = HashMap<Int, Int>(chain.size * 2)
        for ((i, v) in chain.withIndex()) {
            val first = seen.putIfAbsent(v, i)
            // 한 바퀴로 치려면 충분히 길어야 한다. 2~3개짜리 되돌이는 순환이 아니다.
            if (first != null && i - first >= 8) return chain.subList(first, i + 1)
        }
        return null
    }

    private fun key(a: Int, b: Int): Long = a.toLong() * 1_000_000L + b

    /** GTFS id 에 쓸 수 있게 정리. 노선명에 한글·공백이 섞여 있다. */
    private fun safe(v: String) = v.replace(Regex("[^0-9A-Za-z가-힣]"), "")

    private fun hms(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)

    private fun csv(v: String): String =
        if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * zip 항목 시각을 고정한다.
     *
     * 안 그러면 **내용이 같아도 파일 바이트가 매번 달라진다** — 항목마다 현재 시각이
     * 박히기 때문이다. 그러면 "GTFS 가 바뀌었나"를 해시로 판단할 수 없고, 하루 다섯 번
     * 도는 스케줄이 매번 4분짜리 행렬 재생성을 헛돌린다. 릴리스 자산도 내용이 같은데
     * 매번 새 파일로 올라간다.
     *
     * 재현 가능한 산출물의 표준 관행이기도 하다.
     */
    private const val FIXED_TIME = 1_577_836_800_000L   // 2020-01-01T00:00:00Z

    private fun entry(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
