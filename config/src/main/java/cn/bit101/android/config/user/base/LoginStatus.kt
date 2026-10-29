package cn.bit101.android.config.user.base

import cn.bit101.android.config.common.SettingItem
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.CookieManager

interface LoginStatus {

    /**
     * 登录学号
     */
    val sid: SettingItem<String>

    /**
     * 登录密码
     */
    val password: SettingItem<String>

    /**
     * 登录状态
     */
    val status: SettingItem<Boolean>

    /**
     * 学校会话是否使用 WebVPN
     */
    val webVpn: SettingItem<Boolean>

    /**
     * Cookie管理器
     */
    val cookieManager: CookieManager

    /**
     * 用于 bit101 的 fake-cookie
     */
    val fakeCookie: SettingItem<String>

    /**
     * BIT101 服务端（`bit101.flwfdd.xyz` / `login.bit101.flwfdd.xyz`）当前是否可用。
     *
     * 与 [status] **解耦**：[status] 只表示**学校会话**是否有效（座位/课表等直连功能的门槛）；
     * 本字段只描述 BIT101 服务端的可达性，供社区/成绩等页做**降级提示**，不影响学校功能。
     * 仅运行期状态，不持久化：默认 true，由 data 层在请求成功/失败时置真/假。
     */
    val bit101Available: MutableStateFlow<Boolean>

    /**
     * **学校身份验证的冷却截止时间**（毫秒时间戳，0 = 无冷却）。
     *
     * 为什么需要：BIT101 服务端代所有用户做学校统一身份认证，2026-10-30 该机被学校 WAF
     * 整 IP 封禁（一天 4000~7000 次 CAS 登录被判撞库）。客户端失败后若立刻重试，
     * 会把这台机器的请求量继续放大。⇒ 失败一次就按 [LoginRetryPolicy] 冷却，
     * 期间**一律不再发起学校登录**（包括交互式，避免凌晨给人发短信）。
     *
     * ⚠️ 必须持久化：不落盘的话重启 App 就重置，冷却形同虚设。
     */
    val identityRetryAt: SettingItem<Long>

    /**
     * **学校身份验证的连续失败次数**，驱动 [identityRetryAt] 的指数退避。
     * 登录成功即清零；`clear()` 也清零。
     */
    val identityAttempts: SettingItem<Int>

    /**
     * 清除登录状态，包括 sid、password、status、cookie
     */
    suspend fun clear()
}
