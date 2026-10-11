package cn.bit101.api.model.common

/**
 * 「这个域名是不是学校的」—— **全 App 只有这一份判据**。
 *
 * ## 为什么要单独抽出来（2026-09-30 SSO 审计）
 *
 * 同一条判据此前被抄了 4 遍，而且**抄得还不一样**（有的含根域、有的不含）：
 * `SchoolCookieStore`、`BitLoginSession`、`WebScreen`、`CasLoginScreen` 各一份。
 *
 * 抄漏一处的代价不小：CAS 登录链路一旦跳出 App（交给系统浏览器），
 * **会话 cookie 就不会回到 App** —— 用户看到的是「明明登录了却还是未登录」，
 * 这正是 `*.bit.edu.cn` 必须留在 App 内 WebView 的原因（v1.9.3 修过一卡通）。
 *
 * ## 两个函数的区别（都保留，别合并）
 *
 * - [isSchoolDomain]：**含**根域 `bit.edu.cn`。判 cookie 归属用这个
 *   —— 根域下发的 cookie 也是学校的。
 * - [isSchoolSubdomain]：**不含**根域，只要真子域。
 *   webvpn 复制 cookie 时用这个：把根域 cookie 复制到 `webvpn.bit.edu.cn`
 *   没有意义，而子域（如 `.seatlib.bit.edu.cn`）的才需要跟着走。
 */
object SchoolDomains {

    /** 学校主域。**写死在这一处**，别在别的地方再拼字符串。 */
    const val ROOT = "bit.edu.cn"

    /**
     * 统一身份认证（CAS）的那几个域 —— 各子系统的登录跳转都会经过它们。
     *
     * ⚠️ 为什么单独列出来：见 [isSsoDomain] 的用途（跨子系统带会话 cookie）。
     * 从代码里看，跳转链实际是 `子系统 → login.bit.edu.cn → sso.bit.edu.cn`。
     */
    private val SSO_HOSTS = listOf("sso.bit.edu.cn", "login.bit.edu.cn")

    /**
     * 是否 CAS（统一身份认证）域。
     *
     * ## 用途：让「跨子系统自动登录」成立
     *
     * 各子系统（一卡通 / 图书馆 / 延河课堂）自己域下的会话 cookie，要靠**完整走一遍
     * CAS 跳转链**才能建立。而 OkHttp 的 cookie jar 是**按域名匹配**的：请求
     * `dkykt.info.bit.edu.cn` 时带不上 CAS 的 cookie，于是被 302 到 CAS 就停住了
     * —— 表现和「没登录」一模一样（v1.9.51 真机取证：BIT101 已登录、课表正常，
     * 但一卡通显示「未登录，点下方登录一卡通」）。
     *
     * 修法是：**在请求 CAS 域时，显式把 CAS 会话 cookie 补上**（课表那套做法的
     * 通版——`DefaultCoursesRepo` 一直是把 cookie 当参数传的，所以它能成功）。
     * 只在该带的地方带，不把 CAS 的 cookie 滥发给任意子系统。
     */
    fun isSsoDomain(host: String?): Boolean {
        val normalized = normalize(host) ?: return false
        return SSO_HOSTS.any { normalized == it || normalized.endsWith(".$it") }
    }

    /** 是否学校域（**含**根域 `bit.edu.cn` 本身）。 */
    fun isSchoolDomain(host: String?): Boolean {
        val normalized = normalize(host) ?: return false
        return normalized == ROOT || normalized.endsWith(".$ROOT")
    }

    /** 是否学校域的**真子域**（**不含**根域 `bit.edu.cn` 本身）。 */
    fun isSchoolSubdomain(host: String?): Boolean {
        val normalized = normalize(host) ?: return false
        return normalized.endsWith(".$ROOT")
    }

    /**
     * 归一化：cookie 的 `domain` 常常带前导点（`.bit.edu.cn`），
     * 且域名大小写不敏感。
     */
    private fun normalize(host: String?): String? =
        host?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
