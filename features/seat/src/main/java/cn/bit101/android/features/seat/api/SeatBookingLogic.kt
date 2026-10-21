package cn.bit101.android.features.seat.api

import cn.bit101.android.data.school.StudySpotLogic
import cn.bit101.android.features.seat.model.ReservationRecord
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 从「我的预约」里挑出**现在该提醒用户去坐**的那一条。
 *
 * 纯函数，与网络/登录解耦，方便覆盖边界：
 * - 已结束的不算
 * - 1 小时以后才开始的先不打扰
 * - 多条命中时取开始最早的（进行中的必然最早）
 * - 开始/结束时间解析失败就跳过（不猜）
 */
internal object SeatBookingLogic {

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 提前多久开始提示。 */
    private const val LEAD_MINUTES = 60L

    fun pick(records: List<ReservationRecord>, now: LocalDateTime): StudySpotLogic.Booking? {
        val deadline = now.plusMinutes(LEAD_MINUTES)

        return records.asSequence()
            .filter { it.isActive }
            .mapNotNull { record ->
                val begin = record.beginLocalTime() ?: return@mapNotNull null
                val end = record.endLocalTime() ?: return@mapNotNull null

                // 已结束 / 还早：都不该出现在「现在去哪儿」里
                if (!end.isAfter(now)) return@mapNotNull null
                if (begin.isAfter(deadline)) return@mapNotNull null

                val place = listOf(record.areaName.trim(), record.seatNo.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (place.isBlank()) return@mapNotNull null

                begin to StudySpotLogic.Booking(
                    place = place,
                    timeText = timeText(begin, end, now),
                )
            }
            .minByOrNull { it.first }
            ?.second
    }

    private fun timeText(begin: LocalDateTime, end: LocalDateTime, now: LocalDateTime): String {
        val prefix = if (begin.toLocalDate() == now.toLocalDate()) "" else "明天 "
        return "$prefix${begin.format(HH_MM)}-${end.format(HH_MM)}"
    }
}
