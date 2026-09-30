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
