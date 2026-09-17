package cn.bit101.android.features.seat.api

import android.webkit.CookieManager
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.seat.SeatLog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 座位模块的 HTTP 基础设施：共享的 cookie 桥与 OkHttpClient。
 *
 * 合并前的状况：`SeatApi` / `SeatSession` / `SeatCasLogin` 各自 new 了 OkHttpClient，
 * 超时、协议、UA 三处配置不一致，而且 `trySilentAuth()` **每次调用**都新建一个 client ——
 * 连接池与线程池全部白建。请求之间也无法复用已建立的连接。
 *
 * 现在统一从这里取：[session] 用于登录类请求，[base] 供业务客户端追加认证拦截器。
 */
@Singleton
class SeatHttp @Inject constructor(
    loginStatus: LoginStatus,
) {

    companion object {
        const val BASE = "https://seatlib.bit.edu.cn"
        const val SSO_BASE = "https://sso.bit.edu.cn"

        private const val TAG = "SeatHttp"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /** WebView ↔ OkHttp 的 cookie 桥，三个客户端共享同一份。 */
    internal val cookieJar = SeatCookieJar(loginStatus.cookieManager)

    /** 登录类请求（CAS 换 JWT）使用。单例，复用连接池。 */
    val session: OkHttpClient by lazy { base().build() }

    /**
     * 业务客户端的公共配置。调用方在此基础上追加自己的认证拦截器。
     *
     * 固定 HTTP/1.1：学校网关对 HTTP/2 的支持不稳定，业务请求此前也一直是这么设的。
     */
    fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
            )
        }

    /**
     * 把 WebView 里 CAS 登录得到的 cookie 同步到 OkHttp 使用的共享 cookie store。
     *
     * Android WebView 与 OkHttp 的 cookie 存储彼此独立，不同步的话
     * WebView 里刚建立的 phpCAS 会话在后续 OkHttp 请求上就不存在。
     */
    fun syncWebViewCookies() {
        val webCookieManager = CookieManager.getInstance()
        listOf(BASE, SSO_BASE).forEach urlLoop@{ url ->
            val raw = webCookieManager.getCookie(url)?.takeIf { it.isNotEmpty() } ?: return@urlLoop
            val uri = URI.create(url)
            var count = 0
            raw.split(';').forEach partLoop@{ part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@partLoop
                val name = part.substring(0, eq).trim()
                if (name.isEmpty()) return@partLoop
                cookieJar.put(uri, name, part.substring(eq + 1).trim())
                count++
            }
            SeatLog.d(TAG) { "synced $count cookie(s) from $url" }
        }
    }
}
