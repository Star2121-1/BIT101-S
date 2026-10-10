package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.seat.SeatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * ## ⚠️ 两条必须守住的线
 *
 * - **限流**：学校 CAS 短时间多次登录会触发风控（要求短信二次验证）。
 *   成功后 30s 内不重复；失败后 5 分钟内不再尝试。
 * - **谁有权打 SSO**：调用方必须显式声明 [renew] 的 `allowCredentials`。
 *   只有用户明确发起授权的路径才允许走「学号密码重走 CAS」——
 *   那一步会被学校风控触发**真实短信**，自动路径上没人输验证码，短信就白发了。
 *   「等验证码」不设总超时（用户要掏手机），限时交给 [SeatSmsChallengeHub]。
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
    }

    /** 用锁把并发续期合并成一次 —— 多个任务同时发现会话失效时只打一次 SSO */
    private val mutex = Mutex()

    /** 下次允许尝试的时间戳 */
    private var nextAllowedAt = 0L

    /**
     * 尝试续期。成功返回新 token；失败/冷却中返回 null（调用方回退到手动登录）。
     *
     * ⚠️⚠️ [allowCredentials] 决定**允不允许走「学号密码重走 CAS」这一步**。
     * 那一步会真的打学校 SSO，而**风控触发时学校会直接给你发一条短信验证码**。
     * 所以规则是：**只有「用户明确发起授权」的路径才传 true**
     * （点「授权座位系统」/ 点预约）；一切**自动**路径都传 false ——
     * 进页面的自动续期、token 失效的自动续期、业务请求的 401 重试。
     * 那些路径要么没有界面、要么用户没要求，短信发出去也没人输（前台服务甚至能在
     * 锁屏时触发），用户只会收到一条**来路不明的验证码**（真机反馈）。
     *
     * ⚠️ **不会抛异常** —— 续期失败不是要上报的错误，调用方只需要知道成没成。
     */
    suspend fun renew(allowCredentials: Boolean): String? = mutex.withLock {
        val now = System.currentTimeMillis()

        // 1. cookie 静默认证 —— 只打 seatlib，不打学校 SSO，**永远不会发短信**，
        //    所以它不受「凭据路径的限流」约束，任何时候都可以先试。
        seatSession.authenticateSeatlib().getOrNull()?.let { result ->
            nextAllowedAt = now + SUCCESS_COOLDOWN_MS
            SeatLog.d(TAG) { "renew ok via cookie" }
            return@withLock result.token
        }

        if (!allowCredentials) {
            SeatLog.d(TAG) { "cookie silent auth failed; this path must not hit SSO" }
            return@withLock null
        }

        // 2. 凭据直登会真的打学校 SSO（可能触发风控 → 发短信），必须限流
        if (now < nextAllowedAt) return@withLock null

        val sid = runCatching { loginStatus.sid.get() }.getOrNull().orEmpty()
        val pwd = runCatching { loginStatus.password.get() }.getOrNull().orEmpty()
        if (sid.isBlank() || pwd.isBlank()) {
            // 没有凭据可用的（例如只用 WebView 登过、或用户清除了凭据）→ 只能人工
            nextAllowedAt = now + FAIL_BACKOFF_MS
            SeatLog.d(TAG) { "no stored credentials, cannot silent-relogin" }
            return@withLock null
        }

        // ⚠️ 这里**刻意不设总超时**：学校要求短信二次验证时，登录流程会挂起等用户输入，
        //    而用户掏手机看验证码本来就要一会儿 —— 早先那个 25s 固定超时会在用户还没输完
        //    时就把整次登录取消、验证码随之作废（真机反馈：「收到了验证码却没人让我们输」）。
        //    「等验证码」的时限交给 [SeatSmsChallengeHub]（5 分钟，界面上还可手动取消），
        //    网络段由各自 HTTP 客户端的超时兜底。
        val result = withContext(Dispatchers.IO) { seatSession.loginWithCredentials(sid, pwd) }

        val token = result.getOrNull()?.token
        nextAllowedAt = if (token != null) now + SUCCESS_COOLDOWN_MS else now + FAIL_BACKOFF_MS
        if (token != null) SeatLog.d(TAG) { "renew ok via credentials" }
        token
    }
}
