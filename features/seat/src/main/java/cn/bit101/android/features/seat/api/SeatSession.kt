package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
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
 * 两条路径共用同一套处理：
 * - [authenticateSeatlib] 静默认证 —— seatlib 侧已有有效 phpCAS 会话时无需任何交互；
 * - [exchangeTicket] 用 WebView 拦截到的 `cas=` ticket 显式换取。
 *
 * token 的权威副本在 [SeatApi.token]（负责持久化），这里不再另存一份。
 */
@Singleton
class SeatSession @Inject constructor(
    private val seatHttp: SeatHttp,
) {

    companion object {
        private const val TAG = "SeatSession"
        private const val CAS_USER_PATH = "/api/cas/user"

        /** phpCAS 的 service 地址 —— 必须与 seatlib 服务端登记的一致。 */
        private const val CAS_SERVICE = "https://seatlib.bit.edu.cn/api/cas/cas"

        /** phpCAS 内部 code 出现在最终 hash 路由的查询串里。 */
        private val CAS_CODE_REGEX = Regex("""[?&]cas=([A-Za-z0-9.\-]+)""")
    }

    /** 静默认证。失败返回 failure，由调用方引导走 WebView 登录。 */
    suspend fun authenticateSeatlib(): Result<LoginResult> = exchange(ticket = null)

    /** 用 CAS ticket 换取 JWT。 */
    suspend fun exchangeTicket(ticket: String): Result<LoginResult> = exchange(ticket)

    private suspend fun exchange(ticket: String?): Result<LoginResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = if (ticket != null) JSONObject().put("cas", ticket) else JSONObject()
            val request = Request.Builder()
                .url(SeatHttp.BASE + CAS_USER_PATH)
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = seatHttp.session.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            response.close()
            if (response.code != 200) throw IOException("HTTP ${response.code}")

            val result = parseCasUser(body) ?: throw IOException("会话已过期或未登录")
            SeatLog.d(TAG) {
                "cas exchange ok (${if (ticket != null) "ticket" else "silent"}), " +
                    "token=${SeatLog.mask(result.token)}"
            }
            result
        }
    }

    // ─────────────────────────────────────────────
    // 账号密码直登（纯 HTTP，不经 WebView）
    // ─────────────────────────────────────────────

    /**
     * 用学号 + 密码直接完成学校 CAS 认证并换取 JWT。
     *
     * 背景：学校 SSO 登录页（sso.bit.edu.cn/cas/login）在 WebView 里不渲染表单
     * （2026-09-18 真机复测：页脚渲染、Angular 应用在跑，但表单区域空白），
     * 而「工作区 JAVA 项目」用纯 HTTP 模拟同一流程在真机上实测可用，遂移植该方案。
     *
     * 流程：
     *  1. GET CAS 登录页，从 HTML 里取 `login-croypto`（AES salt）与 `login-page-flowkey`（execution）
     *  2. 密码与空 captcha 载荷用 AES/ECB/PKCS5（key=Base64 解码的 salt）加密
     *  3. 不跟随重定向 POST 表单，302 的 Location 即 ticket URL
     *  4. 再跟两跳重定向，从最终 hash 路由取 phpCAS code
     *  5. POST `/api/cas/user` 用 code 换 JWT（复用 [exchange]，cookie 由共享 jar 持有）
     */
    suspend fun loginWithCredentials(username: String, password: String): Result<LoginResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                // ⚠️ 必须用隔离的 cookie jar：共享 jar 里的陈旧 phpCAS/WebView 会话
                // 会让 seatlib 的票据校验走岔（302 到 authserver 登录页而非下发 cas code，
                // 2026-09-18 真机实测）。JAVA 侧可用实现同样是「每次登录全量清 cookie」。
                val jar = IsolatedCookieJar()
                val http = seatHttp.base().cookieJar(jar).build()
                val noRedirect = http.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val loginUrl = "${SeatHttp.SSO_BASE}/cas/login?service=" +
                    URLEncoder.encode(CAS_SERVICE, "UTF-8")

                // 1. 登录页 → salt + execution
                val pageRes = http.newCall(
                    Request.Builder().url(loginUrl).get().build()
                ).execute()
                val html = pageRes.body?.string().orEmpty()
                val pageCode = pageRes.code
                pageRes.close()
                if (pageCode != 200) throw IOException("CAS 登录页 HTTP $pageCode")

                val salt = findPageValue(html, "login-croypto")
                    ?: throw IOException("登录页解析失败：找不到 salt")
                val execution = findPageValue(html, "login-page-flowkey")
                    ?: throw IOException("登录页解析失败：找不到 execution")
                SeatLog.d(TAG) {
                    "cas page ok, salt=${SeatLog.mask(salt)}, exec=${SeatLog.mask(execution)}"
                }

                // 2. 表单（AES 加密密码），不跟随重定向
                val form = FormBody.Builder()
                    .add("username", username)
                    .add("password", aesEncrypt(password, salt))
                    .add("execution", execution)
                    .add("croypto", salt)
                    .add("captcha_payload", aesEncrypt("{}", salt))
                    .add("type", "UsernamePassword")
                    .add("geolocation", "")
                    .add("captcha_code", "")
                    .add("_eventId", "submit")
                    .build()

                val postRes = noRedirect.newCall(
                    Request.Builder().url(loginUrl).post(form).build()
                ).execute()
                val ticketUrl = postRes.header("Location")
                val postCode = postRes.code
                postRes.body?.close()
                if (postCode != 302 || ticketUrl.isNullOrEmpty()) {
                    throw IOException("CAS 认证失败（HTTP $postCode），请检查学号密码")
                }

                // 3. 跟随重定向链取 phpCAS code（票据校验 → 会话检查 → hash 路由）
                val hop1Res = noRedirect.newCall(
                    Request.Builder().url(ticketUrl).get().build()
                ).execute()
                val hop1Loc = hop1Res.header("Location")
                hop1Res.body?.close()
                if (hop1Res.code != 302 || hop1Loc.isNullOrEmpty()) {
                    throw IOException("phpCAS 票据校验失败（HTTP ${hop1Res.code}）")
                }

                val hop2Res = noRedirect.newCall(
                    Request.Builder().url(hop1Loc).get().build()
                ).execute()
                val hop2Loc = hop2Res.header("Location")
                hop2Res.body?.close()

                val code = hop2Loc?.let(::extractCasCode)
                    ?: extractCasCode(hop1Loc)
                    ?: throw IOException("重定向链中未找到 cas code：${hop2Loc ?: hop1Loc}")
                SeatLog.d(TAG) { "phpCAS code=${SeatLog.mask(code)}" }

                // 4. code 换 JWT（同一隔离 jar，携带链路中建立的 phpCAS 会话）
                val payload = JSONObject().put("cas", code)
                val jwtRes = http.newCall(
                    Request.Builder()
                        .url(SeatHttp.BASE + CAS_USER_PATH)
                        .header("Content-Type", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                val jwtBody = jwtRes.body?.string() ?: "{}"
                jwtRes.close()
                if (jwtRes.code != 200) throw IOException("HTTP ${jwtRes.code}")

                val result = parseCasUser(jwtBody)
                    ?: throw IOException("换取 JWT 失败：${jwtBody.take(120)}")
                SeatLog.d(TAG) { "credential login ok, token=${SeatLog.mask(result.token)}" }
                result
            }
        }

    /** 从 URL（含 `#` 后的 hash 查询串）里提取 phpCAS code。 */
    private fun extractCasCode(url: String): String? =
        CAS_CODE_REGEX.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** CAS 登录页把值放在 `<p id="...">value</p>` 里。 */
    private fun findPageValue(html: String, id: String): String? =
        Regex("""id="$id">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\n", "")?.replace("\r", "")
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** 学校 CAS 的密码提交格式：AES/ECB/PKCS5，key 为 Base64 解码的 salt。 */
    private fun aesEncrypt(plain: String, salt: String): String {
        val keyBytes = Base64.getDecoder().decode(salt)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
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
