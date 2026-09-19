package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatLog
import cn.bit101.bitlogin.login.SsoLogin
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class LoginResult(
    val token: String = "",
    val name: String = "",
    val studentId: String = ""
)

/**
 * 解析 `/api/cas/user` 的响应体，返回 null 表示会话不存在或响应中没有 token。
 *
 * ⚠️ `member` 是 **JSON 对象而非数组**。早期版本用 `optJSONArray("member")` 取，
 * 恒为 null，表现为「登录成功但拿不到 token」。这里固定用 `optJSONObject`。
 *
 * 抽成顶层函数是为了能直接做单元测试（见 `src/test/.../CasResponseTest.kt`）。
 */
internal fun parseCasUser(body: String): LoginResult? {
    val member = runCatching { JSONObject(body).optJSONObject("member") }.getOrNull() ?: return null
    if (member.isNull("token")) return null
    val token = member.optString("token", "")
    if (token.isEmpty()) return null
    return LoginResult(
        token = token,
        name = member.optString("name", ""),
        studentId = member.optString("id", "")
    )
}

/**
 * seatlib 的会话管理：把 CAS 登录结果换成 JWT。
 *
 * - [authenticateSeatlib] 静默认证 —— seatlib 侧已有有效 phpCAS 会话时无需任何交互；
 * - [exchangeTicket] 用 WebView 拦截到的 `cas=` code 显式换取；
 * - [loginWithCredentials] 学号 + 密码直登（纯 HTTP，不经 WebView）。
 *
 * token 的权威副本在 [SeatApi.token]（负责持久化），这里不再另存一份。
 */
@Singleton
class SeatSession @Inject constructor(
    private val seatHttp: SeatHttp,
    private val smsHub: SeatSmsChallengeHub,
) {

    companion object {
        private const val TAG = "SeatSession"
        private const val CAS_USER_PATH = "/api/cas/user"

        /** phpCAS 的 service 地址 —— 必须与 seatlib 服务端登记的一致。 */
        private const val CAS_SERVICE = "https://seatlib.bit.edu.cn/api/cas/cas"

        /** phpCAS 内部 code 出现在重定向 Location 的查询串（或 hash 路由）里。 */
        private val CAS_CODE_REGEX = Regex("""[?&]cas=([A-Za-z0-9.\-]+)""")

        /** 票据校验的跳数上限，防异常重定向环。 */
        private const val MAX_CAS_HOPS = 8
    }

    /** 静默认证。失败返回 failure，由调用方引导重新授权。 */
    suspend fun authenticateSeatlib(): Result<LoginResult> = exchange(seatHttp.session, ticket = null)

    /** 用 CAS code 换取 JWT。 */
    suspend fun exchangeTicket(ticket: String): Result<LoginResult> =
        exchange(seatHttp.session, ticket)

    /** 界面侧提交短信验证码。 */
    fun submitSmsCode(code: String) = smsHub.submit(code)

    /** 界面侧取消二次验证。 */
    fun cancelSmsChallenge() = smsHub.cancel()

    /** 二次验证挑战状态，供界面展示输入框。 */
    val smsChallenge = smsHub.challenge

    private suspend fun exchange(client: OkHttpClient, ticket: String?): Result<LoginResult> =
        withContext(Dispatchers.IO) {
            runCatching { postCasUser(client, ticket) }
        }

    /**
     * POST `/api/cas/user` 换取 JWT。
     *
     * `ticket = null` 表示静默认证（依赖 client 里已有的 phpCAS cookie）。
     */
    private fun postCasUser(client: OkHttpClient, ticket: String?): LoginResult {
        val payload = if (ticket != null) JSONObject().put("cas", ticket) else JSONObject()
        val request = Request.Builder()
            .url(SeatHttp.BASE + CAS_USER_PATH)
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            val result = parseCasUser(body) ?: throw IOException("会话已过期或未登录")
            SeatLog.d(TAG) {
                "cas exchange ok (${if (ticket != null) "code" else "silent"}), " +
                    "token=${SeatLog.mask(result.token)}"
            }
            return result
        }
    }

    // ─────────────────────────────────────────────
    // 账号密码直登（纯 HTTP，不经 WebView）
    // ─────────────────────────────────────────────

    /**
     * 用学号 + 密码完成学校统一身份认证并换取 JWT。
     *
     * 背景：学校 SSO 登录页（sso.bit.edu.cn/cas/login）在 WebView 里不渲染表单
     * （2026-09-18 真机复测：页脚渲染、Angular 应用在跑，但表单区域空白），
     * 故走纯 HTTP 路线。
     *
     * CAS 部分交给官方 [SsoLogin]（BIT-Login 库）：它实现了学校 SSO 的完整协议 ——
     * 风控指纹（USTC）、`protected` 接口的 CSRF 头、加密取手机号、以及**短信二次验证**。
     * 早期这里手写「取 salt → AES 加密密码 → POST 表单」，缺了风控与二次验证，
     * 一旦学校触发风控（表现为密码正确也返回 200 + 二次验证选择页）就只能报错退出。
     *
     * 流程：
     *  1. `SsoLogin.login(callbackUrl = CAS_SERVICE)` 完成 CAS 认证；若学校要求二次验证，
     *     库会发短信并通过 [SeatSmsChallengeHub] 挂起，等界面输入验证码；
     *  2. 拿返回的 phpCAS 回调地址，在**隔离会话**里跟随重定向取到 `cas=` code；
     *  3. 用该 code 换 JWT。
     */
    suspend fun loginWithCredentials(username: String, password: String): Result<LoginResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sso = SsoLogin(
                    smsCodeCallback = { context ->
                        SeatLog.d(TAG) {
                            "second factor required, maskedPhone=${context.maskedPhone}, " +
                                "purpose=${context.purpose}"
                        }
                        smsHub.awaitCode(context.maskedPhone, context.purpose)
                    }
                )
                val ssoResult = sso.login(
                    username = username,
                    password = password,
                    callbackUrl = CAS_SERVICE,
                )
                SeatLog.d(TAG) { "cas ok, ticket=${SeatLog.mask(ssoResult.ticket ?: "")}" }

                // ⚠️ 必须用隔离的 cookie jar：共享 jar 里的陈旧 phpCAS/WebView 会话
                // 会让 seatlib 的票据校验走岔（302 到 authserver 登录页而非下发 cas code，
                // 2026-09-18 真机实测）。JAVA 侧可用实现同样是「每次登录全量清 cookie」。
                val jar = IsolatedCookieJar()
                val http = seatHttp.base().cookieJar(jar).build()

                val code = followForCasCode(http, ssoResult.callback)
                SeatLog.d(TAG) { "phpCAS code=${SeatLog.mask(code)}" }

                postCasUser(http, code).also {
                    SeatLog.d(TAG) { "credential login ok, token=${SeatLog.mask(it.token)}" }
                }
            }
        }

    /**
     * 跟随 phpCAS 回调链，从中提取 `cas=` code。
     *
     * 手工跟跳而不是交给 OkHttp 自动重定向：code 往往出现在 **Location 头**的
     * hash 片段里（`.../h5/#/login?cas=xxx`），而 URL 片段不会随请求发送，
     * 自动跟随后就再也看不到了。
     */
    private fun followForCasCode(client: OkHttpClient, startUrl: String): String {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var next: String? = startUrl
        var hops = 0
        while (!next.isNullOrEmpty() && hops++ < MAX_CAS_HOPS) {
            extractCasCode(next)?.let { return it }

            val request = Request.Builder().url(next).get().build()
            val response = noRedirect.newCall(request).execute()
            val location = response.header("Location")
            val code = response.code
            val resolved = location?.let { response.request.url.resolve(it)?.toString() ?: it }
            response.close()

            extractCasCode(resolved.orEmpty())?.let { return it }
            if (resolved.isNullOrEmpty()) {
                throw IOException("phpCAS 票据校验未完成（HTTP $code）")
            }
            next = resolved
        }
        throw IOException("phpCAS 重定向层数过多（>$MAX_CAS_HOPS）")
    }

    /** 从 URL（含 `#` 后的 hash 查询串）里提取 phpCAS code。 */
    private fun extractCasCode(url: String): String? =
        CAS_CODE_REGEX.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

/**
 * 凭据登录专用的内存 CookieJar，与共享的 WebView/OkHttp cookie 存储完全隔离。
 *
 * 为什么不直接清共享 jar：它与 BIT101 学校登录共用底层存储，
 * 全量清理会把 BIT101 会话一起踢掉；而这份流程也不需要把 phpCAS
 * 会话留在共享侧 —— 拿到 JWT 后座位接口全部走 Authorization 头。
 */
private class IsolatedCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store["${it.name}|${it.domain}|${it.path}"] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.filter { it.matches(url) }
}
