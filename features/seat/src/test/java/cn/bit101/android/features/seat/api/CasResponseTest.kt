package cn.bit101.android.features.seat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/api/cas/user` 响应解析。
 *
 * 这里回归的是一个真实踩过的坑：`member` 是 **JSON 对象而非数组**，
 * 早期用 `optJSONArray("member")` 取，恒为 null，表现为「登录走完了但拿不到 token」。
 * 所以「member 是数组时必须返回 null 而不是崩溃」也要固化下来。
 */
class CasResponseTest {

    @Test
    fun `parses member object with token`() {
        val body = """
            {"code":1,"member":{"token":"eyJhbGciOiJI","name":"张三","id":"1120200001"}}
        """.trimIndent()

        val result = parseCasUser(body)

        assertEquals("eyJhbGciOiJI", result?.token)
        assertEquals("张三", result?.name)
        assertEquals("1120200001", result?.studentId)
    }

    @Test
    fun `missing name and id are tolerated`() {
        val result = parseCasUser("""{"member":{"token":"abc"}}""")

        assertEquals("abc", result?.token)
        assertEquals("", result?.name)
        assertEquals("", result?.studentId)
    }

    @Test
    fun `member as array yields null instead of crashing`() {
        // 历史 bug：API 返回对象，代码按数组取
        assertNull(parseCasUser("""{"member":[{"token":"abc"}]}"""))
    }

    @Test
    fun `token present but null yields null`() {
        assertNull(parseCasUser("""{"member":{"token":null,"name":"张三"}}"""))
    }

    @Test
    fun `empty token yields null`() {
        assertNull(parseCasUser("""{"member":{"token":""}}"""))
    }

    @Test
    fun `missing member yields null`() {
        assertNull(parseCasUser("""{"code":0,"msg":"未登录"}"""))
    }

    @Test
    fun `malformed json yields null instead of throwing`() {
        assertNull(parseCasUser(""))
        assertNull(parseCasUser("not json at all"))
        assertNull(parseCasUser("<html>CAS login page</html>"))
    }
}
