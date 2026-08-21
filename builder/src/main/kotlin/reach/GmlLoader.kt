package reach

import java.io.File

/**
 * stripe2933/SeoulMetropolitanSubway 의 GML 그래프를 읽는다.
 *
 * 이 파일이 주는 것은 **노선별 역 순서(인접 관계)** 이고, 그게 직접 만들기 제일 번거로운
 * 부분이다. 좌표도 함께 들어 있다. 소요시간은 없으므로 [TimetableBuilder]가 거리에서 추정한다.
 *
 * ⚠️ 기준 2020년. GTX-A·신림선·대곡소사선·별내선 등이 빠져 있다.
 * KTDB GTFS 가 도착하면 이 로더 대신 GtfsSource 를 쓰면 된다.
 */
object GmlLoader {

    /** 같은 이름이어도 이 거리보다 멀면 다른 역으로 본다 (5호선 양평 vs 경의중앙선 양평). */
    private const val SAME_STATION_MAX_METERS = 1_000.0

    /** 개찰·계단·통로 등 거리로 안 잡히는 고정 비용. */
    private const val DEFAULT_TRANSFER_OVERHEAD_SEC = 90

    /** 환승 통로 보행속도. 계단·인파를 감안해 지상 보행보다 느리게 잡는다. */
    private const val TRANSFER_WALK_MPS = 1.2

    fun load(file: File, transferOverheadSec: Int = DEFAULT_TRANSFER_OVERHEAD_SEC): Network {
        val nodes = mutableListOf<RawNode>()
        val rawEdges = mutableListOf<Triple<Int, Int, String>>()

        var block: String? = null
        val cur = HashMap<String, MutableList<String>>()

        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line == "node [" -> { block = "node"; cur.clear() }
                line == "edge [" -> { block = "edge"; cur.clear() }
                line == "]" && block != null -> {
                    when (block) {
                        "node" -> nodes += parseNode(cur)
                        "edge" -> rawEdges += Triple(
                            cur["source"]!!.first().toInt(),
                            cur["target"]!!.first().toInt(),
                            cur["line_no"]?.first()?.trim('"') ?: "",
                        )
                    }
                    block = null
                    cur.clear()
                }
                block != null -> {
                    val idx = line.indexOf(' ')
                    if (idx > 0) {
                        cur.getOrPut(line.substring(0, idx)) { mutableListOf() }
                            .add(line.substring(idx + 1).trim())
                    }
                }
            }
        }

        nodes.sortBy { it.id }
        require(nodes.withIndex().all { (i, n) -> n.id == i }) { "GML node id 가 0..n-1 연속이 아니다" }

        val stations = groupIntoStations(nodes)
        val stationOf = IntArray(nodes.size) { -1 }
        for (st in stations) for (p in st.platforms) stationOf[p] = st.index

        val platforms = nodes.map { Platform(it.id, stationOf[it.id], it.line, it.code, it.lat, it.lon) }

        // line_no 가 빈 간선은 선로가 아니라 환승 통로다. 이걸 선로로 넣으면
        // (a) 노선이 잘게 조각나고 (b) 환승이 '열차'로 처리돼 실제보다 빨라진다.
        val trackEdges = rawEdges
            .filter { (_, _, line) -> line.isNotEmpty() }
            .map { (a, b, line) ->
                TrackEdge(a, b, line, Geo.haversineMeters(nodes[a].lat, nodes[a].lon, nodes[b].lat, nodes[b].lon))
            }

        // 환승은 같은 역 안 모든 승강장 쌍에 대해 만든다. 도보시간은 실제 거리에서 뽑는다.
        val transferEdges = buildList {
            for (st in stations) {
                for (a in st.platforms) for (b in st.platforms) {
                    if (a == b) continue
                    val meters = Geo.haversineMeters(nodes[a].lat, nodes[a].lon, nodes[b].lat, nodes[b].lon)
                    add(TransferEdge(a, b, transferSeconds(meters, transferOverheadSec)))
                }
            }
        }

        return Network(stations, platforms, trackEdges, transferEdges)
    }

    fun transferSeconds(meters: Double, overheadSec: Int): Int =
        (overheadSec + meters / TRANSFER_WALK_MPS).toInt().coerceAtLeast(60)

    private fun parseNode(cur: Map<String, MutableList<String>>): RawNode {
        val pos = cur["pos"] ?: error("node 에 pos 가 없다")
        return RawNode(
            id = cur["id"]!!.first().toInt(),
            code = cur["label"]!!.first().trim('"'),
            line = cur["line_no"]!!.first().trim('"'),
            name = decodeEntities(cur["station_name"]!!.first().trim('"')),
            lon = pos[0].toDouble(),
            lat = pos[1].toDouble(),
        )
    }

    /**
     * 이름이 같고 서로 가까운 승강장들을 한 역으로 묶는다.
     * 이름만으로 묶으면 5호선 양평역과 경의중앙선 양평역(약 53km 거리)이 합쳐져 그래프가 망가진다.
     */
    private fun groupIntoStations(nodes: List<RawNode>): List<Station> {
        val stations = mutableListOf<Station>()
        for ((name, group) in nodes.groupBy { it.name }) {
            val clusters = mutableListOf<MutableList<RawNode>>()
            for (n in group) {
                val hit = clusters.firstOrNull { c ->
                    c.any { Geo.haversineMeters(it.lat, it.lon, n.lat, n.lon) <= SAME_STATION_MAX_METERS }
                }
                if (hit != null) hit += n else clusters += mutableListOf(n)
            }
            for (c in clusters) {
                val st = Station(stations.size, name, c.map { it.lat }.average(), c.map { it.lon }.average())
                c.forEach { st.platforms += it.id }
                stations += st
            }
        }
        return stations
    }

    /** GML 안의 한글이 &#49548; 형태로 인코딩되어 있다. */
    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("&#", i)) {
                val end = s.indexOf(';', i)
                if (end > 0) { out.appendCodePoint(s.substring(i + 2, end).toInt()); i = end + 1; continue }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    private class RawNode(
        val id: Int, val code: String, val line: String,
        val name: String, val lat: Double, val lon: Double,
    )
}
