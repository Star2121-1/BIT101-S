package cn.bit101.android.features.widget

import android.content.Context
import dagger.hilt.android.EntryPointAccessors

/**
 * 组件 → 座位模块的**动作桥**（与 [SeatWidgetSnapshot] 的数据桥方向相反）。
 *
 * ## 为什么需要它
 *
 * 组件座位页的「一键预约」要在**不打开 App** 的前提下对目标座位下单。
 * 预约需要 `SeatApi`（会话、选座、confirm），而这些都在座位模块里；
 * 但 `features:widget` **不能**依赖 `features:seat`（会成环：`:features` 依赖 widget，
 * seat 又依赖 widget）。
 *
 * 所以沿用快照的思路反过来：widget 只声明**它需要什么能力**（本接口），
 * 座位模块在 App 启动时把实现注册进 [SeatWidgetBridgeHolder]。
 *
 * ## 失败语义
 *
 * - 实现未注册（App 从没启动过）→ [SeatWidgetBridgeHolder.invoke] 返回固定文案
 * - 预约失败 → 返回**给用户看的**原因（实现方负责翻译，组件不认识座位模块的异常类型）
 */
interface SeatWidgetBridge {

    /**
     * 立即对任务 [taskId] 的目标座位尝试一次预约。
     *
     * @return null 表示成功；否则返回**可直接展示**的失败原因
     */
    suspend fun reserveNow(taskId: String): String?

    /** 是否具备执行条件（已登录座位系统）。不具备时组件不该显示「一键预约」。 */
    fun canReserveNow(): Boolean

    /**
     * 拉一次「我的预约」，让组件上的座位状态（已预约/使用中/暂离）保持新鲜。
     *
     * WorkManager 的兜底刷新调用它 —— 否则组件每 30 分钟重绘的永远是同一份旧状态。
     * 失败静默（内部记日志即可）：组件显示旧状态好过反复打扰。
     */
    suspend fun refreshReservations()
}

/** [SeatWidgetBridge] 的持有者。座位模块启动时注册，组件侧只读。 */
object SeatWidgetBridgeHolder {

    @Volatile
    private var bridge: SeatWidgetBridge? = null

    /** 座位模块在 App 启动时调用（幂等）。 */
    fun install(impl: SeatWidgetBridge) {
        bridge = impl
    }

    /** 测试与登出场景使用。 */
    fun clear() {
        bridge = null
    }

    fun canReserveNow(): Boolean = bridge?.canReserveNow() ?: false

    /** 座位模块未注册时不做任何事（组件显示旧状态即可）。 */
    suspend fun refreshReservations() {
        runCatching { bridge?.refreshReservations() }
    }

    /**
     * 执行一键预约。实现未就绪时返回固定文案而不是抛异常 ——
     * 组件的动作回调抛异常只会静默失败，用户什么都看不到。
     */
    suspend fun invoke(taskId: String): String? {
        val impl = bridge ?: return "请先打开 App 登录座位系统"
        return runCatching { impl.reserveNow(taskId) }.getOrElse {
            it.message?.takeIf { m -> m.isNotBlank() } ?: "预约失败，请稍后再试"
        }
    }

    /**
     * 兜底：holder 里没有实现时（组件先于 App 被拉起、Hilt 尚未初始化），
     * 试着用 EntryPoint 现取一次。取不到就算了，返回 null 让组件显示空态。
     */
    fun ensureBridge(context: Context): SeatWidgetBridge? {
        bridge?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            ).seatWidgetBridge()
        }.getOrNull()?.also { install(it) }
    }
}
