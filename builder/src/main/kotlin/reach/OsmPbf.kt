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
 * 나머지는 건너뛴다. 릴레이션은 아예 읽지 않는다 — 도보 그래프에 필요 없다.
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
                readPrimitiveBlock(block, wantNodes, wantWays, onNode, onWay)
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
                        if (wantNodes) readDense(body, granularity, latOffset, lonOffset, onNode)
                    }
                    3 -> {
                        val body = r.bytes()
                        if (wantWays) readWay(body, strings, onWay)
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
        onNode: (Node) -> Unit,
    ) {
        var ids: LongArray = LongArray(0)
        var lats: LongArray = LongArray(0)
        var lons: LongArray = LongArray(0)
        Reader(b).each { field, r ->
            when (field) {
                1 -> ids = r.packedZigzag()
                8 -> lats = r.packedZigzag()
                9 -> lons = r.packedZigzag()
                else -> r.skip(field)
            }
        }
        var id = 0L; var lat = 0L; var lon = 0L
        val n = minOf(ids.size, lats.size, lons.size)
        for (i in 0 until n) {
            id += ids[i]; lat += lats[i]; lon += lons[i]
            onNode(
                Node(
                    id,
                    1e-9 * (latOffset + granularity * lat),
                    1e-9 * (lonOffset + granularity * lon),
                ),
            )
        }
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
