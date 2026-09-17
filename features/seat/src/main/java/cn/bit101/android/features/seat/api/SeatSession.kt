package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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
}
