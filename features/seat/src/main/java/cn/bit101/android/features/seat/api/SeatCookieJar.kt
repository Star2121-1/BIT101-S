package cn.bit101.android.features.seat.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/**
 * OkHttp `CookieJar` ←→ `java.net.CookieManager` 的桥。
 *
 * 为什么需要它：CAS 登录在 WebView 里完成、后续业务请求走 OkHttp，
 * 而两者的 cookie 存储彼此独立（[okhttp3.CookieJar] 只服务 OkHttp），
 * 所以必须显式互转，见 [SeatHttp.syncWebViewCookies]。
 *
 * 此前这段转换在 `SeatApi` / `SeatSession` / `SeatCasLogin` **三处各写了一遍**，
 * 实现细节还有分歧（`maxAge` 的类型、`domain`/`path` 的兜底、是否设置 version）。
 * 统一到这里，避免三份实现各自演化。
 *
 * ⚠️ 本类只负责 **OkHttp 桥**。「WebView → cookie store 的同步」**不在**这里 ——
 * 那份已统一到 `:data` 的 `WebViewCookieSync`，见 [SeatHttp.syncWebViewCookies]。
 */
internal class SeatCookieJar(private val cookieManager: CookieManager) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val uri = uriOf(url)
        cookies.forEach { cookie ->
            cookieManager.cookieStore.add(uri, cookie.toHttpCookie(url.host))
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookieManager.cookieStore.get(uriOf(url)).map { it.toOkHttpCookie(url.host) }

    // ⚠️ 这里曾有 `put()`（同名覆盖写单个 cookie）与 `count()`（数 cookie 个数）。
    //    2026-09-30 的 SSO 审计把「WebView → cookie store 的同步」统一到
    //    `:data` 的 `WebViewCookieSync`（那份是**逐条容错**的），这两个方法就没了调用者。
    //    别再往这个类里加 cookie 写入逻辑 —— 共享件在 data 层，座位侧只留 OkHttp 桥。

    private fun uriOf(url: HttpUrl): URI = URI.create("${url.scheme}://${url.host}")
}

private fun Cookie.toHttpCookie(fallbackHost: String): HttpCookie =
    HttpCookie(name, value).apply {
        domain = domain ?: fallbackHost
        path = path
        secure = secure
        version = 0
        // expiresAt == Long.MAX_VALUE 表示 session cookie，不应设置 maxAge
        if (expiresAt != Long.MAX_VALUE) {
            maxAge = maxOf(0L, (expiresAt - System.currentTimeMillis()) / 1000)
        }
    }

private fun HttpCookie.toOkHttpCookie(fallbackHost: String): Cookie =
    Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain ?: fallbackHost)
        .path(path ?: "/")
        .apply { if (secure) secure() }
        .build()
