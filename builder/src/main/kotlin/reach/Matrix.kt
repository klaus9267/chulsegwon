package reach

import java.io.File

/** 한 슬롯 = (탐색 방향, 기준 시각). 출근은 도착 기준, 퇴근은 출발 기준이라 방향이 필요하다. */
enum class Direction { ARRIVE_BY, DEPART_AT }

class Slot(val index: Int, val direction: Direction, val secondsOfDay: Int) {
    val label: String
        get() {
            val h = (secondsOfDay / 3600) % 24
            val m = (secondsOfDay % 3600) / 60
            return "%s %02d:%02d".format(if (direction == Direction.ARRIVE_BY) "도착" else "출발", h, m)
        }
}

/**
 * 출발역 하나에 대한 전 역 × 전 슬롯 소요시간.
 *
 * 분 단위 uint8 로 담는다. 255 는 도달불가 sentinel. 2시간 통근이 120 이라 넉넉하고,
 * 초 단위(uint16) 대비 파일이 절반이다. 프론트에서 threshold 비교만 하므로 분이면 충분하다.
 */
object MatrixWriter {

    const val MAGIC = "TRMX"
    const val VERSION = 1
    const val UNREACHABLE_MINUTES = 255

    fun write(file: File, slots: List<Slot>, stationCount: Int, minutes: Array<ByteArray>) {
        require(minutes.size == slots.size)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(VERSION)
            out.write(0) // flags
            out.write(slots.size and 0xFF); out.write((slots.size shr 8) and 0xFF)
            out.write(stationCount and 0xFF); out.write((stationCount shr 8) and 0xFF)
            for (row in minutes) {
                require(row.size == stationCount)
                out.write(row)
            }
        }
    }

    /** 초 → 분 반올림. 도달불가와 상한 초과는 sentinel 로 접는다. */
    fun toMinuteByte(seconds: Int, capMinutes: Int): Byte {
        if (seconds == UNREACHABLE) return UNREACHABLE_MINUTES.toByte()
        val m = (seconds + 30) / 60
        return if (m >= capMinutes) UNREACHABLE_MINUTES.toByte() else m.toByte()
    }
}
