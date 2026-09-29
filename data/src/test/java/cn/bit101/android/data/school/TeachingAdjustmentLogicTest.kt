package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 教学安排调整解析器的单测。
 *
 * fixture 是**从学校门户抓下来的真实页面**（`src/test/resources/teaching/`），
 * 一字未改 —— 包括导航、页脚这些噪声，因为线上就是这么喂给解析器的。
 * 三份覆盖了两种正文形态：
 * - `notice-2026-guqing`：**日历表格**型
 * - `notice-2025-guqing` / `notice-2026-yuandan`：**纯文字**型
 */
class TeachingAdjustmentLogicTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/teaching/$name")) { "缺 fixture: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parse(name: String): List<TeachingAdjustmentEntry> =
        TeachingAdjustmentLogic.parseNotice(fixture(name), name, "https://jxzx.bit.edu.cn/jxyx/kctz/$name")

    private fun planAt(entries: List<TeachingAdjustmentEntry>, y: Int, m: Int, d: Int): DayPlan? =
        entries.firstOrNull { it.date == LocalDate.of(y, m, d) }?.plan

    // ---------------------------------------------------------- 表格型

    /**
     * ⚠️ **本次事故的回归测试**（2026-09-29 用户截图）：
     * 通知写明「10月10日（星期六）上班、上课，按照星期四教学计划安排上课」，
     * 而我们的课表是「星期几 + 周次」的循环模板 —— 那天会显示「没课」。
     * 10/10 缺课是真的损失，所以这条必须锁死。
     */
    @Test
    fun `2026 国庆：10月10日周六按周四课表上课`() {
        val e = parse("notice-2026-guqing.html")
        assertEquals(DayPlan.MakeUp(4), planAt(e, 2026, 10, 10))
    }

    @Test
    fun `2026 国庆：10月1日至8日放假`() {
        val e = parse("notice-2026-guqing.html")
        for (d in 1..8) {
            assertEquals("10月${d}日应为无课", DayPlan.NoClass, planAt(e, 2026, 10, d))
        }
        // 9 日恢复上课
        assertNull(planAt(e, 2026, 10, 9))
    }

    @Test
    fun `2026 中秋：9月25日至27日放假，9月20日补休`() {
        val e = parse("notice-2026-guqing.html")
        for (d in 25..27) {
            assertEquals("9月${d}日应为无课", DayPlan.NoClass, planAt(e, 2026, 9, d))
        }
        assertEquals(DayPlan.NoClass, planAt(e, 2026, 9, 20))
    }

    @Test
    fun `2026 中秋国庆：条目数与边界都正确`() {
        val e = parse("notice-2026-guqing.html")
        // 9/20 + 9/25~9/27 + 10/1~10/8 + 10/10 = 1 + 3 + 8 + 1
        assertEquals(13, e.size)
        assertEquals(LocalDate.of(2026, 9, 20), e.first().date)
        assertEquals(LocalDate.of(2026, 10, 10), e.last().date)
    }

    // ---------------------------------------------------------- 文字型

    /** 2025 版没有表格，只能靠正文句式 —— 这条验证「文字兜底」真能用。 */
    @Test
    fun `2025 国庆：9月30日至10月9日放假共10天`() {
        val e = parse("notice-2025-guqing.html")
        assertEquals(DayPlan.NoClass, planAt(e, 2025, 9, 30))
        for (d in 1..9) {
            assertEquals("10月${d}日应为无课", DayPlan.NoClass, planAt(e, 2025, 10, d))
        }
    }

    @Test
    fun `2025 国庆：9月28日周日按周二、10月11日周六按周四`() {
        val e = parse("notice-2025-guqing.html")
        assertEquals(DayPlan.MakeUp(2), planAt(e, 2025, 9, 28))
        assertEquals(DayPlan.MakeUp(4), planAt(e, 2025, 10, 11))
    }

    /**
     * ⚠️ 年份要靠**发布日期**推：通知发于 2025-12-15，正文写的是「1月1日」——
     * 那属于 **2026** 年。硬编码年份会把整条记录算到错误的一年（原型踩过）。
     */
    @Test
    fun `2026 元旦：发布日期在 2025 年，正文 1 月要算到次年`() {
        val e = parse("notice-2026-yuandan.html")
        for (d in 1..3) {
            assertEquals("1月${d}日应为无课", DayPlan.NoClass, planAt(e, 2026, 1, d))
        }
        assertEquals(DayPlan.MakeUp(5), planAt(e, 2026, 1, 4))
        // 不能落到 2025 年去
        assertNull(planAt(e, 2025, 1, 1))
    }

    // ---------------------------------------------------------- 反面：不许猜

    @Test
    fun `认不出就不产出 —— 绝不猜`() {
        val html = "<html><body><p>各位同学：今天天气不错。</p><p>1月1日无安排说明。</p></body></html>"
        assertTrue(TeachingAdjustmentLogic.parseNotice(html, "x", "u").isEmpty())
    }

    /** 没有发布日期就无法定年 ⇒ 整条通知都放弃（宁可少一条，也不要算到错年份）。 */
    @Test
    fun `没有发布日期就整条放弃`() {
        val html = """
            <html><body>
            <p>9月20日（星期日）补休校庆开放日，原教学计划安排取消。</p>
            <p>10月10日（星期六）上班、上课，按照星期四教学计划安排上课。</p>
            </body></html>
        """.trimIndent()
        assertTrue(TeachingAdjustmentLogic.parseNotice(html, "x", "u").isEmpty())
    }

    /** 「（星期六）上班、上课」里的「上课」不能被抓成补课 —— 补课必须写明「按照…教学计划」。 */
    @Test
    fun `只提上班上课、没写按哪份课表时不算补课`() {
        val html = """
            <html><body>
            <p>发布日期:2026-09-03</p>
            <p>10月10日（星期六）上班、上课，原有教学安排取消。</p>
            </body></html>
        """.trimIndent()
        assertTrue(TeachingAdjustmentLogic.parseNotice(html, "x", "u").isEmpty())
    }

    // ---------------------------------------------------------- 文本归一化

    /**
     * ⚠️ 门户会把一个词拆进不同标签，抽完文本会留下**词内空格**
     * （实测「中秋假 期」「无 教学安排」「（星期日） 放假」）。
     * 不归一化的话关键词匹配会**静默失败** —— 原型就是这么漏掉 9/26、9/27 的。
     */
    @Test
    fun `汉字之间的空格必须去掉，否则关键词匹配会静默失败`() {
        val t = TeachingAdjustmentLogic.visibleText(
            "<p>26 <span>中秋假</span> 期</p><p>补休·无 教学安排</p>"
        )
        assertTrue("实际: $t", t.contains("中秋假期"))
        assertTrue("实际: $t", t.contains("无教学安排"))
    }

    @Test
    fun `数字与汉字之间的空格要保留（共 3 天）`() {
        val t = TeachingAdjustmentLogic.visibleText("<p>放假，共 3 天</p>")
        assertTrue("实际: $t", t.contains("共 3 天"))
    }

    // ---------------------------------------------------------- 列表页

    @Test
    fun `列表页能认出教学安排调整类通知`() {
        val list = TeachingAdjustmentLogic.parseNoticeList(
            fixture("list.html"),
            "https://jxzx.bit.edu.cn/jxyx/kctz/",
        )
        val hits = list.filter { TeachingAdjustmentLogic.isAdjustmentNotice(it.title) }
        assertTrue("应至少认出 3 条，实际 ${hits.size}", hits.size >= 3)
        assertTrue(hits.any { it.title.contains("2026年中秋节、国庆节") })
        assertTrue("链接要补全成绝对地址", hits.all { it.url.startsWith("https://jxzx.bit.edu.cn/") })
        // 非该类通知不要混进来
        assertTrue(list.none { it.title.contains("封楼") && TeachingAdjustmentLogic.isAdjustmentNotice(it.title) })
    }

    @Test
    fun `planOf 查不到就返回 null（调用方保持原样，不改课表）`() {
        val e = parse("notice-2026-guqing.html")
        assertNull(TeachingAdjustmentLogic.planOf(e, LocalDate.of(2026, 10, 12)))
    }

    /** 展示用的 note 必须来自原文，用户才能自己核对。 */
    @Test
    fun `note 保留原文措辞`() {
        val e = parse("notice-2026-guqing.html")
        val x = e.first { it.date == LocalDate.of(2026, 10, 10) }
        assertTrue("实际: ${x.note}", x.note.contains("按") && x.note.contains("课表"))
    }
}
