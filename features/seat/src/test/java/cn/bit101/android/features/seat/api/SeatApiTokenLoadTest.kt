package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.SeatLoginStatus
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [SeatApi.ensureTokenLoaded] 的后台懒恢复语义：
 *
 * - 冷进程（内存空 + 持久化有值）→ 恢复出 token；
 * - 幂等：内存已有值 → 直接返回，**连持久化都不读**；
 * - 不覆盖：恢复过程中若有并发写入的新 token（UI 侧刚登录），绝不用旧值冲掉（compareAndSet）；
 * - 尽力而为：持久化读取异常 → 返回空、不抛（调用方按「没有会话」早退即可）；
 * - 持久化本身为空 → 保持空。
 *
 * 这些分支直接决定后台路径（通知到点校验 / 组件一键预约 / 前台服务重建 / 组件推送）
 * 会用哪个 token —— 恢复失败或覆盖错值都会让「登录一次就不用再授权」失效。
 */
class SeatApiTokenLoadTest {

    private val seatHttp = mockk<SeatHttp>(relaxed = true)
    private val loginStatus = mockk<SeatLoginStatus>()
    private val autoLogin = mockk<SeatAutoLogin>(relaxed = true)

    private fun newApi(): SeatApi = SeatApi(seatHttp, loginStatus, autoLogin)

    @Test
    fun `restores token from persistence when memory is empty`() = runBlocking {
        coEvery { loginStatus.token.get() } returns "tk-persist"

        val api = newApi()

        assertEquals("tk-persist", api.ensureTokenLoaded())
        assertEquals("tk-persist", api.token)
    }

    @Test
    fun `keeps memory token and skips persistence read when already loaded`() = runBlocking {
        coEvery { loginStatus.token.set(any()) } just runs
        var persistedReads = 0
        coEvery { loginStatus.token.get() } coAnswers {
            persistedReads++
            "tk-should-not-be-read"
        }

        val api = newApi()
        api.token = "tk-mem"

        assertEquals("tk-mem", api.ensureTokenLoaded())
        assertEquals(0, persistedReads)
    }

    @Test
    fun `keeps new concurrent token instead of overwriting with stale persisted value`() = runBlocking {
        coEvery { loginStatus.token.set(any()) } just runs

        val api = newApi()
        // 模拟竞态：持久化读取返回旧值的同时，UI 侧已写入了新 token
        coEvery { loginStatus.token.get() } coAnswers {
            api.token = "tk-new"
            "tk-old"
        }

        assertEquals("tk-new", api.ensureTokenLoaded())
        assertEquals("tk-new", api.token)
    }

    @Test
    fun `returns empty and does not throw when persistence read fails`() = runBlocking {
        coEvery { loginStatus.token.get() } throws IOException("keystore broken")

        val api = newApi()

        assertEquals("", api.ensureTokenLoaded())
        assertEquals("", api.token)
    }

    @Test
    fun `empty persisted value keeps memory empty`() = runBlocking {
        coEvery { loginStatus.token.get() } returns ""

        val api = newApi()

        assertEquals("", api.ensureTokenLoaded())
        assertEquals("", api.token)
    }
}
