package cn.bit101.android.features.seat.api

import cn.bit101.android.features.widget.SeatWidgetBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 座位模块对组件动作桥的绑定。
 *
 * 这是 seat 模块**唯一**的 Hilt module —— 其余类都用构造注入，不需要 module。
 * 之所以要它：组件的 `WidgetEntryPoint.seatWidgetBridge()` 需要 [SeatWidgetBridge]
 * 这个**接口**的绑定，而 Hilt 对接口不会自动找实现。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SeatWidgetBridgeModule {

    @Binds
    abstract fun bindSeatWidgetBridge(impl: DefaultSeatWidgetBridge): SeatWidgetBridge
}
