package cn.bit101.android.data.repo

import android.database.sqlite.SQLiteException
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.common.AESUtils
import cn.bit101.android.data.common.HashUtils
import cn.bit101.android.data.common.SmsCodeRequestHub
import cn.bit101.android.data.net.SchoolCookieStore
import cn.bit101.android.data.net.SessionCleanup
import cn.bit101.android.data.net.base.APIManager
import cn.bit101.android.data.repo.base.LoginRefreshResult
import cn.bit101.android.data.repo.base.LoginRepo
import cn.bit101.android.data.repo.base.LoginRetryPolicy
import cn.bit101.api.model.common.SmsCodeHandler
import cn.bit101.api.model.http.bit101.PostRegisterDataModel
import cn.bit101.api.model.http.bit101.PostWebvpnVerifyDataModel
import cn.bit101.api.model.http.bit101.PostWebvpnVerifyInitDataModel
import cn.bit101.bitlogin.sso.CaptchaError
import cn.bit101.bitlogin.sso.LoginError as SsoLoginError
import cn.bit101.bitlogin.sso.SmsVerificationError
import cn.bit101.bitlogin.sso.SsoHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject


internal class DefaultLoginRepo @Inject constructor(
    private val apiManager: APIManager,
    private val loginStatus: LoginStatus,
    private val smsCodeRequestHub: SmsCodeRequestHub,
    /**
     * ⚠️ 清登录态**只能走它**：`loginStatus.clear()` 只清 BIT101 自己，
     *    座位侧的 JWT 与任务会留下（详见 [SessionCleanup]）。
     */
    private val sessionCleanup: SessionCleanup,
) : LoginRepo {

    private val api
        get() = apiManager.api

    private val loginMutex = Mutex()

    // 纯校验：学校会话是否有效，不再承担自动重登职责
    private suspend fun checkSchoolLogin() = withContext(Dispatchers.IO) {
        try {
            logApiCall(TAG, "校验学校登录态", result = { "成功, 当前学期: $it" }) {
                api.schoolJxzxehall.getCurrentTerm(
                    cookies = SchoolCookieStore.snapshot(loginStatus.cookieManager.cookieStore),
                    webVpn = loginStatus.webVpn.get(),
                )
            }.isNotBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun checkLogin() = withContext(Dispatchers.IO) {
        loginMutex.withLock {
            // 登录态的**权威定义是学校会话**：BIT101 账号（flwfdd）只是可选能力。
            // 它不可用（服务器被 WAF 封 IP / 宕机）不应把用户判成未登录 ——
            // 否则连直连学校的座位/课表都会一起用不了。
            val schoolValid = try {
                checkSchoolLogin()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

            if (schoolValid) {
                loginStatus.status.set(true)
                return@withLock true
            }

            when (refreshLoginLocked(allowInteractive = true)) {
                LoginRefreshResult.SUCCESS -> true
                LoginRefreshResult.NEEDS_INTERACTIVE -> {
                    loginStatus.status.set(false)
                    false
                }
                LoginRefreshResult.TRANSIENT -> false
                LoginRefreshResult.FAILED -> {
                    withContext(NonCancellable) { sessionCleanup.clearAll() }
                    false
                }
                // checkLogin 已持有 loginMutex，refreshLoginLocked 不可能返回 BUSY，保守按不清理处理
                LoginRefreshResult.BUSY -> false
                // 冷却期内不尝试：既不判失败也不清登录态（用户可能完全离线/服务端暂不可用）
                LoginRefreshResult.COOLDOWN -> false
            }
        }
    }

    override suspend fun refreshLogin(allowInteractive: Boolean): LoginRefreshResult =
        withContext(Dispatchers.IO) {
            loginMutex.withLock {
                refreshLoginLocked(allowInteractive)
            }
        }

    // 非阻塞式刷新：互斥锁空闲时刷新，被持锁流程（checkLogin/login/refreshLogin）占用时立即返回 BUSY，
    // 供 OkHttp 401 拦截器调用，避免在持锁流程内重入同一把非重入锁而死锁
    override suspend fun tryRefreshLogin(): LoginRefreshResult =
        withContext(Dispatchers.IO) {
            if (loginMutex.tryLock()) {
                try {
                    refreshLoginLocked(allowInteractive = false)
                } finally {
                    loginMutex.unlock()
                }
            } else {
                LoginRefreshResult.BUSY
            }
        }

    // 调用方需持有 loginMutex，避免 checkLogin 中重复加锁导致死锁
    private suspend fun refreshLoginLocked(allowInteractive: Boolean): LoginRefreshResult {
        val sid = loginStatus.sid.get()
        val password = loginStatus.password.get()
        if (sid.isEmpty() || password.isEmpty()) {
            return LoginRefreshResult.FAILED
        }

        // ⚠️ 冷却期内**一律不发起学校登录**（含交互式）。
        // 服务端被学校 WAF 封 IP 时，这里的每一次尝试都打在同一个出口 IP 上，
        // 且 allowInteractive 的分支还可能给用户手机发短信 —— 凌晨尤其不能。
        val now = System.currentTimeMillis()
        if (!LoginRetryPolicy.shouldAttempt(now, loginStatus.identityRetryAt.get())) {
            return LoginRefreshResult.COOLDOWN
        }

        val smsCodeHandler = if (allowInteractive) smsCodeRequestHub.createSmsCodeHandler() else null

        try {
            loginSchool(sid, password, smsCodeHandler)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只有**学校登录失败**才决定刷新结果；BIT101 的失败不参与分类。
            val result = e.classifyRefreshFailure()
            recordIdentityFailure(result, now)
            return result
        }

        // 学校会话已恢复 ⇒ 清零退避窗口，下次失败重新从 24h 起算
        loginStatus.identityAttempts.set(0)
        loginStatus.identityRetryAt.set(0L)

        // 学校会话已恢复 ⇒ 登录态可用。BIT101 账号（flwfdd）是可选能力，best-effort：
        // 它挂了（服务器被 WAF 封 IP 等）不应把学校会话也判失败、更不应清除。
        loginStatus.status.set(true)
        try {
            loginBIT101(sid, password)
            loginStatus.bit101Available.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 忽略：学校会话有效即可（仅社区/成绩等 BIT101 功能受影响）
            loginStatus.bit101Available.value = false
        }

        return LoginRefreshResult.SUCCESS
    }

    /**
     * 记录一次学校身份验证失败，并据此设置冷却截止时间。
     *
     * - **[NEEDS_INTERACTIVE] 不冷却**：用户正在输短信/图形验证码，冷却会把人挡在门外
     * - 其它（[TRANSIENT] / [FAILED]）统一按 [LoginRetryPolicy] 指数退避
     *
     * ⚠️ 这里拿不到服务端的 `Retry-After`（BIT-Login SDK 的异常里只有 statusCode），
     * 所以 `retryAfter` 传 null；将来若 SDK 暴露该头，直接透传即可，策略层已支持。
     */
    private suspend fun recordIdentityFailure(result: LoginRefreshResult, now: Long) {
        if (result == LoginRefreshResult.NEEDS_INTERACTIVE) return
        val attempts = loginStatus.identityAttempts.get() + 1
        loginStatus.identityAttempts.set(attempts)
        loginStatus.identityRetryAt.set(
            LoginRetryPolicy.retryAt(now, attempts, retryAfter = null, identity = true)
        )
    }

    // 依据 BIT-Login SDK 异常类型对刷新失败分类，遍历 cause 链以便穿透 SDK 的包装
    private fun Throwable.classifyRefreshFailure(): LoginRefreshResult {
        var current: Throwable? = this
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            when (current) {
                is CancellationException, is SmsVerificationError, is CaptchaError -> {
                    return LoginRefreshResult.NEEDS_INTERACTIVE
                }
                is SsoHttpException -> {
                    if (current.statusCode == 429 || current.statusCode in 500..599) {
                        return LoginRefreshResult.TRANSIENT
                    }
                    return LoginRefreshResult.FAILED
                }
                is SsoLoginError -> {
                    if (current.statusCode == 429 || current.statusCode in 500..599) {
                        return LoginRefreshResult.TRANSIENT
                    }
                    return LoginRefreshResult.FAILED
                }
                is IOException -> return LoginRefreshResult.TRANSIENT
            }
            current = current.cause
        }
        return LoginRefreshResult.FAILED
    }

    private suspend fun loginSchool(
        username: String,
        password: String,
        smsCodeHandler: SmsCodeHandler? = null,
    ) = withContext(Dispatchers.IO) {
        val result = logApiCall(TAG, "学校统一身份认证登录(sid=${username.take(2)}****)", result = { "成功, webVpn=${it.webVpn}" }) {
            api.schoolLogin.login(username, password, smsCodeHandler)
        }
        SchoolCookieStore.replace(loginStatus.cookieManager.cookieStore, result.cookies)
        apiManager.switch(result.webVpn)
        true
    }

    private suspend fun loginBIT101(username: String, password: String) = withContext(Dispatchers.IO) {
        val initData = api.user.webVpnVerifyInit(
            PostWebvpnVerifyInitDataModel.Body(
                sid = username,
            )
        ).body() ?: throw Exception("init bit101 login error")

        val salt = initData.salt
        val execution = initData.execution
        val cookie = initData.cookie
        val encryptedPassword = AESUtils.encryptPassword(password, salt)

        val verifyData = api.user.webVpnVerify(
            PostWebvpnVerifyDataModel.Body(
                sid = username,
                password = encryptedPassword,
                execution = execution,
                cookie = cookie,
                salt = salt,
                captcha = ""
            )
        ).body() ?: throw Exception("get webVpnVerify response error")
        val md5Password = HashUtils.md5(password)
        val token = verifyData.token
        val code = verifyData.code

        val fakeCookie = api.user.register(
            PostRegisterDataModel.Body(
                password = md5Password,
                token = token,
                code = code,
                loginMode = true
            )
        ).body()?.fakeCookie ?: throw Exception("get register response error")

        loginStatus.fakeCookie.set(fakeCookie)

        true
    }

    override suspend fun login(
        username: String,
        password: String,
        smsCodeHandler: SmsCodeHandler?,
    ) = withContext(Dispatchers.IO) {
        loginMutex.withLock {
            sessionCleanup.clearAll()
            try {
                loginSchool(username, password, smsCodeHandler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { sessionCleanup.clearAll() }
                throw e
            }

            // 学校登录成功 ⇒ 用户即可正常使用座位/课表等直连学校的功能。
            // 随后的 BIT101 账号登录（flwfdd 代理 CAS）是 best-effort：
            // 服务器被 WAF 封 IP / 宕机时，不应让整个登录失败，更不应清掉学校会话。
            loginStatus.sid.set(username)
            loginStatus.password.set(password)
            loginStatus.status.set(true)
            try {
                loginBIT101(username, password)
                loginStatus.bit101Available.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 忽略：BIT101 侧不可用时仅社区/成绩等功能受影响
                loginStatus.bit101Available.value = false
            }
            true
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        loginMutex.withLock {
            withContext(NonCancellable) { sessionCleanup.clearAll() }
        }
    }

    override suspend fun <T> doOperationRequiresLogin(operation: suspend () -> T): T {
        return try {
            operation()
        } catch (e: Exception) {
            if (e is CancellationException || e is IllegalArgumentException || e is SQLiteException) {
                throw e
            }

            // ⚠️ 只有「会话明确失效」才值得重新登录。
            // 超时 / 网络不可达 / 5xx / 429 / 解析错误重登没用，只会在服务端被封期间放大 CAS 请求量。
            // 403 也不算：它常是「权限不足」的页面，当会话过期处理会造成死循环重登。
            if (!e.isAuthenticationFailure()) throw e

            // 冷却期内不尝试，直接抛原异常（不吞、不重登）
            val now = System.currentTimeMillis()
            if (!LoginRetryPolicy.shouldAttempt(now, loginStatus.identityRetryAt.get())) throw e

            val checkLoginSuccess = try {
                checkLogin()
            } catch (checkError: Exception) {
                if (checkError is CancellationException) throw checkError
                throw IllegalStateException("check login failed", e).apply {
                    addSuppressed(checkError)
                }
            }

            if (checkLoginSuccess) {
                operation()
            } else {
                throw IllegalStateException("check login failed", e)
            }
        }
    }

    /**
     * 判断异常是否表示**会话真的失效**。
     *
     * 只认 HTTP 401：
     * - 5xx / 429 / [IOException] / 超时 → 服务端或网络问题，重登无意义（还会放大流量）
     * - 403 → 多半是「权限不足」的页面，当会话过期处理会死循环重登
     *   （上游 1.4.4 专门写了一条 `permission page is not converted into a login retry`）
     */
    private fun Throwable.isAuthenticationFailure(): Boolean {
        var current: Throwable? = this
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is HttpException && current.code() == 401) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val TAG = "LoginRepo"
    }
}
