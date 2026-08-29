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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AESUtils {
    fun encryptPassword(password: String, salt: String): String {
        val keyBytes = Base64.getDecoder().decode(salt)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8)))
    }
}

data class LoginResult(
    val token: String = "",
    val name: String = "",
    val studentId: String = ""
)

class SeatSession @JvmOverloads constructor(
    private val loginStatus: LoginStatus
) {
    companion object {
        private const val CAS_BASE = "https://sso.bit.edu.cn/cas"
        private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"
        private const val PHP_CAS_SERVICE = "https://seatlib.bit.edu.cn/api/cas/cas"
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

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun login(username: String, password: String): Result<LoginResult> = withContext(Dispatchers.IO) {
        try {
            cookieStore.removeAll()

            val serviceEnc = java.net.URLEncoder.encode(PHP_CAS_SERVICE, "UTF-8")
            val loginUrl = "$CAS_BASE/login?service=$serviceEnc"

            val initRes = client.newCall(Request.Builder().url(loginUrl).get().build()).execute()
            val initHtml = initRes.body?.string() ?: return@withContext Result.failure(Exception("① body null"))
            initRes.close()

            val salt = findFieldValue(initHtml, "login-croypto") ?: ""
            val execution = findFieldValue(initHtml, "login-page-flowkey") ?: ""

            if (salt.isEmpty() || execution.isEmpty()) {
                return@withContext Result.failure(Exception("① parse fail salt=$salt exec=$execution"))
            }

            val encPass = AESUtils.encryptPassword(password, salt)
            val encCap = AESUtils.encryptPassword("{}", salt)

            val body = okhttp3.FormBody.Builder()
                .add("username", username)
                .add("password", encPass)
                .add("execution", execution)
                .add("croypto", salt)
                .add("captcha_payload", encCap)
                .add("type", "UsernamePassword")
                .add("geolocation", "")
                .add("captcha_code", "")
                .add("_eventId", "submit")
                .build()

            val loginRes = noRedirectClient.newCall(
                Request.Builder().url(loginUrl).post(body).build()
            ).execute()
            val ticketUrl = loginRes.header("Location")
            val loginCode = loginRes.code
            loginRes.body?.close()

            if (loginCode != 302 || ticketUrl.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("③ CAS fail HTTP $loginCode"))
            }

            val casRes = noRedirectClient.newCall(
                Request.Builder().url(ticketUrl).get().build()
            ).execute()
            val casLoc = casRes.header("Location")
            val casCode = casRes.code
            casRes.body?.close()

            if (casCode != 302 || casLoc.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("④ phpCAS fail HTTP $casCode"))
            }

            val sessRes = noRedirectClient.newCall(
                Request.Builder().url(casLoc).get().build()
            ).execute()
            val sessLoc = sessRes.header("Location")
            sessRes.body?.close()

            if (sessLoc == null) {
                return@withContext Result.failure(Exception("④b session check failed"))
            }

            val codeRegex = Regex("""cas=([a-f0-9]{32})""")
            val phpcasCode = codeRegex.find(sessLoc)?.groupValues?.getOrNull(1)
            if (phpcasCode == null) {
                return@withContext Result.failure(Exception("⑤ failed to extract phpCAS code from: $sessLoc"))
            }

            val jwtReq = JSONObject().put("cas", phpcasCode).toString()
                .toRequestBody("application/json".toMediaType())

            val userRes = client.newCall(
                Request.Builder().url("$SEATLIB_BASE/api/cas/user")
                    .header("Content-Type", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(jwtReq).build()
            ).execute()
            val userBody = userRes.body?.string() ?: "{}"
            userRes.close()

            val json = JSONObject(userBody)
            val member = json.optJSONObject("member")
            if (member != null && !member.isNull("token")) {
                val token = member.optString("token", "")
                if (token.isNotEmpty()) {
                    jwtToken = token
                    return@withContext Result.success(LoginResult(token = token, name = member.optString("name", ""), studentId = member.optString("id", "")))
                }
            }

            Result.failure(Exception("⑥ api/cas/user empty: $userBody"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                    return@withContext Result.success(LoginResult(token = token, name = member.optString("name", ""), studentId = member.optString("id", "")))
                }
            }
            Result.failure(Exception("Session expired"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findFieldValue(html: String, id: String): String? {
        val regex = Regex("""id="$id">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(html)?.groupValues?.getOrNull(1)
            ?.replace("\n", "")?.replace("\r", "")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
