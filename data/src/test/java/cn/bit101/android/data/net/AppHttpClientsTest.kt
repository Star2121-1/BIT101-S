package cn.bit101.android.data.net

import cn.bit101.api.model.common.SchoolDomains
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/**
 * [AppHttpClients.SsoAwareCookieJar] 的行为测试。
 *
 * 盯的是**边界**：CAS 会话 cookie 只在请求 CAS 域时补，绝不滥发给任意子系统。
 * 这条是安全边界，光靠注释记不住。
 */
class AppHttpClientsTest {

    private fun cookieManagerWith(cookies: List<Pair<String, String>>): CookieManager {
        val cm = CookieManager()
        cookies.forEach { (domain, name) ->
            cm.cookieStore.add(
                URI("https://$domain/"),
                HttpCookie(name, "value-$name").apply {
                    this.domain = domain
                    path = "/"
                },
            )
        }
        return cm
    }

    private fun namesFor(jar: AppHttpClients.SsoAwareCookieJar, url: String): Set<String> =
        jar.loadForRequest(url.toHttpUrl()).map { it.name }.toSet()

    /** 请求 CAS 域 ⇒ 一定带上 CAS 会话 cookie（这正是跨子系统自动登录成立的前提）。 */
    @Test
    fun `请求 CAS 域时带上 CAS 会话 cookie`() {
        val jar = AppHttpClients.SsoAwareCookieJar(
            cookieManagerWith(listOf("sso.bit.edu.cn" to "CAS_SESSION")),
        )

        assertTrue(namesFor(jar, "https://sso.bit.edu.cn/login?service=x").contains("CAS_SESSION"))
        assertTrue(namesFor(jar, "https://login.bit.edu.cn/cas").contains("CAS_SESSION"))
    }

    /**
     * ⚠️ **安全边界**：请求普通子系统域时**不许**带 CAS 的 cookie。
     * 这条要是红了，等于把学校 SSO 会话 cookie 滥发给了任意子系统域。
     */
    @Test
    fun `请求子系统域时不带 CAS 会话 cookie`() {
        val jar = AppHttpClients.SsoAwareCookieJar(
            cookieManagerWith(listOf("sso.bit.edu.cn" to "CAS_SESSION")),
        )

        assertFalse(
            namesFor(jar, "https://dkykt.info.bit.edu.cn/home/openHomePageByCas")
                .contains("CAS_SESSION"),
        )
        assertFalse(namesFor(jar, "https://lib.bit.edu.cn/login").contains("CAS_SESSION"))
    }

    /** 子系统自己的 cookie 该带还得带 —— 补 CAS 不能把原有行为弄丢。 */
    @Test
    fun `子系统自己的 cookie 照常带`() {
        val jar = AppHttpClients.SsoAwareCookieJar(
            cookieManagerWith(
                listOf(
                    "dkykt.info.bit.edu.cn" to "YK_SESSION",
                    "sso.bit.edu.cn" to "CAS_SESSION",
                ),
            ),
        )

        val names = namesFor(jar, "https://dkykt.info.bit.edu.cn/home/openHomePageByCas")
        assertTrue(names.contains("YK_SESSION"))
        assertFalse(names.contains("CAS_SESSION"))
    }

    /** 同名 cookie 不能出现两次 —— 重复时服务端取到的可能是旧值。 */
    @Test
    fun `同名 cookie 不重复`() {
        val jar = AppHttpClients.SsoAwareCookieJar(
            cookieManagerWith(listOf("sso.bit.edu.cn" to "CAS_SESSION")),
        )

        val cookies = jar.loadForRequest("https://sso.bit.edu.cn/login".toHttpUrl())
        assertEquals(1, cookies.count { it.name == "CAS_SESSION" })
    }

    /** 域判据本身（与 SchoolDomainsTest 的分工：这里只测 CAS 相关的那几个）。 */
    @Test
    fun `CAS 域判据`() {
        assertTrue(SchoolDomains.isSsoDomain("sso.bit.edu.cn"))
        assertTrue(SchoolDomains.isSsoDomain("login.bit.edu.cn"))
        assertFalse(SchoolDomains.isSsoDomain("dkykt.info.bit.edu.cn"))
        assertFalse(SchoolDomains.isSsoDomain("lib.bit.edu.cn"))
        assertFalse(SchoolDomains.isSsoDomain("bit.edu.cn"))
        assertFalse(SchoolDomains.isSsoDomain(null))
    }
}
