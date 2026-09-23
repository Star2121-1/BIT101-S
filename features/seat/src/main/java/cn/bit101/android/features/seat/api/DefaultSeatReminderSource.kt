package cn.bit101.android.features.seat.api

import cn.bit101.android.features.notify.SeatReminderInput
import cn.bit101.android.features.notify.SeatReminderSource
import cn.bit101.android.features.seat.SeatLog
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SeatReminderSource] 的实现：把「我的预约」翻译成提醒中心能吃的输入。
 *
 * ## 职责边界
 *
 * 提醒中心不认识 `ReservationRecord`（那是座位模块的模型），所以转换在**这里**做：
 * 只取「座位号」与「签到截止时刻」两个字段 —— 前者用来写文案，后者用来定时。
 * 签到时限的规则（当日 60 分钟 / 次日 9:00 前）由 `ReservationRecord.signInDeadline`
 * 实现，这里不重复判断。
 *
 * ## 为什么只给「还没签到的」
 *
 * `status == "2"` 才是有效未签到；其余状态（使用中、暂离、已结束）都不需要签到提醒。
 * 已错过截止的也不给 —— 违约已经记上了，这时候弹通知只会添堵。
 */
@Singleton
class DefaultSeatReminderSource @Inject constructor(
    private val seatApi: SeatApi,
    private val reservationRepository: SeatReservationRepository,
) : SeatReminderSource {

    override suspend fun pendingSignIns(now: LocalDateTime): List<SeatReminderInput> {
        // 没有会话就直接放弃：拉不到数据，也没什么可提醒的（不刷日志、不弹窗）
        if (seatApi.token.isBlank()) return emptyList()

        // 尽量用新鲜数据：预约可能在别处被取消，或已经刷卡签到
        runCatching { reservationRepository.refreshIfStale(REFRESH_INTERVAL_MS) }
            .onFailure { SeatLog.w(TAG, "refresh before notify failed: ${it.message}") }

        val records = reservationRepository.records.value
        if (records.isEmpty()) return emptyList()

        return records.mapNotNull { record ->
            if (!record.isActive) return@mapNotNull null
            val deadline = record.signInDeadline(now) ?: return@mapNotNull null
            // 只给「还来得及」的
            if (!deadline.isAfter(now)) return@mapNotNull null
            SeatReminderInput(
                seatNo = record.seatNo,
                signInDeadline = deadline,
            )
        }
    }

    private companion object {
        /**
         * 读数据前的刷新节流（5 分钟）。
         *
         * ⚠️ 这里**比组件的 10 分钟更短**：签到时限是「错过就记违约」的事，
         * 用稍旧的数据算出错误截止时刻的代价比多发一次请求大得多。
         */
        const val REFRESH_INTERVAL_MS = 5 * 60_000L

        const val TAG = "SeatReminderSource"
    }
}
