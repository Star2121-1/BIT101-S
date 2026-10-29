package cn.bit101.api.service.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CasServiceGuard] 的契约测试。
 *
 * 这些断言守的是**学生密码**：`service` 一旦被指到外部域名，
 * BIT-Login 库就会把密码密文提交过去（salt 由对方给，对方能解密）。
 * 任何一条变红都不许放过。
 */
class CasServiceGuardTest {

    private val validService = "https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fjwapp%2Fsys%2Fxsfacx%2F%2Adefault%2Findex.do"

    private fun location(service: String) =
        "https://jxzxehall.bit.edu.cn/auth-protocol-core/login?service=$service"

    // ── 放行 ──────────────────────────────────────────────

    @Test
    fun `school subdomain over https is allowed`() {
        val decoded = CasServiceGuard.requireSafeService(location(validService))
        assertEquals("https://jxzxehallapp.bit.edu.cn/jwapp/sys/xsfacx/*default/index.do", decoded)
    }

    @Test
    fun `school root domain is allowed`() {
        val decoded = CasServiceGuard.requireSafeService(
            location("https%3A%2F%2Fbit.edu.cn%2Fcallback"),
        )
        assertEquals("https://bit.edu.cn/callback", decoded)
    }

    @Test
    fun `webvpn subdomain is allowed`() {
        val decoded = CasServiceGuard.requireSafeService(
            location("https%3A%2F%2Fwebvpn.bit.edu.cn%2Fhttps%2Fabc%2Fcallback"),
        )
        assertTrue(decoded.startsWith("https://webvpn.bit.edu.cn/"))
    }

    // ── 拒绝：这是重点 ─────────────────────────────────────

    @Test
    fun `external domain is rejected`() {
        val error = assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(location("https%3A%2F%2Fexample.com%2F"))
        }
        assertTrue("错误消息应说明是域名问题", error.message.orEmpty().contains("非学校域名"))
    }

    @Test
    fun `domain that merely ends with school suffix is rejected`() {
        // bit.edu.cn.evil.com —— 后缀伪装，必须拒绝
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(location("https%3A%2F%2Fbit.edu.cn.evil.com%2F"))
        }
    }

    @Test
    fun `domain that merely contains school name is rejected`() {
        // evil-bit.edu.cn —— 前缀伪装，必须拒绝
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(location("https%3A%2F%2Fevil-bit.edu.cn%2F"))
        }
    }

    @Test
    fun `plain http is rejected even on school domain`() {
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(location("http%3A%2F%2Fjxzxehall.bit.edu.cn%2F"))
        }
    }

    @Test
    fun `missing location is rejected`() {
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(null)
        }
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService("   ")
        }
    }

    @Test
    fun `location without service parameter is rejected`() {
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService("https://jxzxehall.bit.edu.cn/auth-protocol-core/login")
        }
    }

    @Test
    fun `unparsable service is rejected`() {
        assertThrows(CasServiceGuard.UnsafeServiceException::class.java) {
            CasServiceGuard.requireSafeService(location("not%20a%20url"))
        }
    }

    // ── 解析细节 ──────────────────────────────────────────

    @Test
    fun `service parameter is cut before following query params`() {
        val decoded = CasServiceGuard.requireSafeService(
            location("https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fcb&from=cas"),
        )
        assertEquals("https://jxzxehallapp.bit.edu.cn/cb", decoded)
    }

    @Test
    fun `percent decoding does not turn plus into space`() {
        // Python quote 风格：'/' 是 %2F，'+' 会被编码成 %2B
        val decoded = CasServiceGuard.requireSafeService(
            location("https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fa%2Bb"),
        )
        assertEquals("https://jxzxehallapp.bit.edu.cn/a+b", decoded)
    }

    @Test
    fun `uppercase percent hex is decoded`() {
        val decoded = CasServiceGuard.requireSafeService(
            location("https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fx%2Fy"),
        )
        assertTrue(decoded.contains("/x/y"))
    }
}
