package reach

import java.io.DataInputStream
import java.io.File
import java.util.zip.Inflater

/**
 * OSM PBF 리더.
 *
 * **왜 직접 쓰나.** 이 프로젝트의 의존성은 Jackson 하나다. PBF 를 읽자고 osm4j 나
 * osmosis 를 들이면 전이 의존성이 여럿 따라오는데, 정작 우리가 쓰는 건 노드 좌표와
 * 웨이의 노드 목록·태그뿐이다. protobuf 와이어 포맷은 varint 와 길이 접두 필드
 * 두 가지가 전부라 그 정도는 직접 읽는 게 낫다고 봤다.
 *
 * 대신 **완전한 protobuf 구현이 아니다.** OSM PBF 가 실제로 쓰는 필드만 읽고
 * 나머지는 건너뛴다.
 *
 * 2026-09: **릴레이션과 노드 태그**를 읽게 넓혔다. 도보 그래프에는 필요 없었는데
 * 철도망을 여기서 뽑기로 하면서 필요해졌다 - 역은 태그 붙은 노드(railway=station)고,
 * 노선은 릴레이션(type=route, route=subway)이며 정차 순서가 멤버 순서다.
 * 둘 다 기본은 꺼져 있어서 도보 그래프를 만들 때 값을 치르지 않는다.
 *
 * 파일 구조:
 * ```
 * [4바이트 빅엔디안 헤더길이][BlobHeader][Blob] × N
 *   BlobHeader.type = "OSMHeader" | "OSMData"
 *   Blob = raw 또는 zlib 압축된 PrimitiveBlock
 * ```
 */
object OsmPbf {

    /** 노드 하나. 좌표는 이미 실수로 환산된 값이다. */
    class Node(val id: Long, val lat: Double, val lon: Double)

    /**
     * 태그가 붙은 노드. 역·정류장이 여기 들어온다.
     *
     * [Node] 와 따로 두는 이유는 비용이다. 남한 PBF 는 노드가 수천만 개인데 그중
     * 태그가 있는 건 극소수다. 전부에 Map 을 하나씩 만들면 그것만으로 힙이 터진다.
     * 그래서 태그가 실제로 있는 노드에만 이걸 준다.
     */
    class TaggedNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)

    /**
     * 릴레이션 하나. 노선이 여기 들어온다.
     *
     * [memberTypes] 는 PBF 의 enum 그대로다 - 0=노드, 1=웨이, 2=릴레이션.
     * 정차 순서는 **멤버 순서**이고, 역할([memberRoles])이 stop / stop_entry_only /
     * platform 인 멤버가 정차다.
     */
    class Relation(
        val id: Long,
        val tags: Map<String, String>,
        val memberIds: LongArray,
        val memberTypes: IntArray,
        val memberRoles: Array<String>,
    )

    /** 웨이 하나. 태그는 필요한 것만 담는다. */
    class Way(val id: Long, val refs: LongArray, val tags: Map<String, String>)

    /**
     * 파일을 훑으며 노드와 웨이를 넘긴다.
     *
     * 콜백을 받는 이유는 수도권 전체가 노드 수천만 개라 리스트로 들고 있을 수 없어서다.
     * 부르는 쪽이 필요한 것만 골라 담는다.
     */
    fun read(
        file: File,
        wantNodes: Boolean,
        wantWays: Boolean,
        onNode: (Node) -> Unit = {},
        onWay: (Way) -> Unit = {},
        /** 태그 붙은 노드를 받고 싶을 때만 켠다. 켜면 DenseNodes 의 keys_vals 도 푼다. */
        wantNodeTags: Boolean = false,
        onTaggedNode: (TaggedNode) -> Unit = {},
        wantRelations: Boolean = false,
        onRelation: (Relation) -> Unit = {},
    ) {
        require(file.exists()) { "PBF 가 없다: ${file.absolutePath}" }
        DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val headerLen = try { input.readInt() } catch (e: Exception) { break }
                if (headerLen <= 0 || headerLen > 64 * 1024) break
                val header = ByteArray(headerLen).also { input.readFully(it) }

                var type = ""
                var dataSize = 0
                Reader(header).each { field, r ->
                    when (field) {
                        1 -> type = String(r.bytes(), Charsets.UTF_8)
                        3 -> dataSize = r.varint().toInt()
                        else -> r.skip(field)
                    }
                }
                if (dataSize <= 0) break
                val blob = ByteArray(dataSize).also { input.readFully(it) }
                if (type != "OSMData") continue

                val block = inflateBlob(blob)
                readPrimitiveBlock(
                    block, wantNodes, wantWays, onNode, onWay,
                    wantNodeTags, onTaggedNode, wantRelations, onRelation,
                )
            }
        }
    }

    /** Blob: 1=raw, 2=raw_size, 3=zlib_data. 실제 파일은 거의 항상 zlib 이다. */
    private fun inflateBlob(blob: ByteArray): ByteArray {
        var raw: ByteArray? = null
        var zlib: ByteArray? = null
        var rawSize = 0
        Reader(blob).each { field, r ->
            when (field) {
                1 -> raw = r.bytes()
                2 -> rawSize = r.varint().toInt()
                3 -> zlib = r.bytes()
                else -> r.skip(field)
            }
        }
        raw?.let { return it }
        val z = zlib ?: return ByteArray(0)
        val out = ByteArray(rawSize)
        val inf = Inflater()
        inf.setInput(z)
        var off = 0
        while (off < out.size && !inf.finished()) {
            val n = inf.inflate(out, off, out.size - off)
            if (n == 0) break
            off += n
        }
        inf.end()
        return out
    }

    /**
     * PrimitiveBlock: 1=stringtable, 2=primitivegroup,
     * 17=granularity, 19=lat_offset, 20=lon_offset.
     *
     * 좌표는 정수로 담겨 있고 `1e-9 * (offset + granularity * delta)` 로 환산한다.
     * granularity 기본값 100 은 약 1cm 해상도다.
     */
    private fun readPrimitiveBlock(
        block: ByteArray,
        wantNodes: Boolean,
        wantWays: Boolean,
        onNode: (Node) -> Unit,
        onWay: (Way) -> Unit,
        wantNodeTags: Boolean,
        onTaggedNode: (TaggedNode) -> Unit,
        wantRelations: Boolean,
        onRelation: (Relation) -> Unit,
    ) {
        var strings: Array<String> = emptyArray()
        val groups = ArrayList<ByteArray>()
        var granularity = 100L
        var latOffset = 0L
        var lonOffset = 0L

        Reader(block).each { field, r ->
            when (field) {
                1 -> strings = readStringTable(r.bytes())
                2 -> groups += r.bytes()
                17 -> granularity = r.varint()
                19 -> latOffset = r.zigzag()
                20 -> lonOffset = r.zigzag()
                else -> r.skip(field)
            }
        }

        for (g in groups) {
            Reader(g).each { field, r ->
                // ⚠️ 필드를 **조건부로 소비하면 안 된다.** 처음엔
                // `if (wantNodes) readDense(r.bytes(), ...)` 로 썼는데, 조건이 거짓이면
                // r.bytes() 가 아예 안 불려서 그 바이트가 스트림에 남는다. 다음 읽기가
                // 길이 접두사를 필드 키로 오해하면서 파서 전체가 어긋났다.
                // 먼저 읽어서 소비하고, 쓸지 말지는 그다음에 정한다.
                when (field) {
                    2 -> {
                        val body = r.bytes()
                        if (wantNodes || wantNodeTags) {
                            readDense(
                                body, granularity, latOffset, lonOffset, strings,
                                wantNodes, onNode, wantNodeTags, onTaggedNode,
                            )
                        }
                    }
                    3 -> {
                        val body = r.bytes()
                        if (wantWays) readWay(body, strings, onWay)
                    }
                    4 -> {
                        val body = r.bytes()
                        if (wantRelations) readRelation(body, strings, onRelation)
                    }
                    else -> r.skip(field)
                }
            }
        }
    }

    private fun readStringTable(b: ByteArray): Array<String> {
        val out = ArrayList<String>()
        Reader(b).each { field, r ->
            if (field == 1) out += String(r.bytes(), Charsets.UTF_8) else r.skip(field)
        }
        return out.toTypedArray()
    }

    /**
     * DenseNodes: 1=id, 8=lat, 9=lon — 전부 **델타 인코딩된 packed sint64** 다.
     *
     * 즉 값 자체가 아니라 앞 값과의 차이가 들어 있어서 누적해야 한다. 이게 PBF 가
     * XML 대비 작은 이유이기도 하다 — 가까운 노드끼리 모여 있으면 차이가 작다.
     */
    private fun readDense(
        b: ByteArray,
        granularity: Long,
        latOffset: Long,
        lonOffset: Long,
        strings: Array<String>,
        wantNodes: Boolean,
        onNode: (Node) -> Unit,
        wantNodeTags: Boolean,
        onTaggedNode: (TaggedNode) -> Unit,
    ) {
        var ids: LongArray = LongArray(0)
        var lats: LongArray = LongArray(0)
        var lons: LongArray = LongArray(0)
        // keys_vals: 노드마다 키,값 쌍이 이어지다가 **0 하나로 끝난다.** 태그가 없는
        // 노드도 0 을 하나 차지한다. 그래서 배열 하나를 노드 순서대로 훑으며 잘라야
        // 하고, 한 칸이라도 건너뛰면 그다음 노드부터 태그가 통째로 밀린다.
        var kv: LongArray = LongArray(0)
        Reader(b).each { field, r ->
            when (field) {
                1 -> ids = r.packedZigzag()
                8 -> lats = r.packedZigzag()
                9 -> lons = r.packedZigzag()
                10 -> { val v = r.packedVarint(); if (wantNodeTags) kv = v }
                else -> r.skip(field)
            }
        }
        var id = 0L; var lat = 0L; var lon = 0L
        var k = 0
        val n = minOf(ids.size, lats.size, lons.size)
        for (i in 0 until n) {
            id += ids[i]; lat += lats[i]; lon += lons[i]
            val la = 1e-9 * (latOffset + granularity * lat)
            val lo = 1e-9 * (lonOffset + granularity * lon)
            if (wantNodes) onNode(Node(id, la, lo))
            if (!wantNodeTags || k >= kv.size) continue
            if (kv[k] == 0L) { k++; continue }
            val tags = HashMap<String, String>(4)
            while (k + 1 < kv.size && kv[k] != 0L) {
                val ki = kv[k].toInt(); val vi = kv[k + 1].toInt()
                if (ki < strings.size && vi < strings.size) tags[strings[ki]] = strings[vi]
                k += 2
            }
            k++
            if (tags.isNotEmpty()) onTaggedNode(TaggedNode(id, la, lo, tags))
        }
    }

    /**
     * Relation: 1=id, 2=keys, 3=vals, 8=roles_sid, 9=memids(델타 sint64), 10=types.
     *
     * 멤버 순서가 곧 노선의 정차 순서라 **순서를 흐트러뜨리면 안 된다.**
     */
    private fun readRelation(b: ByteArray, strings: Array<String>, onRelation: (Relation) -> Unit) {
        var id = 0L
        var keys: LongArray = LongArray(0)
        var vals: LongArray = LongArray(0)
        var roles: LongArray = LongArray(0)
        var mem: LongArray = LongArray(0)
        var types: LongArray = LongArray(0)
        Reader(b).each { field, r ->
            when (field) {
                1 -> id = r.varint()
                2 -> keys = r.packedVarint()
                3 -> vals = r.packedVarint()
                8 -> roles = r.packedVarint()
                9 -> mem = r.packedZigzag()
                10 -> types = r.packedVarint()
                else -> r.skip(field)
            }
        }
        val tags = HashMap<String, String>(keys.size.coerceAtLeast(1))
        for (i in keys.indices) {
            val ki = keys[i].toInt()
            val vi = vals.getOrNull(i)?.toInt() ?: continue
            if (ki < strings.size && vi < strings.size) tags[strings[ki]] = strings[vi]
        }
        val n = minOf(mem.size, types.size)
        val ids = LongArray(n)
        var acc = 0L
        for (i in 0 until n) { acc += mem[i]; ids[i] = acc }
        onRelation(
            Relation(
                id, tags, ids,
                IntArray(n) { types[it].toInt() },
                Array(n) { i -> roles.getOrNull(i)?.toInt()?.let { strings.getOrNull(it) } ?: "" },
            ),
        )
    }

    /** Way: 1=id, 2=keys, 3=vals, 8=refs(델타 sint64). */
    private fun readWay(b: ByteArray, strings: Array<String>, onWay: (Way) -> Unit) {
        var id = 0L
        var keys: LongArray = LongArray(0)
        var vals: LongArray = LongArray(0)
        var refs: LongArray = LongArray(0)
        Reader(b).each { field, r ->
            when (field) {
                1 -> id = r.varint()
                2 -> keys = r.packedVarint()
                3 -> vals = r.packedVarint()
                8 -> refs = r.packedZigzag()
                else -> r.skip(field)
            }
        }
        val tags = HashMap<String, String>(keys.size)
        for (i in keys.indices) {
            val k = keys[i].toInt(); val v = vals.getOrNull(i)?.toInt() ?: continue
            if (k < strings.size && v < strings.size) tags[strings[k]] = strings[v]
        }
        var acc = 0L
        val nodeIds = LongArray(refs.size)
        for (i in refs.indices) { acc += refs[i]; nodeIds[i] = acc }
        onWay(Way(id, nodeIds, tags))
    }

    // ── protobuf 와이어 포맷 ────────────────────────────────────
    //
    // 필요한 건 두 가지뿐이다. 태그(varint)의 하위 3비트가 타입이고 나머지가 필드 번호,
    // 타입 0 은 varint, 타입 2 는 길이 접두 바이트열. OSM PBF 는 이 둘만 쓴다.

    private class Reader(val b: ByteArray) {
        var p = 0
        /** 방금 읽은 키의 와이어 타입. [skip] 이 이걸 보고 얼마나 건너뛸지 정한다. */
        var wire = 0

        inline fun each(body: (Int, Reader) -> Unit) {
            while (p < b.size) {
                val key = varint()
                wire = (key and 7).toInt()
                body((key shr 3).toInt(), this)
            }
        }

        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val c = b[p++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                if (c and 0x80 == 0) return r
                s += 7
            }
        }

        /** sint64 는 지그재그 인코딩이다 — 음수를 짧게 담으려고 부호를 최하위 비트로 옮긴다. */
        fun zigzag(): Long {
            val v = varint()
            return (v ushr 1) xor -(v and 1)
        }

        fun bytes(): ByteArray {
            val n = varint().toInt()
            val out = b.copyOfRange(p, p + n)
            p += n
            return out
        }

        fun packedVarint(): LongArray {
            val n = varint().toInt()
            val end = p + n
            val out = ArrayList<Long>(n / 2 + 1)
            while (p < end) out += varint()
            return out.toLongArray()
        }

        fun packedZigzag(): LongArray {
            val n = varint().toInt()
            val end = p + n
            val out = ArrayList<Long>(n / 2 + 1)
            while (p < end) out += zigzag()
            return out.toLongArray()
        }

        /**
         * 안 쓰는 필드 건너뛰기.
         *
         * 얼마나 건너뛸지는 값이 아니라 **와이어 타입**이 정한다. 그래서 [each] 가
         * 키를 읽을 때 타입을 [wire] 에 남겨 둔다. 이걸 안 하면 값을 보고 타입을
         * 되짚어야 하는데, 그건 되지 않는다 — 바이트만 봐서는 varint 인지 길이인지 모른다.
         */
        fun skip(@Suppress("UNUSED_PARAMETER") field: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> { val n = varint().toInt(); p += n }
                5 -> p += 4
                else -> p = b.size   // 모르는 타입이면 이 메시지를 포기한다
            }
        }
    }
}
