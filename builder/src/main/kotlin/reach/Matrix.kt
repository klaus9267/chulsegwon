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

    /** flags 비트 0: 총시간 평면 뒤에 **이탈 도보(분)** 평면이 한 벌 더 있다. */
    const val FLAG_WALK_PLANE = 1

    /**
     * [minutes] 는 총 소요시간(분), [walk] 는 그중 **이탈 도보**(분).
     *
     * **도보를 따로 싣는 이유.** 화면의 "역에서 도보 N분" 슬라이더가 값을 가지려면
     * 총시간 안에 도보가 얼마인지 알아야 한다. 예전엔 그걸 몰라서, 웹이 동네 중심점
     * 바깥으로 도보를 **한 번 더** 더해 그렸다 — 행렬 값에 이미 이탈 도보가 15분까지
     * 들어 있는데도. 두 번 센 것이고, 게다가 배치는 1.1m/s 도보망, 웹은 1.25m/s
     * 직선이라 모델도 달랐다.
     *
     * 평면을 나눠 싣는다(총시간 전부 → 도보 전부). 그래야 옛 판을 읽는 코드가
     * 앞쪽만 보고도 그대로 동작하고, 같은 값끼리 이웃해 압축도 잘 된다.
     */
    fun write(
        file: File,
        slots: List<Slot>,
        stationCount: Int,
        minutes: Array<ByteArray>,
        walk: Array<ByteArray>? = null,
    ) {
        require(minutes.size == slots.size)
        require(walk == null || walk.size == slots.size)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(VERSION)
            out.write(if (walk != null) FLAG_WALK_PLANE else 0)
            out.write(slots.size and 0xFF); out.write((slots.size shr 8) and 0xFF)
            out.write(stationCount and 0xFF); out.write((stationCount shr 8) and 0xFF)
            for (row in minutes) {
                require(row.size == stationCount)
                out.write(row)
            }
            if (walk != null) for (row in walk) {
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
