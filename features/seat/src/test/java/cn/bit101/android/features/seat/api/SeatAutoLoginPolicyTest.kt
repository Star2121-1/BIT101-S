package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.LoginStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * 「谁能打学校 SSO」这条线必须被测试盯住 —— 它直接决定用户会不会莫名其妙收到短信。
 *
 * 学校风控触发二次验证时，**短信是学校直接发出去的**，而触发它的入口分成两类：
 *
 * - **允许凭据**（`allowCredentials = true`）：用户点了「授权座位系统」/ 点预约。
 *   界面会立刻弹验证码输入框，短信有用。
 * - **不允许**（`false`）：进页面的自动续期、token 失效的自动续期、业务请求的 401 重试。
 *   这些路径**要么没界面、要么用户没要求**（前台服务甚至能在锁屏时触发），
 *   短信发出去也没人输 —— 用户只会收到一条**来路不明的验证码**。
 *
 * 所以 `false` 必须是硬的：哪怕 cookie 静默认证失败，也**绝不能**退到凭据直登。
 */
class SeatAutoLoginPolicyTest {

    private val session = mockk<SeatSession>()
    private val loginStatus = mockk<LoginStatus>(relaxed = true)

    /** seatlib 的 phpCAS 会话已失效（cookie 静默认证会失败） */
    private val cookieFailed = IOException("无有效 phpCAS 会话")

    private fun ok(token: String) = Result.success(LoginResult(token = token, name = "n", studentId = "s"))

    /**
     * 自动路径：cookie 失败就算了，**一次都不许打 SSO**。
     *
     * ⚠️ 这里必须把凭据桩成**非空**。否则「没打 SSO」可能只是因为没凭据可打
     * （`sid.isBlank()` 那条早退了）—— 测试会**因为别的原因通过**，
     * 把守卫拆掉也照样绿，等于没测。桩上凭据后，只有守卫本身能拦住它。
     */
    @Test
    fun `cookie-only path never falls back to credentials`() = runBlocking {
        coEvery { session.authenticateSeatlib() } returns Result.failure(cookieFailed)
        coEvery { loginStatus.sid.get() } returns "1120241355"
        coEvery { loginStatus.password.get() } returns "pw"
        coEvery { session.loginWithCredentials(any(), any()) } returns ok("不该被用到")

        val autoLogin = SeatAutoLogin(session, loginStatus)

        assertNull(autoLogin.renew(allowCredentials = false))

        coVerify(exactly = 0) { session.loginWithCredentials(any(), any()) }
    }

    /** cookie 还有效时，谁都不需要打 SSO（两条路径都该走这条路）。 */
    @Test
    fun `cookie success returns the token without touching sso`() = runBlocking {
        coEvery { session.authenticateSeatlib() } returns ok("tk-cookie")

        val autoLogin = SeatAutoLogin(session, loginStatus)

        assertEquals("tk-cookie", autoLogin.renew(allowCredentials = true))
        assertEquals("tk-cookie", autoLogin.renew(allowCredentials = false))

        coVerify(exactly = 0) { session.loginWithCredentials(any(), any()) }
    }

    /**
     * 反方向也要成立 —— 否则这个开关可能只是被忽略掉了。
     * **用户发起**时允许用已持久化的凭据直登（此时界面会弹验证码框）。
     */
    @Test
    fun `explicit authorization may use stored credentials`() = runBlocking {
        coEvery { session.authenticateSeatlib() } returns Result.failure(cookieFailed)
        coEvery { loginStatus.sid.get() } returns "1120241355"
        coEvery { loginStatus.password.get() } returns "pw"
        coEvery { session.loginWithCredentials(any(), any()) } returns ok("tk-cred")

        val autoLogin = SeatAutoLogin(session, loginStatus)

        assertEquals("tk-cred", autoLogin.renew(allowCredentials = true))

        coVerify(exactly = 1) { session.loginWithCredentials("1120241355", "pw") }
    }

    /** 没有持久化凭据时（例如只用网页登过），两条路径都只能回落到人工，不该报错。 */
    @Test
    fun `missing credentials never hits sso even when allowed`() = runBlocking {
        coEvery { session.authenticateSeatlib() } returns Result.failure(cookieFailed)
        coEvery { loginStatus.sid.get() } returns ""
        coEvery { loginStatus.password.get() } returns ""

        val autoLogin = SeatAutoLogin(session, loginStatus)

        assertNull(autoLogin.renew(allowCredentials = true))

        coVerify(exactly = 0) { session.loginWithCredentials(any(), any()) }
    }
}
