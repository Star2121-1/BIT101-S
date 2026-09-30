package cn.bit101.android.data.net

import cn.bit101.api.model.common.SchoolCookie
import cn.bit101.api.model.common.SchoolDomains
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.time.Instant

/**
 * 学校域 cookie 在「全局 [CookieStore]」与 [SchoolCookie] 之间的读写。
 *
 * ⚠️ 「哪些 cookie 算学校的」这条判据统一在 [SchoolDomains]，**别在这里另写一套**
 * —— 2026-09-30 的 SSO 审计前，这条判据在全 App 被抄了 4 遍且互不一致。
 */
internal object SchoolCookieStore {
    fun replace(
        cookieStore: CookieStore,
        cookies: List<SchoolCookie>,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ) {
        val converted = cookies.mapNotNull { it.toHttpCookie(nowEpochSeconds) }
        require(converted.isNotEmpty()) { "学校登录未返回可用 Cookie" }

        cookieStore.cookies
            .filter { SchoolDomains.isSchoolDomain(it.domain) }
            .forEach { cookieStore.remove(it.uri(), it) }

        converted.forEach { cookieStore.add(it.uri(), it) }
    }

    fun snapshot(
        cookieStore: CookieStore,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): List<SchoolCookie> =
        cookieStore.cookies
            .filter { SchoolDomains.isSchoolDomain(it.domain) && !it.hasExpired() }
            .map { it.toSchoolCookie(nowEpochSeconds) }

    internal fun HttpCookie.toSchoolCookie(nowEpochSeconds: Long): SchoolCookie {
        return SchoolCookie(
            name = name,
            value = value,
            domain = requireNotNull(domain) { "Cookie domain 不能为空" },
            path = path ?: "/",
            secure = secure,
            expiresEpochSeconds = if (maxAge >= 0) nowEpochSeconds + maxAge else null,
        )
    }

    internal fun SchoolCookie.toHttpCookie(nowEpochSeconds: Long): HttpCookie? {
        require(name.isNotBlank()) { "Cookie name 不能为空" }
        require(domain.isNotBlank()) { "Cookie domain 不能为空" }

        val remainingSeconds = expiresEpochSeconds?.minus(nowEpochSeconds)
        if (remainingSeconds != null && remainingSeconds <= 0) return null

        return HttpCookie(name, value).apply {
            domain = this@toHttpCookie.domain
            path = this@toHttpCookie.path.ifBlank { "/" }
            secure = this@toHttpCookie.secure
            maxAge = remainingSeconds ?: -1
        }
    }

    private fun HttpCookie.uri(): URI {
        val host = requireNotNull(domain).trimStart('.')
        return URI("${if (secure) "https" else "http"}://$host${path ?: "/"}")
    }
}
