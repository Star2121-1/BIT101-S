package cn.bit101.android.features.common.utils

import cn.bit101.api.model.common.SchoolDomains
import java.net.URI

/**
 * 「这个地址要不要**留在 App 内 WebView**」—— **全 App 只有这一份判据**。
 *
 * ## 为什么必须统一（2026-09-30 SSO 审计）
 *
 * 这条规则以前散在 `WebScreen` 与 `CasLoginScreen` 两处，**判据还不一致**
 * （前者含 `bit101.cn` 与校园网门户，后者只判学校域）。而它是**登录能不能留住**的关键：
 * 统一身份认证（CAS）只有在 App 内的 WebView 里走完，会话 cookie 才会落进
 * App 的 `CookieManager`；一旦被交给系统浏览器，就是**白登**
 * —— 用户看到「明明登录了却还是未登录」（一卡通 v1.9.3 就踩过这个）。
 *
 * ⇒ 以后**新增学校子系统域名，只改这一个文件**。
 *
 * ## 两个函数的适用场合
 *
 * - [isSchoolHost] / [isSchoolSubdomainHost]：只要「学校域」的判据时用。
 * - [isInternal]：WebView 导航策略用 —— 除学校域外，还有 BIT101 自家站与校园网门户。
 */
object InAppWebUrls {

    /** BIT101 自己的站（App 的「网」页就是它）。 */
    const val BIT101_ROOT = "bit101.cn"

    /** 校园网认证门户（深澜 Srun）的主机名 —— 纯内网 HTTP，也必须留在 App 内。 */
    const val CAMPUS_NET_PORTAL_HOST = "10.0.0.55"

    /** 校园网认证门户入口地址。 */
    const val CAMPUS_NET_PORTAL_URL = "http://10.0.0.55/"

    /** [host] 是否学校域（`*.bit.edu.cn`，含根域）。 */
    fun isSchoolHost(host: String?): Boolean = SchoolDomains.isSchoolDomain(host)

    /** [host] 是否学校域的真子域（不含根域）—— 需要区分时用。 */
    fun isSchoolSubdomainHost(host: String?): Boolean = SchoolDomains.isSchoolSubdomain(host)

    /**
     * [url] 是否应当留在 App 内 WebView：学校域 + BIT101 自家站 + 校园网门户。
     *
     * ⚠️ URL 解析失败（含非法字符）一律按**外部链接**处理 —— 宁可交给浏览器，
     * 也不要让 WebView 去加载一个解析不了的东西。
     */
    fun isInternal(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return SchoolDomains.isSchoolDomain(host) ||
            host == BIT101_ROOT || host.endsWith(".$BIT101_ROOT") ||
            host == CAMPUS_NET_PORTAL_HOST
    }

    /** 从 URL 里安全取出小写 host；取不到返回 `null`。 */
    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url).host }.getOrNull()?.lowercase()
    }
}
