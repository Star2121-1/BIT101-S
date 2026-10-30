package cn.bit101.android.data.repo

import cn.bit101.android.config.common.SettingItem
import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.config.user.base.SeatLoginStatus
import cn.bit101.android.data.common.SmsCodeRequestHub
import cn.bit101.android.data.net.SessionCleanup
import cn.bit101.android.data.net.base.APIManager
import cn.bit101.android.data.repo.base.LoginRefreshResult
import cn.bit101.api.Bit101Api
import cn.bit101.api.model.common.SchoolCookie
import cn.bit101.api.model.common.SchoolLoginResult
import cn.bit101.api.model.http.bit101.PostLoginDataModel
import cn.bit101.api.model.http.bit101.PostRegisterDataModel
import cn.bit101.api.model.http.bit101.PostWebvpnVerifyDataModel
import cn.bit101.api.model.http.bit101.PostWebvpnVerifyInitDataModel
import cn.bit101.bitlogin.sso.LoginError as SsoLoginError
import cn.bit101.bitlogin.sso.SmsVerificationError
import cn.bit101.bitlogin.sso.SsoHttpException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.CookieManager

class DefaultLoginRepoTest {
    private lateinit var api: Bit101Api
    private lateinit var userApi: cn.bit101.api.service.bit101.UserApiService
    private lateinit var apiManager: APIManager
    private lateinit var loginStatus: MemoryLoginStatus
    private lateinit var seatLoginStatus: MemorySeatLoginStatus
    private lateinit var seatTaskStore: MemorySeatTaskStore
    private lateinit var repo: DefaultLoginRepo

    @Before
    fun setUp() {
        api = mockk()
        apiManager = mockk()
        loginStatus = MemoryLoginStatus()
        seatLoginStatus = MemorySeatLoginStatus()
        seatTaskStore = MemorySeatTaskStore()
        // 显式给出 api.user 的 mock：
        // ① strict mock 下直接读属性会抛 MockKException
        // ② 提前取好引用后，`coVerify(exactly = 0) { userApi.xxx() }` 不会把 `getUser()`
        //    这次属性访问也算进 exactly = 0（mockk 把 getter 当一次交互，之前踩过）
        userApi = mockk()
        every { api.user } returns userApi
        every { apiManager.api } returns api
        coEvery { apiManager.switch(any()) } answers {
            runBlocking { loginStatus.webVpn.set(firstArg()) }
        }
        repo = DefaultLoginRepo(
            apiManager,
            loginStatus,
            SmsCodeRequestHub(),
            SessionCleanup(loginStatus, seatLoginStatus, seatTaskStore),
        )
    }

    /**
     * ⚠️⚠️ **登出必须两边都清**（2026-10-11 解耦时补的锁）。
     *
     * 以前这段逻辑藏在 `DefaultLoginStatus.clear()` 里（配置层反向知道座位的存储键），
     * 抽成 `SessionCleanup` 之后**最容易漏的就是某一侧忘了清** ——
     * 漏了的表现很隐蔽：BIT101 显示已登出，座位还在拿过期的 JWT 打接口，
     * 而且两边看起来都「没问题」。
     */
    @Test
    fun `登出同时清掉 BIT101 与座位两侧`() = runBlocking {
        loginStatus.seedLoggedIn()
        seatLoginStatus.token.set("seat-jwt")
        seatTaskStore.tasks.set("[{...}]")

        repo.logout()

        assertFalse("BIT101 侧该清掉", loginStatus.status.get())
        assertEquals("座位 JWT 该清掉", "", seatLoginStatus.token.get())
        assertEquals("座位任务该清掉", "", seatTaskStore.tasks.get())
    }

    @Test
    fun `login saves school state and BIT101 fake cookie when both succeed`() = runBlocking {
        stubSuccessfulSchoolLogin(webVpn = true)
        stubSuccessfulBackendLogin()

        assertTrue(repo.login("112233", "password"))

        assertEquals("112233", loginStatus.sid.get())
        assertEquals("password", loginStatus.password.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
        assertTrue(loginStatus.status.get())
        assertTrue(loginStatus.webVpn.get())
        assertTrue(loginStatus.cookieManager.cookieStore.cookies.any { it.name == "SESSION" })
        assertTrue("BIT101 正常 ⇒ 降级标志为 true", loginStatus.bit101Available.value)
    }

    /**
     * ⚠️ 这一组守的是「别再往学校 WAF 上撞」。
     *
     * 两条路代价差一个数量级：
     * - `/user/login`：服务端只校验一次密码
     * - `webVpnVerify`：服务端**代用户做一遍学校 CAS**（2026-10-30 被封 IP 的根因）
     *
     * 所以一旦有了社区密码摘要，就不许再走后者。
     */
    @Test
    fun `有社区摘要时只走轻量登录且不触发统一认证`() = runBlocking {
        stubSuccessfulSchoolLogin()
        val digest = cn.bit101.android.data.common.HashUtils.md5("password")
        loginStatus.communityPasswordDigest.set(digest)
        coEvery { api.user.login(any()) } returns Response.success(
            PostLoginDataModel.Response(fakeCookie = "community-cookie"),
        )

        assertTrue(repo.login("112233", "password"))

        coVerify(exactly = 1) {
            api.user.login(match { it.sid == "112233" && it.password == digest })
        }
        coVerify(exactly = 0) { userApi.webVpnVerifyInit(any()) }
        assertEquals("community-cookie", loginStatus.fakeCookie.get())
        assertTrue("BIT101 可用", loginStatus.bit101Available.value)
    }

    @Test
    fun `无摘要时用学校密码 md5 试轻量登录并记住摘要`() = runBlocking {
        stubSuccessfulSchoolLogin()
        coEvery { api.user.login(any()) } returns Response.success(
            PostLoginDataModel.Response(fakeCookie = "community-cookie"),
        )

        assertTrue(repo.login("112233", "password"))

        assertEquals(
            "应记住学校密码的 md5，下次不再走 CAS",
            cn.bit101.android.data.common.HashUtils.md5("password"),
            loginStatus.communityPasswordDigest.get(),
        )
        coVerify(exactly = 0) { userApi.webVpnVerifyInit(any()) }
    }

    @Test
    fun `有摘要但轻量登录失败时不降级到统一认证`() = runBlocking {
        stubSuccessfulSchoolLogin()
        loginStatus.communityPasswordDigest.set("stale-digest")
        coEvery { api.user.login(any()) } throws IllegalStateException("wrong password")
        // 统一认证的 stub 就绪也不许被调用：降级等于把一次密码错放大成一次 CAS 请求
        stubSuccessfulBackendLogin()

        assertTrue("学校登录仍应成功", repo.login("112233", "password"))

        coVerify(exactly = 0) { userApi.webVpnVerifyInit(any()) }
        assertFalse("BIT101 应标记不可用", loginStatus.bit101Available.value)
        assertEquals(
            "摘要不该被一次失败覆盖",
            "stale-digest",
            loginStatus.communityPasswordDigest.get(),
        )
    }

    @Test
    fun `loginCommunity 用统一认证时不保存密码摘要`() = runBlocking {
        stubSuccessfulBackendLogin()

        assertTrue(repo.loginCommunity("112233", "password", useSchoolIdentity = true))

        // 统一认证没有验证过社区密码，不能把学校密码当"已验证"存下来
        assertEquals("", loginStatus.communityPasswordDigest.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
    }

    @Test
    fun `loginCommunity 用密码登录时保存摘要`() = runBlocking {
        coEvery { api.user.login(any()) } returns Response.success(
            PostLoginDataModel.Response(fakeCookie = "community-cookie"),
        )

        assertTrue(repo.loginCommunity("112233", "pass", useSchoolIdentity = false))

        assertEquals(
            cn.bit101.android.data.common.HashUtils.md5("pass"),
            loginStatus.communityPasswordDigest.get(),
        )
        assertEquals("community-cookie", loginStatus.fakeCookie.get())
    }

    @Test
    fun `school login failure clears partial state`() {
        loginStatus.seedLoggedIn()
        coEvery { api.schoolLogin.login(any(), any(), any()) } throws IllegalStateException("school failed")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.login("112233", "password") }
        }

        assertLoggedOut()
    }

    @Test
    fun `BIT101 backend login failure keeps usable school session`() = runBlocking {
        stubSuccessfulSchoolLogin()
        coEvery { api.user.webVpnVerifyInit(any()) } throws IllegalStateException("backend failed")

        // 学校登录成功即可用；BIT101 账号（flwfdd）不可用不应让整个登录失败
        assertTrue(repo.login("112233", "password"))

        assertTrue("学校登录成功 ⇒ 已登录", loginStatus.status.get())
        assertEquals("112233", loginStatus.sid.get())
        assertEquals("password", loginStatus.password.get())
        assertEquals("BIT101 未登录 ⇒ fakeCookie 为空", "", loginStatus.fakeCookie.get())
        assertTrue(
            "学校 cookie 必须保留（座位/课表直连要用）",
            loginStatus.cookieManager.cookieStore.cookies.any { it.name == "SESSION" },
        )
        assertFalse("BIT101 服务端不可用 ⇒ 降级标志为 false", loginStatus.bit101Available.value)
    }

    @Test
    fun `valid persisted school session does not call BIT Login`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubValidChecks()

        assertTrue(repo.checkLogin())

        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    @Test
    fun `invalid school session reauthenticates with saved credentials`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")
        stubSuccessfulSchoolLogin()
        stubSuccessfulBackendLogin()

        assertTrue(repo.checkLogin())

        coVerify(exactly = 1) { api.schoolLogin.login("112233", "password", any()) }
    }

    @Test
    fun `missing credentials does not attempt school login`() = runBlocking {
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")

        assertFalse(repo.checkLogin())

        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    @Test
    fun `operation retries at most once after a valid login check`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubValidChecks()
        var calls = 0

        val result = repo.doOperationRequiresLogin {
            calls++
            // 只有 401 才算「会话真的失效」⇒ 触发一次重登
            if (calls == 1) throw http401()
            "success"
        }

        assertEquals("success", result)
        assertEquals(2, calls)
    }

    /**
     * P0-3 的核心断言：**非 401 的异常不许触发登录**。
     *
     * 背景：服务端被学校 WAF 封 IP 期间，任何失败都会变成一次 CAS 尝试，
     * 用户越多流量越大。这里用超时/5xx 最常见的几类异常钉住这个边界。
     */
    @Test
    fun `non authentication failures never trigger a login`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubValidChecks()

        for (failure in listOf(
            IllegalStateException("expired request"),
            java.io.IOException("timeout"),
            httpError(500),
            httpError(403),
        )) {
            var calls = 0
            assertThrows(Exception::class.java) {
                runBlocking {
                    repo.doOperationRequiresLogin {
                        calls++
                        throw failure
                    }
                }
            }
            assertEquals("${failure.javaClass.simpleName} 不该被重试", 1, calls)
        }
        // 一次登录都不该发生
        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    /** 冷却期内即使遇到 401 也不登录。 */
    @Test
    fun `cooldown blocks relogin even on 401`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubValidChecks()
        // 冷却到 1 小时后
        loginStatus.identityRetryAt.set(System.currentTimeMillis() + 60 * 60_000L)

        var calls = 0
        assertThrows(Exception::class.java) {
            runBlocking {
                repo.doOperationRequiresLogin {
                    calls++
                    throw http401()
                }
            }
        }

        assertEquals(1, calls)
        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    @Test
    fun `logout clears credentials state and cookies`() = runBlocking {
        loginStatus.seedLoggedIn()
        loginStatus.communityPasswordDigest.set("digest")

        repo.logout()

        assertLoggedOut()
        // 主动登出必须清掉社区密码摘要：否则换号后会带着上个账号的摘要去 /user/login
        assertEquals("", loginStatus.communityPasswordDigest.get())
    }

    @Test
    fun `refreshLogin refreshes school and bit101 sessions`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubSuccessfulSchoolLogin(webVpn = true)
        stubSuccessfulBackendLogin()

        assertEquals(LoginRefreshResult.SUCCESS, repo.refreshLogin())

        assertTrue(loginStatus.status.get())
        assertTrue(loginStatus.webVpn.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
        assertTrue(loginStatus.cookieManager.cookieStore.cookies.any { it.name == "SESSION" })
        coVerify(exactly = 1) { api.schoolLogin.login("112233", "password", any()) }
    }

    @Test
    fun `refreshLogin without saved credentials returns FAILED`() = runBlocking {
        assertEquals(LoginRefreshResult.FAILED, repo.refreshLogin())

        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    @Test
    fun `checkLogin transient failure keeps credentials`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")
        coEvery { api.schoolLogin.login(any(), any(), any()) } throws IOException("network down")

        assertFalse(repo.checkLogin())

        assertEquals("112233", loginStatus.sid.get())
        assertEquals("password", loginStatus.password.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
    }

    @Test
    fun `checkLogin sms requirement keeps credentials but marks logged out`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")
        coEvery { api.schoolLogin.login(any(), any(), any()) } throws SmsVerificationError("需要短信验证")

        assertFalse(repo.checkLogin())

        assertFalse(loginStatus.status.get())
        assertEquals("112233", loginStatus.sid.get())
        assertEquals("password", loginStatus.password.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
    }

    @Test
    fun `checkLogin wrong password clears credentials`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")
        coEvery { api.schoolLogin.login(any(), any(), any()) } throws SsoLoginError("用户名或密码错误", code = "1030027")

        assertFalse(repo.checkLogin())

        assertLoggedOut()
    }

    @Test
    fun `checkLogin rate limited failure keeps credentials`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } throws IllegalStateException("unauthorized")
        coEvery { api.schoolLogin.login(any(), any(), any()) } throws SsoHttpException(429, "https://sso.bit.edu.cn", "too many requests")

        assertFalse(repo.checkLogin())

        assertEquals("112233", loginStatus.sid.get())
        assertEquals("password", loginStatus.password.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
    }

    @Test
    fun `tryRefreshLogin returns BUSY while checkLogin holds the lock`() = runBlocking {
        loginStatus.seedLoggedIn()
        val checkStarted = CompletableDeferred<Unit>()
        val checkGate = CompletableDeferred<String>()
        // checkLogin 现在只探测**学校会话**（getCurrentTerm），在它上面挂起以持锁
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } coAnswers {
            checkStarted.complete(Unit)
            checkGate.await()
        }

        val job = async(Dispatchers.IO) { repo.checkLogin() }
        checkStarted.await()

        // 锁已被 checkLogin 持有，非阻塞刷新应立即返回 BUSY，不发起学校登录
        assertEquals(LoginRefreshResult.BUSY, repo.tryRefreshLogin())
        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }

        checkGate.complete("2025-2026-1")
        assertTrue(job.await())
    }

    @Test
    fun `tryRefreshLogin refreshes school and bit101 sessions when idle`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubSuccessfulSchoolLogin(webVpn = true)
        stubSuccessfulBackendLogin()

        assertEquals(LoginRefreshResult.SUCCESS, repo.tryRefreshLogin())

        assertTrue(loginStatus.status.get())
        assertTrue(loginStatus.webVpn.get())
        assertEquals("fake-cookie", loginStatus.fakeCookie.get())
        coVerify(exactly = 1) { api.schoolLogin.login("112233", "password", any()) }
    }

    @Test
    fun `tryRefreshLogin without saved credentials returns FAILED`() = runBlocking {
        assertEquals(LoginRefreshResult.FAILED, repo.tryRefreshLogin())

        coVerify(exactly = 0) { api.schoolLogin.login(any(), any(), any()) }
    }

    @Test
    fun `checkLogin ignores BIT101 backend outage when school session is valid`() = runBlocking {
        loginStatus.seedLoggedIn()
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } returns "2025-2026-1"
        coEvery { api.user.check() } throws IOException("bit101 down")

        assertTrue(repo.checkLogin())

        // 已与 BIT101 探测解耦：学校会话有效即视为登录，不再调 /user/check
        coVerify(exactly = 0) { api.user.check() }
    }

    @Test
    fun `refreshLogin succeeds and keeps school session when BIT101 backend is down`() = runBlocking {
        loginStatus.seedLoggedIn()
        stubSuccessfulSchoolLogin(webVpn = true)
        coEvery { api.user.webVpnVerifyInit(any()) } throws IOException("bit101 down")

        assertEquals(LoginRefreshResult.SUCCESS, repo.refreshLogin())

        assertTrue(loginStatus.status.get())
        assertTrue(loginStatus.webVpn.get())
        assertTrue(loginStatus.cookieManager.cookieStore.cookies.any { it.name == "SESSION" })
        assertFalse("BIT101 不可用 ⇒ 降级标志为 false", loginStatus.bit101Available.value)
        coVerify(exactly = 1) { api.schoolLogin.login("112233", "password", any()) }
    }

    private fun stubSuccessfulSchoolLogin(webVpn: Boolean = false) {
        coEvery { api.schoolLogin.login(any(), any(), any()) } returns SchoolLoginResult(
            cookies = listOf(
                SchoolCookie(
                    name = "SESSION",
                    value = "secret",
                    domain = "sso.bit.edu.cn",
                    path = "/",
                    secure = true,
                    expiresEpochSeconds = null,
                ),
            ),
            webVpn = webVpn,
        )
    }

    private fun stubSuccessfulBackendLogin() {
        coEvery { api.user.webVpnVerifyInit(any()) } returns Response.success(
            PostWebvpnVerifyInitDataModel.Response(
                captcha = "",
                cookie = "cookie",
                execution = "execution",
                salt = "MDEyMzQ1Njc4OWFiY2RlZg==",
            ),
        )
        coEvery { api.user.webVpnVerify(any()) } returns Response.success(
            PostWebvpnVerifyDataModel.Response(token = "token", code = "code"),
        )
        coEvery { api.user.register(any()) } returns Response.success(
            PostRegisterDataModel.Response(fakeCookie = "fake-cookie"),
        )
    }

    private fun stubValidChecks() {
        coEvery { api.user.check() } returns Response.success(Unit)
        coEvery { api.schoolJxzxehall.getCurrentTerm(any(), any()) } returns "2025-2026-1"
    }

    private fun assertLoggedOut() = runBlocking {
        assertEquals("", loginStatus.sid.get())
        assertEquals("", loginStatus.password.get())
        assertEquals("", loginStatus.fakeCookie.get())
        assertFalse(loginStatus.status.get())
        assertFalse(loginStatus.webVpn.get())
        assertTrue(loginStatus.cookieManager.cookieStore.cookies.isEmpty())
    }

    /** 构造一个真实的 Retrofit 401 —— `doOperationRequiresLogin` 只认它。 */
    private fun httpError(code: Int): retrofit2.HttpException = retrofit2.HttpException(
        retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, "")),
    )

    private fun http401(): retrofit2.HttpException = httpError(401)

    private class MemoryLoginStatus : LoginStatus {
        override val sid = MemorySettingItem("")
        override val password = MemorySettingItem("")
        override val status = MemorySettingItem(false)
        override val webVpn = MemorySettingItem(false)
        override val fakeCookie = MemorySettingItem("")
        override val communityPasswordDigest = MemorySettingItem("")
        override val identityRetryAt = MemorySettingItem(0L)
        override val identityAttempts = MemorySettingItem(0)
        override val bit101Available = MutableStateFlow(true)
        override val cookieManager = CookieManager()

        override suspend fun clear() {
            status.set(false)
            webVpn.set(false)
            sid.set("")
            password.set("")
            fakeCookie.set("")
            // 与生产实现一致：clear() **不**清社区密码摘要（只有 logout 才清）
            identityRetryAt.set(0L)
            identityAttempts.set(0)
            cookieManager.cookieStore.removeAll()
        }

        fun seedLoggedIn() = runBlocking {
            sid.set("112233")
            password.set("password")
            status.set(true)
            fakeCookie.set("fake-cookie")
        }
    }

    /** 座位侧登录态（只为验证「登出会连座位一起清」）。 */
    private class MemorySeatLoginStatus : SeatLoginStatus {
        override val token = MemorySettingItem("")
    }

    private class MemorySeatTaskStore : SeatTaskStore {
        override val tasks = MemorySettingItem("")
    }

    private class MemorySettingItem<T>(initial: T) : SettingItem<T> {
        private val state = MutableStateFlow(initial)
        override val flow = state
        override suspend fun get(): T = state.value
        override suspend fun set(value: T) {
            state.value = value
        }
    }
}
