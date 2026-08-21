package reach

/** 그래프가 의도대로 만들어졌는지 눈으로 확인하는 도구. 파이프라인이 아니라 검사용. */
object Diagnostics {

    fun lines(network: Network) {
        println("=== 노선별 체인 ===")
        println("체인이 많다는 건 노선이 조각났다는 뜻이고, 조각 경계에서 불필요한 대기가 생긴다.")
        val chains = network.lineSequences()
        for ((line, cs) in chains.entries.sortedByDescending { it.value.size }) {
            val sizes = cs.map { it.size }.sortedDescending()
            println("  %-10s 체인 %2d개  길이 %s".format(line, cs.size, sizes.take(8).joinToString(",")))
        }
        println("  합계 체인 ${chains.values.sumOf { it.size }}개")
    }

    fun transfers(network: Network) {
        println("=== 환승역 상위 ===")
        network.stations.filter { it.platforms.size > 1 }
            .sortedByDescending { it.platforms.size }
            .take(12)
            .forEach { st ->
                val ls = st.platforms.map { network.platforms[it].line }
                println("  %-10s %d개 노선: %s".format(st.name, st.platforms.size, ls.joinToString(",")))
            }
        println("  환승역 총 ${network.stations.count { it.platforms.size > 1 }}개")
    }

    /** 이름이 같은데 멀리 떨어져 따로 분리된 역들 — 5호선 양평 vs 경의중앙선 양평 같은 케이스. */
    fun duplicateNames(network: Network) {
        println("=== 같은 이름 다른 역 (의도적 분리) ===")
        network.stations.groupBy { it.name }.filter { it.value.size > 1 }.forEach { (name, sts) ->
            val d = Geo.haversineMeters(sts[0].lat, sts[0].lon, sts[1].lat, sts[1].lon)
            println("  %-10s %d곳, 첫 두 곳 거리 %,.0fm".format(name, sts.size, d))
        }
    }

    /** 고른 경로를 그대로 풀어 보여준다. 숫자만 보면 왜 틀렸는지 알 수 없다. */
    fun explain(network: Network, router: Router, fromName: String, toName: String, atSec: Int) {
        val a = network.stations.firstOrNull { it.name == fromName }
        val b = network.stations.firstOrNull { it.name == toName }
        if (a == null || b == null) { println("!! 역을 못 찾음: $fromName / $toName"); return }
        println("=== $fromName -> $toName  경로 ===")
        router.explain(a.index, b.index, atSec).forEach { println(it) }
    }

    /** 대표 구간 소요시간을 찍어 실제 감각과 맞는지 본다. */
    fun sampleRoutes(network: Network, router: Router, fromName: String, atSec: Int, targets: List<String>) {
        val from = network.stations.firstOrNull { it.name == fromName }
        if (from == null) { println("!! '$fromName' 역을 못 찾음"); return }
        val h = atSec / 3600; val m = (atSec % 3600) / 60
        println("=== $fromName 출발 %02d:%02d — 소요시간 ===".format(h, m))
        val secs = router.travelTimesDepartingAt(from.index, atSec)
        for (t in targets) {
            val st = network.stations.firstOrNull { it.name == t }
            if (st == null) { println("  %-10s (없음)".format(t)); continue }
            val v = secs[st.index]
            println("  %-10s %s".format(t, if (v == UNREACHABLE) "도달불가" else "${(v + 30) / 60}분"))
        }
    }
}
