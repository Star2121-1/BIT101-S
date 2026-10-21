package cn.bit101.android.features.seat.api

import cn.bit101.android.data.repo.base.SeatBookingSource
import cn.bit101.android.data.school.StudySpotLogic
import cn.bit101.android.features.seat.SeatLog
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SeatBookingSource] 的座位模块实现。
 *
 * - 未登录座位系统：直接返回 null，不碰网络（也不触发任何登录/短信流程）
 * - 已登录：最多每 5 分钟真正拉一次「我的预约」，其余时候用仓库内存里的列表
 * - 任何失败都吞掉：预约信息只是自习卡的一行提示，不能影响主推荐
 */
@Singleton
class DefaultSeatBookingSource @Inject constructor(
    private val reservations: SeatReservationRepository,
    private val seatApi: SeatApi,
) : SeatBookingSource {

    override suspend fun currentBooking(now: LocalDateTime): StudySpotLogic.Booking? {
        val token = runCatching { seatApi.ensureTokenLoaded() }.getOrNull()
        if (token.isNullOrBlank()) return null

        runCatching { reservations.refreshIfStale(STALE_MS) }
            .onFailure { SeatLog.w(TAG, "refresh reservations failed: ${it.message}") }

        return runCatching { SeatBookingLogic.pick(reservations.records.value, now) }
            .getOrNull()
    }

    private companion object {
        const val TAG = "SeatBookingSource"

        /** 自习卡可能被反复重组/连带刷新，5 分钟内复用内存结果即可。 */
        const val STALE_MS = 5 * 60_000L
    }
}
