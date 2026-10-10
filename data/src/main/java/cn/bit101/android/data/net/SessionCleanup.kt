package cn.bit101.android.data.net

import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.config.user.base.SeatLoginStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登出 / 会话失效时的清理 —— **全 App 只有这一份**。
 *
 * ## 为什么不在 `LoginStatus.clear()` 里做
 *
 * 之前 `DefaultLoginStatus.clear()` 顺手清了 seatlib 的 token 与任务。
 * **方向是对的**（学校会话一旦失效，seatlib 的 phpCAS 会话也确实跟着失效），
 * 但**位置错了**：`config` 是配置层，不该知道「座位有哪些数据、存在哪个键里」这种
 * 领域细节 —— 于是配置层反向依赖了座位的存储键，以后座位侧多存一样东西，
 * 就得回来改这里（而且改的人未必知道）。
 *
 * ⇒ 现在：各侧只清自己的（[LoginStatus.clear] 只清 BIT101），
 *   「一起清」由这个对象负责，它才同时认识两边。
 *
 * ⚠️ **调用点必须用它，不要直接用 `loginStatus.clear()`** ——
 *    否则就是「只清了一半」：BIT101 显示已登出，座位那边还拿着过期的 JWT 继续打接口。
 */
@Singleton
class SessionCleanup @Inject constructor(
    private val loginStatus: LoginStatus,
    private val seatLoginStatus: SeatLoginStatus,
    private val seatTaskStore: SeatTaskStore,
) {

    /**
     * 清掉全部会话与派生数据。
     *
     * - BIT101 侧：学号 / 密码 / 登录态 / webVpn / fakeCookie / 学校 cookie
     * - 座位侧：seatlib 的 JWT + 预约任务（会话失效后那些任务也不可能续跑）
     */
    suspend fun clearAll() {
        loginStatus.clear()
        seatLoginStatus.token.set("")
        seatTaskStore.tasks.set("")
    }
}
