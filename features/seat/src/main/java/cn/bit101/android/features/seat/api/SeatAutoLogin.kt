package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.seat.SeatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 座位（seatlib）会话的**静默续期**。
 *
 * ## 为什么需要它（2026-09-21 用户反馈）
 *
 * 用户希望「登录一次之后就不要再授权」。此前会话一过期就只能人工重登：
 * - JWT 虽有持久化，但服务端过期后就是死 token
 * - 冷启动虽有 cookie 静默认证，phpCAS 会话失效后就没了下文
 * - `loginWithCredentials()`（学号+密码直登）**只在手动登录表单里被调用**
 *
 * 而 App 其实**已经加密持久化了学号和密码**（`DefaultLoginRepo.login()` 里
 * `loginStatus.sid/password.set(...)`），完全有能力自动重登 —— 这里就是把线接上。
 *
 * ## 续期顺序（成本从低到高）
 *
 * 1. **cookie 静默认证**（`authenticateSeatlib()`）—— phpCAS 会话还在时成本最低
 * 2. **学号密码重走 CAS**（`loginWithCredentials()`）—— 最可靠，但会打学校 SSO
 *
 * ## ⚠️ 两个必须做的防护
 *
 * - **限流**：学校 CAS 短时间多次登录会触发风控（要求短信二次验证）。
 *   成功后 30s 内不重复；失败后 5 分钟内不再尝试。
 * - **限时**：`SsoLogin` 的短信回调是**无限期挂起等用户输入**的 ——
 *   后台静默续期一旦赶上学校要求二次验证，就会永远卡住。
 *   所以必须 `withTimeoutOrNull`，超时即取消（并清理短信挂起），回退到手动登录。
 */
@Singleton
class SeatAutoLogin @Inject constructor(
    private val seatSession: SeatSession,
    private val loginStatus: LoginStatus,
) {

    private companion object {
        const val TAG = "SeatAutoLogin"

        /** 成功后多久内不重复续期（新 token 是新鲜的，没必要立刻再来） */
        const val SUCCESS_COOLDOWN_MS = 30_000L

        /** 失败后多久内不再尝试（反复打 SSO 会触发学校风控） */
        const val FAIL_BACKOFF_MS = 5 * 60_000L

        /** 凭据登录的限时。正常全程 <10s；超时基本就是撞上了短信二次验证 */
        const val CREDENTIAL_TIMEOUT_MS = 25_000L
    }

    /** 用锁把并发续期合并成一次 —— 多个任务同时发现会话失效时只打一次 SSO */
    private val mutex = Mutex()

    /** 下次允许尝试的时间戳 */
    private var nextAllowedAt = 0L

    /**
     * 尝试静默续期。成功返回新 token；失败/冷却中返回 null（调用方回退到手动登录）。
     *
     * ⚠️ **不会抛异常** —— 续期失败不是要上报的错误，调用方只需要知道成没成。
     */
    suspend fun renew(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < nextAllowedAt) return@withLock null

        // 1. cookie 静默认证
        seatSession.authenticateSeatlib().getOrNull()?.let { result ->
            nextAllowedAt = now + SUCCESS_COOLDOWN_MS
            SeatLog.d(TAG) { "renew ok via cookie" }
            return@withLock result.token
        }

        // 2. cookie 失效 → 学号密码重走 CAS（凭据在登录时已加密持久化）
        val sid = runCatching { loginStatus.sid.get() }.getOrNull().orEmpty()
        val pwd = runCatching { loginStatus.password.get() }.getOrNull().orEmpty()
        if (sid.isBlank() || pwd.isBlank()) {
            // 没有凭据可用的（例如只用 WebView 登过、或用户清除了凭据）→ 只能人工
            nextAllowedAt = now + FAIL_BACKOFF_MS
            SeatLog.d(TAG) { "no stored credentials, cannot silent-relogin" }
            return@withLock null
        }

        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CREDENTIAL_TIMEOUT_MS) {
                seatSession.loginWithCredentials(sid, pwd)
            }
        }
        if (result == null) {
            // 超时 = 大概率撞上短信二次验证（回调在无限期等输入）。
            // 取消挂起并回退：让用户在界面上完成验证，比后台永久卡住好。
            seatSession.cancelSmsChallenge()
            nextAllowedAt = now + FAIL_BACKOFF_MS
            SeatLog.w(TAG, "silent credential login timed out (likely 2FA required)")
            return@withLock null
        }

        val token = result.getOrNull()?.token
        nextAllowedAt = if (token != null) now + SUCCESS_COOLDOWN_MS else now + FAIL_BACKOFF_MS
        if (token != null) SeatLog.d(TAG) { "renew ok via credentials" }
        token
    }
}
