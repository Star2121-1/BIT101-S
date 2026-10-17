package cn.bit101.android.features.seat.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [isCasAuthFailure] 的判定表：只有**能证明会话不存在**的失败才算认证失败。
 *
 * 误判的代价（本测试防的回归）：网络抖动被当成会话失效 → 用户被假登出 +
 * 正在跑的监控任务被 stopAll（重登后也不会自动恢复）。
 * 反向漏判由 `handleApiError` 的 10001 / 401 分支兜底，成本可控。
 *
 * 判定形状与 [SeatSession.postCasUser] 的抛错形状一一对应：
 * - 会话不存在 → `IOException(SeatSession.ERR_SESSION_GONE)`
 * - HTTP 非 200 → `IOException("HTTP ${code}")`
 */
class CasAuthFailureKindTest {

    @Test
    fun `session gone message is auth failure`() {
        // /api/cas/user 返回 200 但没有 token：phpCAS 会话被清后的地面真相
        assertTrue(isCasAuthFailure(IOException(SeatSession.ERR_SESSION_GONE)))
    }

    @Test
    fun `http 401 is auth failure`() {
        assertTrue(isCasAuthFailure(IOException("HTTP 401")))
    }

    @Test
    fun `http 403 is auth failure`() {
        assertTrue(isCasAuthFailure(IOException("HTTP 403")))
    }

    @Test
    fun `timeout is inconclusive`() {
        assertFalse(isCasAuthFailure(SocketTimeoutException("timeout")))
        assertFalse(isCasAuthFailure(SocketTimeoutException()))
    }

    @Test
    fun `unknown host is inconclusive`() {
        assertFalse(isCasAuthFailure(UnknownHostException("seatlib.bit.edu.cn")))
    }

    @Test
    fun `connect failure is inconclusive`() {
        assertFalse(isCasAuthFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `http 5xx is inconclusive`() {
        assertFalse(isCasAuthFailure(IOException("HTTP 500")))
        assertFalse(isCasAuthFailure(IOException("HTTP 503")))
    }

    @Test
    fun `null cause is inconclusive`() {
        assertFalse(isCasAuthFailure(null))
    }
}
