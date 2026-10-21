package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.StudySpotLogic
import java.time.LocalDateTime

/**
 * 「我的座位预约」来源。
 *
 * 为什么接口在 data、实现在 features:seat：座位解析（`ReservationRecord` / seatlib
 * 会话）住在 seat 模块，而 `data` 不能依赖 `features`。这里只暴露
 * [StudySpotLogic.Booking] 这一种 data 层认识的形状，实现由 seat 模块用 Hilt 提供。
 *
 * 约定：
 * - **没有登录座位 / 没有有效预约 / 刷新失败** 一律返回 `null`，不要抛异常 ——
 *   自习推荐是锦上添花，不能因为座位接口抽风就把主功能拖下水。
 * - 只返回**进行中或即将开始（1 小时内）**的预约，已结束的不要。
 */
interface SeatBookingSource {

    suspend fun currentBooking(now: LocalDateTime): StudySpotLogic.Booking?
}
