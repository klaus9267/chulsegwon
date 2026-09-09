package reach

import java.io.File

/**
 * OSM 에서 **수도권 전철 노선망**을 뽑는다. [GmlLoader] 를 대신한다.
 *
 * ## 왜 갈아탔나
 *
 * `data/raw/metro_graph.gml` 은 2020-12 기준이라 그 뒤에 열린 것이 통째로 없었다 —
 * GTX-A, 신림선, 대곡소사선(서해선 대곡~일산), 별내선, 4호선 진접선, 7호선 석남연장,
 * 5호선 하남연장, 1호선 연천연장, 인천1호선 검단연장. [ADR-37] 의 카카오 대조에서
 * "일산→온수동 +18분"이 정확히 **대곡소사선이 없어서**였다.
 *
 * OSM 은 이미 로컬에 있고(`data/raw/osm/south-korea.osm.pbf`, Geofabrik, ODbL),
 * 역은 태그 붙은 노드로, 노선은 릴레이션으로 들어 있다. 세어보니:
 *
 * - 수도권에 걸친 철도 노선 릴레이션 251개 · 정차 5,971개
 * - 이름 없는 정차 **4개(0.1%)** · 멤버인데 노드를 못 찾은 것 **0개**
 * - 2020년 이후 개통분 9종을 전수 확인해 **전부 있음**
 *
 * ## 뜻밖의 소득 — 급행이 릴레이션으로 들어 있다
 *
 * OSM 은 운행 계통마다 릴레이션을 따로 둔다. 그래서 **정차 패턴이 그대로 나온다**:
 * `수인·분당선 급행: 왕십리 → 고색`(29정차, 일반은 37), `1호선: 용산 → 동인천 특급`(11정차),
 * `4호선: 불암산 → 오이도 급행`(42정차), `9호선 급행`(17정차).
 *
 * 1~9호선은 [RailTimetable] 의 실측 시각표가 덮지만, **경의중앙·수인분당·경춘·서해·
 * 신분당은 실측 시각표가 공개돼 있지 않다.** 그쪽은 이 패턴에 [Headways] 의 합성
 * 시각을 입혀 쓴다. 급행을 아예 모르던 것보다 낫다.
 *
 * ## 급행은 선로 그래프에 안 넣는다
 *
 * [Network.trackEdges] 는 "인접한 두 역"이어야 한다. 급행 릴레이션의 연속 정차는
 * 역을 건너뛰므로, 그걸 선로로 넣으면 노선 그래프가 경로가 아니게 되고
 * [Network.lineRuns] 의 종점 추적이 무너진다. 급행은 **패턴으로만** 쓴다.
 */
object RailOsm {

    /** 수도권. 이보다 넓게 잡으면 부산·대구 지하철이 섞여 들어온다. */
    private const val MIN_LAT = 36.90
    private const val MAX_LAT = 38.15
    private const val MIN_LON = 126.30
    private const val MAX_LON = 127.90

    private val ROUTE_KINDS = setOf("subway", "light_rail", "train")

    /**
     * 쓸 노선. **허용목록으로 간다.**
     *
     * `network=수도권 전철` 로 거르면 우이신설선이 빠지고(태그가 비어 있다) KTX·ITX·
     * 무궁화·새마을·공항철도 직통열차가 섞여 들어온다. 그것들은 요금 체계가 다르고
     * (직통열차는 별도 운임, KTX 는 예약제) 정차가 3~7개뿐이라, 넣으면 도달권이
     * "서울역에서 4정거장 만에 부산" 같은 모양이 된다.
     *
     * 값은 우리 쪽 노선 이름이다. [Headways.multiplierFor] 가 이 이름으로 배차
     * 배율을 찾고, [RailTimetable] 이 "1".."9" 로 실측 시각표를 붙인다.
     */
    private val LINE_OF = mapOf(
        "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5",
        "6" to "6", "7" to "7", "8" to "8", "9" to "9",
        "경의·중앙" to "경의중앙",
        "수인·분당" to "수인분당",
        "경춘" to "경춘",
        "경강" to "경강",
        "서해" to "서해",
        "신분당" to "신분당",
        "공항철도" to "공항철도",
        "인천1" to "인천1",
        "I2" to "인천2",
        "U" to "의정부",
        "W" to "우이신설",
        "김포 골드라인" to "김포골드",
        "Silim" to "신림",
        "GTX-A" to "GTX-A",
        "용인" to "용인",
    )

    /** 같은 이름이어도 이 거리보다 멀면 다른 역으로 본다. [GmlLoader] 와 같은 규칙. */
    private const val SAME_STATION_MAX_METERS = 1_000.0

    /**
     * OSM 역명에서 **괄호 안 부역명을 뗀다.**
     *
     * OSM 은 `교대(법원·검찰청)`, `총신대입구 (이수)`, `대림(구로구청)` 처럼 부역명을
     * 붙여 쓰는데, **같은 역인데 노선마다 붙이기도 하고 안 붙이기도 한다.**
     * 이름으로 역을 묶는 우리 규칙에서는 그게 곧 역이 쪼개진다는 뜻이고,
     * 쪼개지면 **환승이 통째로 사라진다** — 실제로 교대(2↔3호선)와 대림(2↔7호선)이
     * 두 역으로 갈라져 있었다.
     *
     * 가운뎃점만 든 이름(`전대·에버랜드`, `시청·용인대`)은 부역명이 아니라 정식
     * 이름이라 건드리지 않는다.
     */
    private fun cleanName(v: String): String {
        val i = v.indexOf('(')
        val base = (if (i > 0) v.substring(0, i) else v).trim()
        return base.ifEmpty { v.trim() }
    }

    class Stop(val nodeId: Long, val name: String, val lat: Double, val lon: Double)

    class Route(
        val relId: Long,
        val name: String,
        val ref: String,
        val kind: String,
        val network: String,
        val memberNodes: LongArray,
    ) {
        /**
         * 급행·특급 계통인가. 이름으로 판별한다 — OSM 에 별도 태그가 없다.
         *
         * ⚠️ `수도권 광역급행철도 A선`(GTX-A)은 **노선 이름에 "급행"이 들어 있을 뿐
         * 급행 계통이 아니다.** 그대로 두면 GTX-A 가 통째로 급행으로 잡혀 선로
         * 그래프에서 빠진다.
         */
        val express get() = ("급행" in name || "특급" in name) && "광역급행철도" !in name
    }

    /** 한 운행 계통. 정차는 [Network.platforms] 색인이다. */
    class Pattern(
        val line: String,
        val name: String,
        val express: Boolean,
        val stops: IntArray,
        /**
         * 방향. [serviceSet] 이 채운다(그 전에는 0).
         *
         * **배차를 나눌 때 반드시 방향을 갈라야 한다.** 한 승강장에 상행 2개·하행 2개가
         * 서면 계통이 4개지만, 어느 한 방향으로 가려는 사람이 탈 수 있는 건 2개뿐이다.
         * 4로 세면 기다리는 시간이 두 배가 된다.
         */
        val dir: Int = 0,
    )

    /**
     * PBF 를 두 번 훑는다.
     *
     * 한 번에 못 하는 이유는 **릴레이션이 노드보다 뒤에 나오기 때문**이다. 어떤 노드가
     * 필요한지는 릴레이션을 다 읽어야 알 수 있고, 그때는 노드가 이미 지나갔다.
     * 노드를 전부 들고 있으면 남한 PBF 가 수천만 개라 힙이 터진다.
     */
    fun load(pbf: File): Pair<List<Route>, Map<Long, Stop>> {
        val routes = ArrayList<Route>(512)
        var relSeen = 0L
        OsmPbf.read(
            pbf, wantNodes = false, wantWays = false, wantRelations = true,
            onRelation = { r ->
                relSeen++
                if (r.tags["type"] != "route") return@read
                val kind = r.tags["route"] ?: return@read
                if (kind !in ROUTE_KINDS) return@read
                val ids = ArrayList<Long>(64)
                for (i in r.memberIds.indices) {
                    // 노드 멤버 중 역할이 stop* 인 것만. platform 은 승강장 폴리곤이라
                    // 순서가 stop 과 겹치고, 웨이인 경우도 많아 좌표를 바로 못 준다.
                    if (r.memberTypes[i] != 0) continue
                    if (!r.memberRoles[i].startsWith("stop")) continue
                    ids += r.memberIds[i]
                }
                if (ids.size < 2) return@read
                routes += Route(
                    r.id, r.tags["name"] ?: "", r.tags["ref"] ?: "", kind,
                    r.tags["network"] ?: "", ids.toLongArray(),
                )
            },
        )

        val want = HashSet<Long>(routes.sumOf { it.memberNodes.size } * 2)
        for (r in routes) for (n in r.memberNodes) want += n

        val stops = HashMap<Long, Stop>(want.size * 2)
        OsmPbf.read(
            pbf, wantNodes = false, wantWays = false, wantNodeTags = true,
            onTaggedNode = { n ->
                if (n.id !in want) return@read
                val raw = n.tags["name:ko"] ?: n.tags["name"] ?: ""
                stops[n.id] = Stop(n.id, cleanName(raw), n.lat, n.lon)
            },
        )
        println("      OSM 릴레이션 ${"%,d".format(relSeen)}개 중 철도 노선 ${routes.size}개")
        return routes to stops
    }

    private fun inBox(lat: Double, lon: Double) =
        lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON

    /**
     * 망과 운행 계통을 만든다.
     *
     * 승강장은 `(역, 노선)` 이다 — [ADR-4] 의 이유가 그대로 산다. 역으로 합치면
     * 2호선↔신분당선 환승 도보시간이 사라져 도달권이 낙관적으로 나온다.
     */
    fun build(pbf: File, transferOverheadSec: Int = 90): Pair<Network, List<Pattern>> {
        val (routes, stops) = load(pbf)
        val used = routes.filter { r ->
            LINE_OF.containsKey(r.ref) &&
                r.memberNodes.any { stops[it]?.let { s -> inBox(s.lat, s.lon) } == true }
        }
        val skippedRefs = routes.map { it.ref }.distinct().filter { it !in LINE_OF }
        println("      쓰는 노선 릴레이션 ${used.size}개 · " +
            "허용목록 밖이라 뺀 ref ${skippedRefs.size}종")

        // ── 1) 역 묶기 ────────────────────────────────────────────────
        // 노선마다 정차 노드가 따로라, 같은 역이라도 노드가 여럿이다.
        // 이름이 같고 가까운 것끼리 한 역으로 본다([GmlLoader] 와 같은 규칙).
        val nodeLine = HashMap<Long, MutableSet<String>>(4096)
        for (r in used) {
            val line = LINE_OF[r.ref]!!
            for (n in r.memberNodes) nodeLine.getOrPut(n) { HashSet(2) } += line
        }
        val nodes = nodeLine.keys.mapNotNull { stops[it] }.filter { it.name.isNotEmpty() }
        val stations = ArrayList<Station>(1024)
        val stationOfNode = HashMap<Long, Int>(nodes.size * 2)
        for ((name, group) in nodes.groupBy { it.name }) {
            val clusters = ArrayList<MutableList<Stop>>(2)
            for (n in group) {
                val hit = clusters.firstOrNull { c ->
                    c.any { Geo.haversineMeters(it.lat, it.lon, n.lat, n.lon) <= SAME_STATION_MAX_METERS }
                }
                if (hit != null) hit += n else clusters += mutableListOf(n)
            }
            for (c in clusters) {
                val st = Station(stations.size, name, c.map { it.lat }.average(), c.map { it.lon }.average())
                stations += st
                for (n in c) stationOfNode[n.nodeId] = st.index
            }
        }

        // ── 2) 승강장 = (역, 노선) ─────────────────────────────────────
        val platformOf = HashMap<Long, Int>(nodes.size * 2)      // (역<<8 | 노선) 대신 문자열 키
        val platKey = HashMap<String, Int>(nodes.size * 2)
        val platforms = ArrayList<Platform>(1024)
        val platLat = ArrayList<MutableList<Double>>(1024)
        val platLon = ArrayList<MutableList<Double>>(1024)
        for (n in nodes) {
            val si = stationOfNode[n.nodeId] ?: continue
            for (line in nodeLine[n.nodeId] ?: emptySet<String>()) {
                val key = "$si$line"
                val pi = platKey.getOrPut(key) {
                    platforms += Platform(platforms.size, si, line, "", n.lat, n.lon)
                    platLat += ArrayList<Double>(2); platLon += ArrayList<Double>(2)
                    stations[si].platforms += platforms.size - 1
                    platforms.size - 1
                }
                platLat[pi] += n.lat; platLon[pi] += n.lon
            }
        }
        // 승강장 좌표는 그 노선 정차 노드들의 평균으로 다시 잡는다.
        val fixedPlatforms = platforms.mapIndexed { i, p ->
            Platform(p.index, p.stationIndex, p.line, p.code, platLat[i].average(), platLon[i].average())
        }
        // 노드 → 승강장 (노선마다 다르므로 패턴을 만들 때 노선과 함께 찾는다)
        fun platOf(nodeId: Long, line: String): Int? {
            val si = stationOfNode[nodeId] ?: return null
            return platKey["$si$line"]
        }

        // ── 3) 운행 계통 ──────────────────────────────────────────────
        val patterns = ArrayList<Pattern>(used.size)
        for (r in used) {
            val line = LINE_OF[r.ref]!!
            val seq = ArrayList<Int>(r.memberNodes.size)
            for (n in r.memberNodes) {
                val p = platOf(n, line) ?: continue
                if (seq.isNotEmpty() && seq.last() == p) continue   // 같은 역 연속(회차)
                seq += p
            }
            if (seq.size < 2) continue
            patterns += Pattern(line, r.name, r.express, seq.toIntArray())
        }

        // ── 4) 선로 ──────────────────────────────────────────────────
        // **급행은 뺀다.** 역을 건너뛴 구간을 선로로 넣으면 노선 그래프가 경로가
        // 아니게 되고 [Network.lineRuns] 의 종점 추적이 무너진다.
        val edgeSeen = HashSet<Long>(8192)
        val trackEdges = ArrayList<TrackEdge>(4096)
        for (p in patterns) {
            if (p.express) continue
            for (i in 1 until p.stops.size) {
                val a = p.stops[i - 1]; val b = p.stops[i]
                if (a == b) continue
                val k = if (a < b) a.toLong() * 1_000_000 + b else b.toLong() * 1_000_000 + a
                if (!edgeSeen.add(k)) continue
                val pa = fixedPlatforms[a]; val pb = fixedPlatforms[b]
                trackEdges += TrackEdge(a, b, p.line,
                    Geo.haversineMeters(pa.lat, pa.lon, pb.lat, pb.lon))
            }
        }

        // ── 5) 환승 ──────────────────────────────────────────────────
        val transferEdges = ArrayList<TransferEdge>(4096)
        for (st in stations) {
            for (a in st.platforms) for (b in st.platforms) {
                if (a == b) continue
                val pa = fixedPlatforms[a]; val pb = fixedPlatforms[b]
                val m = Geo.haversineMeters(pa.lat, pa.lon, pb.lat, pb.lon)
                transferEdges += TransferEdge(a, b, GmlLoader.transferSeconds(m, transferOverheadSec))
            }
        }

        val net = Network(stations, fixedPlatforms, trackEdges, transferEdges)
        println("      역 ${"%,d".format(stations.size)} / 승강장 ${"%,d".format(fixedPlatforms.size)}" +
            " / 구간 ${"%,d".format(trackEdges.size)} / 환승 ${"%,d".format(transferEdges.size)}")
        println("      운행 계통 ${patterns.size}개 (그중 급행·특급 ${patterns.count { it.express }}개) · " +
            "노선 ${patterns.map { it.line }.distinct().size}종")

        // 한쪽 방향으로만 서는 승강장을 센다. 이게 늘면 방향 판정이나 OSM 쪽에
        // 구멍이 생긴 것이다 — GTX-A 동탄 방면이 통째로 사라진 적이 있다.
        val sel = serviceSet(patterns)
        val dirsOf = HashMap<Int, MutableSet<Int>>(2048)
        for (p in sel) for (s in p.stops) dirsOf.getOrPut(s) { HashSet(2) } += p.dir
        val oneWay = dirsOf.filterValues { it.size < 2 }.keys
        if (oneWay.isNotEmpty()) {
            // 6호선 응암순환(구산·역촌·불광·독바위·연신내)은 **실제로 편도**라
            // 여기 나오는 게 맞다. 그 밖의 것이 늘면 방향 판정이나 OSM 쪽 구멍이다.
            println("      한 방향으로만 서는 승강장 ${oneWay.size}개(6호선 응암순환 5개 포함): " +
                oneWay.take(10).joinToString(" · ") {
                    "${stations[fixedPlatforms[it].stationIndex].name} ${fixedPlatforms[it].line}"
                })
        }
        return net to patterns
    }

    /**
     * 노선을 덮는 **최소 계통 집합**을 고른다.
     *
     * ## 왜 전부 안 쓰나
     *
     * OSM 은 운행 계통을 아주 잘게 나눠 적는다 — 수인분당선이 24개, 1호선이 87개다.
     * 그건 "이런 열차가 존재한다"는 목록이지 **동시에 그만큼 다닌다는 뜻이 아니다.**
     * `죽전 → 청량리` 와 `왕십리 → 인천` 은 같은 열차가 구간만 다른 경우가 많다.
     *
     * 전부에 배차를 나눠주면 계통당 배차가 288분까지 벌어진다. 본선에서는 겹쳐서
     * 합이 맞지만, **계통 두셋만 서는 지선 끝에서는 두 시간을 기다리게 된다.**
     * 실제로는 거기도 10~20분이면 온다.
     *
     * ## 어떻게
     *
     * 방향별로, 긴 계통부터 집어가며 **새 승강장을 하나도 안 더하는 계통은 버린다.**
     * 그러면 노선 전체를 덮는 가장 적은 수만 남는다 — 실제 운행 계통(본선 + 지선
     * 몇 개 + 급행)에 가깝다. 남은 것들에 노선 배차를 나눠주면 지선도 제 값이 된다.
     *
     * 방향은 **가장 긴 계통을 기준으로 정한다.** 공통 승강장이 같은 순서로 나오면
     * 같은 방향, 뒤집혀 있으면 반대다. 이름의 화살표를 읽지 않는 이유는 OSM 이름이
     * 자유 문자열이라서다.
     */
    fun serviceSet(patterns: List<Pattern>): List<Pattern> {
        val out = ArrayList<Pattern>(patterns.size)
        for ((_, group) in patterns.groupBy { it.line }) {
            for ((_, byKind) in group.groupBy { it.express }) {
                for (comp in components(byKind)) {
                val sorted = comp.sortedByDescending { it.stops.size }
                val ref = sorted.first()
                val pos = HashMap<Int, Int>(ref.stops.size * 2)
                for ((i, s) in ref.stops.withIndex()) pos.putIfAbsent(s, i)
                val dirs = Array(2) { ArrayList<Pattern>(sorted.size) }
                val placed = ArrayList<Pair<Pattern, Int>>(sorted.size)
                for (p in sorted) {
                    // ① 이미 자리를 잡은 것 중에 **정확히 뒤집힌 짝**이 있으면 반대 방향이다.
                    //    기준 계통과 겹치는 승강장이 없는 토막(경의중앙 임진강~도라산)은
                    //    ② 로는 판정이 안 돼 둘 다 0 으로 몰리고, 그러면 덮기에서
                    //    **한 방향이 통째로 사라진다.**
                    val rev = placed.firstOrNull { (q, _) ->
                        q.stops.size == p.stops.size &&
                            q.stops.indices.all { q.stops[it] == p.stops[p.stops.size - 1 - it] }
                    }
                    val d = if (rev != null) 1 - rev.second else {
                        // ② 기준 계통과 공통 승강장의 **순서**를 견준다.
                        var same = 0
                        var flip = 0
                        var last = -1
                        for (s in p.stops) {
                            val i = pos[s] ?: continue
                            if (last >= 0) { if (i > last) same++ else if (i < last) flip++ }
                            last = i
                        }
                        if (flip > same) 1 else 0
                    }
                    dirs[d] += p
                    placed += p to d
                }
                val kept = ArrayList<Pattern>(sorted.size)
                for ((di, d) in dirs.withIndex()) {
                    val covered = HashSet<Int>(256)
                    for (p in d) {
                        if (p.stops.all { it in covered }) continue   // 새로 덮는 게 없다
                        covered += p.stops.toList()
                        kept += Pattern(p.line, p.name, p.express, p.stops, di)
                    }
                }
                // **남긴 계통의 정확한 반대편은 반드시 같이 남긴다.**
                // 덮기만으로 자르면 짧은 왕복 구간에서 한 방향이 사라진다 —
                // 경의중앙 임진강↔도라산이 그랬다. 갈 수는 있는데 올 수는 없는 역이 생긴다.
                for ((di, d) in dirs.withIndex()) {
                    for (p in d) {
                        if (kept.any { it.name == p.name && it.dir == di }) continue
                        val isRev = kept.any { q ->
                            q.dir != di && q.stops.size == p.stops.size &&
                                q.stops.indices.all { q.stops[it] == p.stops[p.stops.size - 1 - it] }
                        }
                        if (isRev) kept += Pattern(p.line, p.name, p.express, p.stops, di)
                    }
                }
                out += kept
                }
            }
        }
        return out
    }

    /**
     * 승강장을 하나라도 나누는 계통끼리 묶는다.
     *
     * **왜 필요한가.** [serviceSet] 은 가장 긴 계통을 기준으로 방향을 정하는데,
     * 기준과 겹치는 승강장이 하나도 없으면 견줄 수가 없어 방향이 0 으로 몰린다.
     * 그 상태로 덮기를 돌리면 반대 방향이 "새로 덮는 게 없다"고 버려진다 —
     * **한 방향이 통째로 사라진다.**
     *
     * GTX-A 가 실제로 그랬다. 운정~서울역과 수서~동탄은 아직 안 이어져 있어서
     * `동탄 → 수서` 가 사라졌다. 노선 하나가 떨어진 두 토막인 경우는 앞으로도
     * 생긴다(연장 개통 전 구간이 대개 그렇다).
     */
    private fun components(ps: List<Pattern>): List<List<Pattern>> {
        val parent = IntArray(ps.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        val owner = HashMap<Int, Int>(1024)          // 승강장 → 처음 본 계통
        for ((i, p) in ps.withIndex()) for (s in p.stops) {
            val prev = owner.putIfAbsent(s, i)
            if (prev != null) union(i, prev)
        }
        return ps.indices.groupBy { find(it) }.values.map { g -> g.map { ps[it] } }
    }

    /**
     * 세어서 보여준다. 갈아끼우기 전에 **자료가 실제로 뭘 담고 있는지** 보는 단계다.
     *
     * 특히 2020년 이후 개통분이 있는지를 이름으로 직접 찾는다. 없으면 OSM 으로
     * 갈아끼울 이유가 없고, 있으면 그게 이 교체의 값어치다.
     */
    fun scan(pbf: File) {
        val (routes, stops) = load(pbf)
        val inSeoul = routes.filter { r ->
            r.memberNodes.any { stops[it]?.let { s -> inBox(s.lat, s.lon) } == true }
        }
        println("      그중 수도권에 걸친 노선 ${inSeoul.size}개 · " +
            "허용목록에 든 것 ${inSeoul.count { it.ref in LINE_OF }}개")
        println()
        println("      ── 노선 (허용목록에 든 것만, ref 별) ──")
        for ((ref, rs) in inSeoul.filter { it.ref in LINE_OF }.groupBy { it.ref }
            .toList().sortedByDescending { it.second.size }) {
            val exp = rs.count { it.express }
            println("        %-8s → %-8s 계통 %3d개(급행 %2d) 최대 %3d정차".format(
                ref, LINE_OF[ref], rs.size, exp, rs.maxOf { it.memberNodes.size }))
        }
        println()
        println("      ── 2020년 이후 개통분이 있나 ──")
        val probes = listOf(
            "대곡소사" to listOf("원종", "부천종합운동장", "김포공항"),
            "별내선" to listOf("별내", "다산", "동구릉", "구리", "암사역사공원"),
            "신림선" to listOf("샛강", "보라매병원", "관악산"),
            "GTX-A" to listOf("운정중앙", "동탄", "구성", "성남"),
            "진접선" to listOf("진접", "오남", "별내별가람"),
            "하남연장" to listOf("하남시청", "하남검단산"),
            "석남연장" to listOf("석남", "산곡"),
            "연천연장" to listOf("연천", "전곡", "청산"),
            "서해선일산" to listOf("일산", "능곡", "대곡"),
            "인천1연장" to listOf("검단호수공원", "검단오류"),
        )
        val names = stops.values.mapNotNull { it.name.ifEmpty { null } }.toHashSet()
        for ((label, want) in probes) {
            val hit = want.filter { it in names }
            println("        %-12s %d/%d  %s".format(label, hit.size, want.size,
                if (hit.size == want.size) "있음" else "없는 것: " + (want - hit.toSet()).joinToString(" · ")))
        }
        println()
        val total = inSeoul.sumOf { it.memberNodes.size }
        val unnamed = inSeoul.sumOf { r -> r.memberNodes.count { stops[it]?.name.isNullOrEmpty() } }
        val missing = inSeoul.sumOf { r -> r.memberNodes.count { it !in stops } }
        println("      정차 ${"%,d".format(total)}개 중 이름 없는 것 ${unnamed}개 · " +
            "노드를 못 찾은 것 ${missing}개")
        println()
        println("      ── 이걸로 망을 만들면 ──")
        val (net, pats) = build(pbf)
        val byLine = pats.groupBy { it.line }
        println("        노선별 계통: " + byLine.entries.sortedByDescending { it.value.size }
            .joinToString(" · ") { "${it.key}${it.value.size}" })
        println("        역이 여럿인 이름(같은 이름 다른 역): " +
            net.stations.groupBy { it.name }.filter { it.value.size > 1 }.keys.joinToString(" · "))
    }
}
