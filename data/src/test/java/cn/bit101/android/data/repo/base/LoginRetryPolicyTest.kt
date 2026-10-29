package cn.bit101.android.data.repo.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoginRetryPolicy] 的契约测试。
 *
 * 背景：服务端被 WAF 封 IP 期间，客户端的重试频率是**放大器**。
 * 这些断言钉住「失败后到底冷却多久」，改动这里的任何数字都必须先想清楚后果。
 */
class LoginRetryPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    // ── 身份验证：24h 起指数退避 ─────────────────────────────

    @Test
    fun `identity failure backs off 24 48 96 192 hours`() {
        assertEquals(now + 24 * hour, LoginRetryPolicy.retryAt(now, 1, null, identity = true))
        assertEquals(now + 48 * hour, LoginRetryPolicy.retryAt(now, 2, null, identity = true))
        assertEquals(now + 96 * hour, LoginRetryPolicy.retryAt(now, 3, null, identity = true))
        assertEquals(now + 192 * hour, LoginRetryPolicy.retryAt(now, 4, null, identity = true))
    }

    @Test
    fun `identity backoff is capped at attempt 4`() {
        val capped = now + 192 * hour
        assertEquals(capped, LoginRetryPolicy.retryAt(now, 5, null, identity = true))
        assertEquals(capped, LoginRetryPolicy.retryAt(now, 99, null, identity = true))
    }

    @Test
    fun `attempt zero or negative is treated as first attempt`() {
        assertEquals(now + 24 * hour, LoginRetryPolicy.retryAt(now, 0, null, identity = true))
        assertEquals(now + 24 * hour, LoginRetryPolicy.retryAt(now, -5, null, identity = true))
    }

    // ── 普通请求：30min / 2h / 24h ───────────────────────────

    @Test
    fun `regular failure backs off 30 minutes 2 hours then 24 hours`() {
        assertEquals(now + 30 * minute, LoginRetryPolicy.retryAt(now, 1, null, identity = false))
        assertEquals(now + 2 * hour, LoginRetryPolicy.retryAt(now, 2, null, identity = false))
        assertEquals(now + 24 * hour, LoginRetryPolicy.retryAt(now, 3, null, identity = false))
        assertEquals(now + 24 * hour, LoginRetryPolicy.retryAt(now, 8, null, identity = false))
    }

    @Test
    fun `regular cooldown is much shorter than identity cooldown`() {
        val regular = LoginRetryPolicy.retryAt(now, 1, null, identity = false)
        val identity = LoginRetryPolicy.retryAt(now, 1, null, identity = true)
        assertTrue("普通请求冷却应明显短于身份验证", identity - regular >= 23 * hour)
    }

    // ── Retry-After 的三种形态 ──────────────────────────────

    @Test
    fun `server retry after seconds wins when it is later than local estimate`() {
        // 普通请求本地估算 30 分钟；服务端要求 3 小时 ⇒ 听服务端
        val at = LoginRetryPolicy.retryAt(now, 1, "10800", identity = false)
        assertEquals(now + 3 * hour, at)
    }

    @Test
    fun `local estimate wins when server retry after is earlier`() {
        // 身份验证本地 24 小时；服务端只说 60 秒 ⇒ 仍按 24 小时冷却
        val at = LoginRetryPolicy.retryAt(now, 1, "60", identity = true)
        assertEquals(now + 24 * hour, at)
    }

    @Test
    fun `retry after accepts RFC 1123 date`() {
        // 用同一套 formatter 生成，避免手写星期几出错（RFC1123 里星期与日期必须一致）
        val future = java.time.ZonedDateTime.of(
            2026, 12, 25, 0, 0, 0, 0, java.time.ZoneOffset.UTC,
        )
        val header = future.format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)

        val at = LoginRetryPolicy.retryAt(now, 1, header, identity = false)

        assertEquals("RFC1123 应被解析为绝对时间", future.toInstant().toEpochMilli(), at)
    }

    @Test
    fun `malformed retry after falls back to local estimate without throwing`() {
        val at = LoginRetryPolicy.retryAt(now, 1, "not-a-date", identity = true)
        assertEquals(now + 24 * hour, at)
    }

    @Test
    fun `negative retry after is ignored`() {
        val at = LoginRetryPolicy.retryAt(now, 1, "-100", identity = false)
        assertEquals(now + 30 * minute, at)
    }

    @Test
    fun `blank retry after is treated as absent`() {
        assertEquals(
            LoginRetryPolicy.retryAt(now, 1, null, identity = false),
            LoginRetryPolicy.retryAt(now, 1, "   ", identity = false),
        )
    }

    // ── 辅助判定 ────────────────────────────────────────────

    @Test
    fun `should attempt only after cooldown elapses`() {
        val at = LoginRetryPolicy.retryAt(now, 1, null, identity = true)
        assertFalse(LoginRetryPolicy.shouldAttempt(now, at))
        assertFalse(LoginRetryPolicy.shouldAttempt(at - 1, at))
        assertTrue(LoginRetryPolicy.shouldAttempt(at, at))
        assertTrue(LoginRetryPolicy.shouldAttempt(at + 1, at))
    }

    @Test
    fun `next attempt at returns zero when not cooling down`() {
        assertEquals(0L, LoginRetryPolicy.nextAttemptAtOrZero(now, now))
        assertEquals(0L, LoginRetryPolicy.nextAttemptAtOrZero(now, now - 1))
        val at = now + 24 * hour
        assertEquals(at, LoginRetryPolicy.nextAttemptAtOrZero(now, at))
    }
}
