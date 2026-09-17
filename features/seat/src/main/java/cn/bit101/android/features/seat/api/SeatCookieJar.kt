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

    /** 以「同名覆盖」语义写入单个 cookie，避免同名 cookie 在 store 里堆积。 */
    fun put(uri: URI, name: String, value: String) {
        cookieManager.cookieStore.get(uri)
            .filter { it.name == name }
            .forEach { cookieManager.cookieStore.remove(uri, it) }

        val cookie = HttpCookie(name, value).apply {
            domain = uri.host
            path = "/"
        }
        cookieManager.cookieStore.add(uri, cookie)
    }

    /** 读取某个 URL 下尚未过期的 cookie 数量（仅用于日志）。 */
    fun count(uri: URI): Int = cookieManager.cookieStore.get(uri).size

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
