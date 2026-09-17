package cn.bit101.android.features.seat.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 认证失效判定。
 *
 * 这一层曾经是错的：早期实现按 `HTTP 401` 判定 token 失效，
 * 而实测 seatlib 在未登录时返回的是 **HTTP 200 + `{"code":10001,"message":"您尚未登录"}`**，
 * 于是 token 失效**永远不会被识别**，用户只会看到「预约失败」而不会被引导重新登录。
 * 这里把真实响应固化成测试，防止回归。
 */
class SeatAuthFailureTest {

    /** `POST /api/Seat/confirm` 未认证的真实响应。 */
    private val realConfirmUnauthorized = """{"code":10001,"message":"您尚未登录"}"""

    /** `POST /api/Space/cancel` 未认证的真实响应。 */
    private val realCancelUnauthorized = """{"code":10001,"message":"您尚未登录"}"""

    /** `POST /api/Seat/seat` 无认证时的真实响应（注意：这个接口**不要求认证**）。 */
    private val realSeatsWithoutAuth = """{"code":1,"msg":"操作成功","data":[]}"""

    private fun failureOf(payload: String): Throwable? {
        val json = JSONObject(payload)
        return seatAuthFailure(json.intOrZero("code"), json.errorText())
    }

    @Test
    fun `unauthorized confirm is reported as token expired`() {
        val error = failureOf(realConfirmUnauthorized)

        assertNotNull(error)
        assertEquals(SeatApi.TOKEN_EXPIRED, error!!.message)
    }

    @Test
    fun `unauthorized cancel is reported as token expired`() {
        assertEquals(SeatApi.TOKEN_EXPIRED, failureOf(realCancelUnauthorized)?.message)
    }

    @Test
    fun `successful response is not treated as auth failure`() {
        assertNull(failureOf(realSeatsWithoutAuth))
    }

    @Test
    fun `unknown business error is not mistaken for auth failure`() {
        // code 1 之外的业务错误不该触发重新登录 —— 否则一次「座位已被占用」
        // 就会把整个会话清掉
        assertNull(seatAuthFailure(code = 10002, message = "该座位已被占用"))
        assertNull(seatAuthFailure(code = 0, message = ""))
    }

    @Test
    fun `message fallback catches unknown auth codes`() {
        // 服务端对「未登录」与「登录已过期」可能用不同的码，
        // 而后者无法在没有真实过期 token 的情况下实测到
        assertEquals(SeatApi.TOKEN_EXPIRED, seatAuthFailure(code = 10099, message = "您尚未登录")?.message)
    }
}
