package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.LoginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class LoginResult(
    val token: String = "",
    val name: String = "",
    val studentId: String = ""
)

@Singleton
class SeatSession @Inject constructor(
    private val loginStatus: LoginStatus
) {
    companion object {
        private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"
    }

    private val cookieManager: CookieManager get() = loginStatus.cookieManager
    private val cookieStore get() = cookieManager.cookieStore

    @Volatile
    var jwtToken: String = ""

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                chain.proceed(req)
            }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookies.forEach { cookie ->
                        val httpCookie = HttpCookie(cookie.name, cookie.value)
                        httpCookie.domain = cookie.domain ?: url.host
                        httpCookie.path = cookie.path
                        httpCookie.secure = cookie.secure
                        httpCookie.version = 0
                        if (cookie.expiresAt != Long.MAX_VALUE) {
                            httpCookie.maxAge = maxOf(0, (cookie.expiresAt - System.currentTimeMillis()) / 1000)
                        }
                        val uri = URI.create("${url.scheme}://${url.host}")
                        cookieStore.add(uri, httpCookie)
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val uri = URI.create("${url.scheme}://${url.host}")
                    return cookieStore.get(uri).map { hc ->
                        Cookie.Builder()
                            .name(hc.name)
                            .value(hc.value)
                            .domain(hc.domain)
                            .path(hc.path)
                            .apply { if (hc.secure) secure() }
                            .build()
                    }
                }
            })
            .build()
    }

    /**
     * 静默认证：seatlib 侧已有有效 phpCAS 会话时，直接换取 JWT。
     * 无会话（或会话失效）时返回 failure，由调用方引导走 WebView CAS 登录。
     */
    suspend fun authenticateSeatlib(): Result<LoginResult> = withContext(Dispatchers.IO) {
        try {
            val req = "{}".toRequestBody("application/json".toMediaType())
            val res = client.newCall(
                Request.Builder().url("$SEATLIB_BASE/api/cas/user")
                    .header("Content-Type", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(req).build()
            ).execute()
            val body = res.body?.string() ?: "{}"
            res.close()

            val json = JSONObject(body)
            val member = json.optJSONObject("member")
            if (member != null && !member.isNull("token")) {
                val token = member.optString("token", "")
                if (token.isNotEmpty()) {
                    jwtToken = token
                    return@withContext Result.success(
                        LoginResult(
                            token = token,
                            name = member.optString("name", ""),
                            studentId = member.optString("id", "")
                        )
                    )
                }
            }
            Result.failure(Exception("Session expired"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
