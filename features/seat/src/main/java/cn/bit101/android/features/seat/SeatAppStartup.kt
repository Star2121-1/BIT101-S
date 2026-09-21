package cn.bit101.android.features.seat

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import cn.bit101.android.features.widget.SeatWidgetBridgeHolder

/**
 * 座位模块在 App 启动时需要做的一次性接线。
 *
 * 目前只做一件事：把「座位任务 → 桌面小组件」的推送器跑起来。
 * 放在 App 启动（而不是「座」页面）是因为：**用户不开座位页时后台任务仍在跑**，
 * 组件上也应该能看到进度。
 */
object SeatAppStartup {

    /**
     * 应用级协程作用域。
     *
     * 用 [SupervisorJob]：某个孩子的失败不应连坐整个作用域 ——
     * 组件推送失败绝不能让座位监控的执行链路一起挂掉。
     * 这个作用域与 App 同生命周期，所以**不需要也不应该**取消它。
     */
    private val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    fun init(context: Context) {
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SeatEntryPoint::class.java,
            )
            // 组件的「一键预约」要经由 holder 找到座位侧实现 —— 必须在 App 启动时装好，
            // 否则组件进程先被拉起时拿不到（holder 里是 null，只能提示用户先打开 App）。
            SeatWidgetBridgeHolder.install(entryPoint.seatWidgetBridge())
            entryPoint.seatWidgetPublisher().start(appScope)
        }
        // 推送器起不来只影响桌面组件，不影响 App 主流程 —— 刻意吞掉异常。
    }
}

/** Hilt 入口点：取座位侧的组件推送器（非注入组件场景的标准解法）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SeatEntryPoint {
    fun seatWidgetPublisher(): SeatWidgetPublisher

    /** 组件「一键预约」的座位侧实现（绑定见 `SeatWidgetBridgeModule`）。 */
    fun seatWidgetBridge(): cn.bit101.android.features.widget.SeatWidgetBridge
}
