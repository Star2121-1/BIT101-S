package cn.bit101.android.features.widget

import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [WidgetLogic] 的纯逻辑单测。
 *
 * Glance 的 UI 无法在 JVM 单测里跑，所以这里是组件**唯一**能自动化验证的部分 ——
 * 也因此所有「可能出错的判断」都应尽量下沉到这一层，别留在 composable 里。
 */
class WidgetLogicTest {

    // ------------------------------------------------------------ 构造工具

    private fun course(
        name: String = "高等数学",
        weekday: Int = 3,
        start: Int = 1,
        end: Int = 2,
        weeks: String = "[1][2][3][4][5][6][7][8][9][10][11][12]",
        classroom: String = "综A-101",
        campus: String = "良乡校区",
    ) = CourseScheduleEntity(
        id = 0,
        term = "2026-2027-1",
        name = name,
        teacher = "张三",
        classroom = classroom,
        description = "",
        weeks = weeks,
        weekday = weekday,
        start_section = start,
        end_section = end,
        campus = campus,
        number = "MATH1001",
        credit = 4,
        hour = 64,
        type = "必修",
        category = "文化课",
        department = "数学学院",
    )

    private fun ddl(
        title: String = "作业1",
        time: LocalDateTime,
        done: Boolean = false,
        group: String = "乐学",
    ) = DDLScheduleEntity(
        id = 0,
        group = group,
        uid = "u-$title",
        title = title,
        text = "",
        time = time,
        done = done,
    )

    private val today: LocalDate = LocalDate.of(2026, 9, 23) // 周三
    private val now: LocalDateTime = today.atTime(9, 0)

    // ------------------------------------------------------------ 周次匹配

    /**
     * 这是最容易写错的地方：`weeks` 是 `[1][2][11]` 这种带方括号的标记串，
     * 用 `contains(week.toString())` 会让第 1 周匹配到 `[11]`、`[21]`。
     */
    @Test
    fun `周次必须按完整标记匹配，不能被 11 误匹配为第 1 周`() {
        val weeks = "[1][2][11][12][21]"

        assertTrue("第 1 周应命中 [1]", WidgetLogic.weeksContains(weeks, 1))
        assertTrue("第 11 周应命中 [11]", WidgetLogic.weeksContains(weeks, 11))
        assertTrue("第 21 周应命中 [21]", WidgetLogic.weeksContains(weeks, 21))

        assertFalse("第 3 周不该命中", WidgetLogic.weeksContains(weeks, 3))
        assertFalse("第 2 周命中 [2] 但第 22 周不该被误判", WidgetLogic.weeksContains(weeks, 22))
    }

    @Test
    fun `weekOf 从学期首日算起，首日即第 1 周`() {
        val firstDay = LocalDate.of(2026, 9, 7)

        assertEquals(1, WidgetLogic.weekOf(firstDay, firstDay))
        assertEquals(1, WidgetLogic.weekOf(firstDay, firstDay.plusDays(6)))
        assertEquals(2, WidgetLogic.weekOf(firstDay, firstDay.plusDays(7)))
        assertEquals(3, WidgetLogic.weekOf(firstDay, firstDay.plusDays(14)))
    }

    /** 学期首日未知时必须返回 -1，调用方据此**跳过周次过滤**而不是滤掉全部课程。 */
    @Test
    fun `weekOf 首日未知返回 -1，首日之前也返回 -1`() {
        assertEquals(-1, WidgetLogic.weekOf(null, today))
        assertEquals(-1, WidgetLogic.weekOf(LocalDate.of(2026, 10, 1), today))
    }

    // ------------------------------------------------------------ 课程页

    @Test
    fun `周次未知时不按周过滤，避免显示错误的空态`() {
        val courses = listOf(
            course(name = "高等数学", weekday = 3, weeks = "[1][2][3]"),
            course(name = "大学物理", weekday = 3, weeks = "[8][9][10]"),
        )

        // week = -1（未知）：两门都应显示 —— 宁可多显示，也不能显示错的「今日无课」
        val page = WidgetLogic.coursePage(courses, week = -1, weekday = 3)

        assertFalse("周次未知时不该判为空", page.isEmpty)
        assertEquals("高等数学", page.primary?.main)
        assertEquals(listOf("大学物理"), page.secondary.map { it.main })
    }

    @Test
    fun `周次已知时只显示当周且当天的课`() {
        val courses = listOf(
            course(name = "高等数学", weekday = 3, weeks = "[1][2][3]"),
            course(name = "大学物理", weekday = 3, weeks = "[8][9][10]"), // 第 3 周不上
            course(name = "英语", weekday = 4, weeks = "[1][2][3]"),      // 不是今天
        )

        val page = WidgetLogic.coursePage(courses, week = 3, weekday = 3)

        assertEquals("高等数学", page.primary?.main)
        assertTrue("其余课都不该出现", page.secondary.isEmpty())
    }

    @Test
    fun `课程按节次升序，最早的一节作为主行`() {
        val courses = listOf(
            course(name = "下午课", weekday = 3, start = 5, end = 6),
            course(name = "早课", weekday = 3, start = 1, end = 2),
            course(name = "晚课", weekday = 3, start = 9, end = 10),
        )

        val page = WidgetLogic.coursePage(courses, week = 3, weekday = 3)

        assertEquals("早课", page.primary?.main)
        assertEquals(listOf("下午课", "晚课"), page.secondary.map { it.main })
    }

    @Test
    fun `无课当天空态文案是今日无课`() {
        val page = WidgetLogic.coursePage(emptyList(), week = 3, weekday = 3)

        assertTrue(page.isEmpty)
        assertNull(page.primary)
        assertEquals("今日无课", page.emptyText)
    }

    /** 组件高度有限，除主行外最多 2 行 —— 再多也看不全，反而挤掉主行。 */
    @Test
    fun `次行最多两条`() {
        val courses = (1..6).map { course(name = "课$it", weekday = 3, start = it, end = it) }

        val page = WidgetLogic.coursePage(courses, week = 3, weekday = 3)

        assertEquals(WidgetPage.MAX_SECONDARY, page.secondary.size)
        assertEquals("课1", page.primary?.main)
    }

    @Test
    fun `节次文案单节次不显示区间`() {
        assertEquals("5 节", WidgetLogic.sectionLabel(5, 5))
        assertEquals("1-2节", WidgetLogic.sectionLabel(1, 2))
        assertEquals("9-10节", WidgetLogic.sectionLabel(9, 10))
    }

    @Test
    fun `教室为空时回落到校区，避免右侧空白`() {
        val noRoom = course(name = "体育", weekday = 3, classroom = "", campus = "良乡校区")
        val page = WidgetLogic.coursePage(listOf(noRoom), week = 3, weekday = 3)

        assertEquals("良乡校区", page.primary?.trail)
    }

    // ------------------------------------------------------------ DDL 页

    @Test
    fun `DDL 只取未完成项，已完成的不显示`() {
        val ddls = listOf(
            ddl(title = "已交作业", time = now.plusDays(1), done = true),
            ddl(title = "未交作业", time = now.plusDays(1), done = false),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals("未交作业", page.primary?.main)
        assertTrue(page.secondary.isEmpty())
    }

    @Test
    fun `DDL 按到期时间升序，最紧急的排首位`() {
        val ddls = listOf(
            ddl(title = "后天", time = now.plusDays(2)),
            ddl(title = "今天", time = now.plusHours(3)),
            ddl(title = "明天", time = now.plusDays(1)),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals("今天", page.primary?.main)
        assertEquals(listOf("明天", "后天"), page.secondary.map { it.main })
    }

    /** 已过期的未完成项才真正要紧，必须显示（且标红），不能因为过期就滤掉。 */
    @Test
    fun `已过期的未完成 DDL 仍然显示且标记为紧急`() {
        val ddls = listOf(ddl(title = "错过了", time = now.minusDays(1)))

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals("错过了", page.primary?.main)
        assertTrue("过期项必须标紧急以便高亮", page.primary!!.urgent)
        assertEquals("已过期", page.primary!!.trail)
    }

    @Test
    fun `超出视野的远期 DDL 不显示`() {
        val ddls = listOf(
            ddl(title = "很近", time = now.plusDays(1)),
            ddl(title = "太远", time = now.plusDays(WidgetLogic.DDL_HORIZON_DAYS + 1)),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals("很近", page.primary?.main)
        assertTrue("远期项应被滤掉", page.secondary.isEmpty())
    }

    /** 24 小时内到期标红；25 小时后不标 —— 否则组件上永远一片红，红色就失去意义。 */
    @Test
    fun `24 小时内到期标为紧急，超出则不标`() {
        val soon = WidgetLogic.ddlPage(listOf(ddl(time = now.plusHours(23))), now)
        val later = WidgetLogic.ddlPage(listOf(ddl(time = now.plusHours(25))), now)

        assertTrue(soon.primary!!.urgent)
        assertFalse(later.primary!!.urgent)
    }

    // ------------------------------------------------------------ 剩余时间

    /**
     * 已过期必须明说「已过期」。显示「还剩 -3 小时」用户不知道该怎么办 ——
     * 负数是程序视角，不是人的视角。
     */
    @Test
    fun `剩余时间文案：过期说已过期，不出现负数`() {
        assertEquals("已过期", WidgetLogic.remainText(now.minusMinutes(1), now))
        assertEquals("已过期", WidgetLogic.remainText(now.minusDays(3), now))
        assertEquals("已过期", WidgetLogic.remainText(now, now))
    }

    @Test
    fun `剩余时间按量级切换单位`() {
        assertEquals("30 分钟", WidgetLogic.remainText(now.plusMinutes(30), now))
        assertEquals("59 分钟", WidgetLogic.remainText(now.plusMinutes(59), now))
        assertEquals("1 小时", WidgetLogic.remainText(now.plusMinutes(60), now))
        assertEquals("23 小时", WidgetLogic.remainText(now.plusHours(23), now))
        assertEquals("1 天", WidgetLogic.remainText(now.plusHours(24), now))
        assertEquals("2 天", WidgetLogic.remainText(now.plusDays(2), now))
    }

    // ------------------------------------------------------------ 座位页

    @Test
    fun `座位页无数据时空态文案是暂无预约`() {
        val page = WidgetLogic.seatPage(emptyList())

        assertTrue(page.isEmpty)
        assertEquals("暂无预约", page.emptyText)
        assertEquals(PageKind.SEAT, page.kind)
    }

    @Test
    fun `座位页保留传入顺序，首条作为主行`() {
        val lines = listOf(
            WidgetLine(lead = "14:00", main = "座位 051", trail = "待签到", urgent = true),
            WidgetLine(lead = "监控", main = "座位 108", trail = "尝试 3 次"),
        )

        val page = WidgetLogic.seatPage(lines)

        assertEquals("座位 051", page.primary?.main)
        assertTrue(page.primary!!.urgent)
        assertEquals(listOf("座位 108"), page.secondary.map { it.main })
    }

    // ------------------------------------------------------------ 整体装配

    @Test
    fun `build 固定返回三页且顺序为 课程-DDL-座位`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = emptyList(),
            seatLines = emptyList(),
            today = today,
            now = now,
            week = 3,
            weekday = 3,
        )

        assertEquals(3, data.pages.size)
        assertEquals(
            listOf(PageKind.COURSE, PageKind.DDL, PageKind.SEAT),
            data.pages.map { it.kind },
        )
    }

    @Test
    fun `build 各页互不干扰：无课时 DDL 页仍正常`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = listOf(ddl(title = "要交的报告", time = now.plusHours(5))),
            seatLines = emptyList(),
            today = today,
            now = now,
            week = 3,
            weekday = 3,
        )

        assertTrue("课程页应为空", data.pages[0].isEmpty)
        assertEquals("要交的报告", data.pages[1].primary?.main)
        assertTrue("座位页应为空", data.pages[2].isEmpty)
    }

    // ------------------------------------------------------------ 辅助

    @Test
    fun `副标题周次已知时带周次，未知时只显示星期`() {
        assertEquals("周三 · 第 3 周", WidgetLogic.dateSubtitle(today, 3))
        assertEquals("周三", WidgetLogic.dateSubtitle(today, -1))
    }

    @Test
    fun `星期几映射正确`() {
        // 2026-09-21 是周一
        val monday = LocalDate.of(2026, 9, 21)
        assertEquals("周一 · 第 1 周", WidgetLogic.dateSubtitle(monday, 1))

        val sunday = LocalDate.of(2026, 9, 27)
        assertEquals("周日 · 第 1 周", WidgetLogic.dateSubtitle(sunday, 1))
    }

    @Test
    fun `跨零点检测`() {
        assertTrue(WidgetLogic.dayChanged(null, today))
        assertTrue(WidgetLogic.dayChanged(today.minusDays(1), today))
        assertFalse(WidgetLogic.dayChanged(today, today))
    }

    @Test
    fun `上课时间段判定：深夜不刷新`() {
        assertFalse(WidgetLogic.isClassHours(java.time.LocalTime.of(3, 0)))
        assertFalse(WidgetLogic.isClassHours(java.time.LocalTime.of(23, 30)))
        assertTrue(WidgetLogic.isClassHours(java.time.LocalTime.of(8, 0)))
        assertTrue(WidgetLogic.isClassHours(java.time.LocalTime.of(21, 59)))
    }
}
