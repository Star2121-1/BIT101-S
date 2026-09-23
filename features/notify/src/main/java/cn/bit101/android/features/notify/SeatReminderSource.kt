package cn.bit101.android.features.notify

import java.time.LocalDateTime

/**
 * 座位侧向提醒中心提供「需要签到提醒」的数据。
 *
 * ## 为什么是接口
 *
 * `features:notify` **不依赖 `features:seat`**（依赖方向见 `docs/notify.md`），
 * 所以提醒中心不能直接读 `SeatReservationRepository`。于是反过来：
 * notify 只声明**它需要什么**（本接口），座位模块提供实现并在 Hilt 里绑定
 * （`DefaultSeatReminderSource`）—— 与组件侧 `SeatWidgetBridge` 同一套思路。
 *
 * ## 与「组件动作桥」的区别
 *
 * 那条桥是**动作**方向（组件让座位下单），这条是**数据**方向（座位把签到时限给提醒）。
 * 之所以不直接注入 `SeatReservationRepository`：那个仓库属于座位模块的内部实现，
 * 一旦它改签名，提醒中心就得跟着改 —— 中间的模型转换在这里一次做掉。
 */
interface SeatReminderSource {

    /**
     * 当前**尚未签到、且签到截止时刻还没过**的预约。
     *
     * 只返回「还来得及」的那些 —— 已经错过截止的补发提醒毫无意义
     * （违约已经记上了，这时候弹通知只会添堵）。
     *
     * @param now 当前时刻，由调用方传入以便单测可控
     */
    suspend fun pendingSignIns(now: LocalDateTime): List<SeatReminderInput>
}
