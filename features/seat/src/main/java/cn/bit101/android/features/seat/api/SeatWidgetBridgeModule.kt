package cn.bit101.android.features.seat.api

import cn.bit101.android.features.notify.SeatReminderSource
import cn.bit101.android.features.widget.SeatWidgetBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 座位模块对「外部模块声明的接口」的绑定。
 *
 * seat 模块**只有这一个** Hilt module —— 其余类都用构造注入。
 * 之所以需要它：有两个接口由**别的模块声明**、由座位模块实现，
 * 而 Hilt 对接口不会自动找实现：
 *
 * | 接口 | 声明方 | 用途 |
 * |---|---|---|
 * | [SeatWidgetBridge] | `features:widget` | 组件的「一键预约」（动作方向） |
 * | [SeatReminderSource] | `features:notify` | 签到时限提醒（数据方向） |
 *
 * 依赖方向始终是 `seat → widget` / `seat → notify`；
 * 那两个模块都不知道座位模块的存在（它们只认识自己声明的接口）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SeatWidgetBridgeModule {

    @Binds
    abstract fun bindSeatWidgetBridge(impl: DefaultSeatWidgetBridge): SeatWidgetBridge

    @Binds
    abstract fun bindSeatReminderSource(impl: DefaultSeatReminderSource): SeatReminderSource
}
