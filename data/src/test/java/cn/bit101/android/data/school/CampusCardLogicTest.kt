package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CampusCardLogic] 的解析测试。
 *
 * 「未登录」样本取自真实 CAS gate 页（2026-09-24 抓取）的关键特征；
 * 「已登录」样本按一卡通首页（jQuery 服务端渲染）的典型形态构造。
 */
class CampusCardLogicTest {

    /** 未登录：被 302 到 CAS gate 后的页面（真实页面的特征片段）。 */
    private val gateHtml = """
        <html><head><title>用户登录</title></head><body>
        <div class="UsernamePassword">用户名密码</div>
        <div>smsLogin 短信验证码</div>
        <a href="clientredirect?client_name=adapter&service=https%3A%2F%2Fdkykt.info.bit.edu.cn%2Fhome%2FopenHomePageByCas">i北理扫码</a>
        </body></html>
    """.trimIndent()

    /** 已登录：首页正文（构造，含余额文案与「统一身份认证」菜单项）。 */
    private val homeHtml = """
        <html><body>
        <ul class="menu"><li>统一身份认证</li><li>退出</li></ul>
        <div class="card-info">
            <span>账户余额</span><em>128.50</em>
            <span>过渡余额</span><em>0.00</em>
            <span>余额</span><em>128.50</em>
        </div>
        </body></html>
    """.trimIndent()

    /** ⚠️ 核心回归：**未登录 = 最终 URL 落在 CAS**，与 HTML 内容无关。 */
    @Test
    fun `重定向到 sso 即未登录`() {
        val snap = CampusCardLogic.parse(
            gateHtml,
            "https://sso.bit.edu.cn/gate?service=https%3A%2F%2Fdkykt.info.bit.edu.cn%2Fhome%2FopenHomePageByCas",
        )
        assertFalse(snap.loggedIn)
    }

    /**
     * v1.8.0 的实测 bug 回归：首页正文含「统一身份认证」文案（菜单/页脚），
     * 但**没被重定向** —— 必须判为已登录（旧版按 HTML 关键词判，误判成未登录）。
     */
    @Test
    fun `首页含统一身份认证文案也判已登录`() {
        val snap = CampusCardLogic.parse(
            homeHtml,
            "https://dkykt.info.bit.edu.cn/home/openHomePageByCas",
        )
        assertTrue(snap.loggedIn)
    }

    /** 已登录 + 正文有余额：按标签去重后提取。 */
    @Test
    fun `提取余额并按标签去重`() {
        val snap = CampusCardLogic.parse(
            homeHtml,
            "https://dkykt.info.bit.edu.cn/home/openHomePageByCas",
        )

        assertEquals(
            listOf("账户余额" to "128.50", "过渡余额" to "0.00", "余额" to "128.50"),
            snap.entries,
        )
    }

    /** 金额容忍千分位逗号。 */
    @Test
    fun `金额支持千分位`() {
        val snap = CampusCardLogic.parse(
            "<div>余额 1,234.56</div>",
            "https://dkykt.info.bit.edu.cn/home/openHomePageByCas",
        )
        assertEquals(listOf("余额" to "1,234.56"), snap.entries)
    }

    /** 网络失败（空 HTML）不算已登录。 */
    @Test
    fun `空响应不算已登录`() {
        val snap = CampusCardLogic.parse(
            "",
            "https://dkykt.info.bit.edu.cn/home/openHomePageByCas",
        )
        assertFalse(snap.loggedIn)
        assertTrue(snap.entries.isEmpty())
    }

    /** 首页是 JS 壳（无余额文案）时：已登录但条目为空，UI 走「未识别」分支。 */
    @Test
    fun `已登录但解析不到余额时条目为空`() {
        val snap = CampusCardLogic.parse(
            "<html><body><div id=app></div></body></html>",
            "https://dkykt.info.bit.edu.cn/home/openHomePageByCas",
        )
        assertTrue(snap.loggedIn)
        assertTrue(snap.entries.isEmpty())
    }

    /** 余额文案：登录后取第一个条目；未登录 / 失败各有降级文案。 */
    @Test
    fun `余额文案分支`() {
        val logged = CampusCardLogic.parse(homeHtml, "https://dkykt.info.bit.edu.cn/home/openHomePageByCas")
        assertEquals("¥128.50", CampusCardLogic.balanceText(logged, loading = false, fetched = true))
        assertEquals("获取中…", CampusCardLogic.balanceText(logged, loading = true, fetched = false))
        assertEquals("获取失败", CampusCardLogic.balanceText(null, loading = false, fetched = true))

        val gate = CampusCardLogic.parse(gateHtml, "https://sso.bit.edu.cn/gate")
        assertEquals("未登录，进详情页登录", CampusCardLogic.balanceText(gate, false, true))
    }
}
