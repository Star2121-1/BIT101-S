package cn.bit101.android.features.widget

import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [WidgetLogic] 的纯逻辑单测。
 *
 * RemoteViews 的渲染无法在 JVM 单测里跑，所以这里是组件**唯一**能自动化验证的部分 ——
 * 也因此所有「可能出错的判断」都应尽量下沉到这一层，别留在 XML 绑定代码里。
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

    // ------------------------------------------------ 作息时间与「当前/下一节」高亮

    @Test
    fun `节次换算成时间段`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        // 真机实测那天的课就是 3-5 / 6-7 / 8-10 节
        assertEquals("09:55-12:20", WidgetLogic.courseTimeText(t, 3, 5))
        assertEquals("13:20-14:55", WidgetLogic.courseTimeText(t, 6, 7))
        assertEquals("15:15-17:40", WidgetLogic.courseTimeText(t, 8, 10))
        assertEquals("08:00-08:45", WidgetLogic.courseTimeText(t, 1, 1))
    }

    @Test
    fun `节次越界时宁可不给时间，也不要编一个`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        assertEquals("", WidgetLogic.courseTimeText(t, 0, 1))
        assertEquals("", WidgetLogic.courseTimeText(t, 1, 99))
        assertEquals("", WidgetLogic.courseTimeText(emptyList(), 1, 2))
    }

    @Test
    fun `正在上课时高亮那一节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
            course(name = "开源软件开发", start = 8, end = 10),
        )
        // 14:12 落在 6-7 节（13:20-14:55）内 —— 与真机实测的时刻一致
        assertEquals(1 to ClassState.ONGOING, WidgetLogic.focusOf(courses, LocalTime.of(14, 12), t))
    }

    @Test
    fun `课间时高亮下一节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
        )
        // 12:30 是午休，下一节是 13:20 的第 6 节
        assertEquals(1 to ClassState.UPCOMING, WidgetLogic.focusOf(courses, LocalTime.of(12, 30), t))
        // 15:00 是 7 节之后，今天已经没有下一节了
        assertNull(WidgetLogic.focusOf(courses, LocalTime.of(15, 0), t))
    }

    @Test
    fun `今天的课都上完后不再高亮`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        assertNull(WidgetLogic.focusOf(listOf(course(start = 3, end = 5)), LocalTime.of(21, 0), t))
    }

    @Test
    fun `显示窗口跟着当前时刻走，不会停在上午`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "早课", start = 1, end = 2),
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
            course(name = "开源软件开发", start = 8, end = 10),
        )
        val page = WidgetLogic.coursePage(
            courses = courses,
            week = 3,
            weekday = 3,
            now = LocalTime.of(14, 12),
            limit = 3,
            timeTable = t,
        )
        // 4 门课放不下 3 行 → 开窗，且以正在上的那节为中心：
        // 早课被挤出窗口（用户不想在下午看早课），但前后的课都在
        assertEquals("操作系统", page.primary?.main)
        assertEquals("计算机系统导论", page.secondary[0].main)
        assertEquals(ClassState.ONGOING, page.secondary[0].highlight)
        assertEquals("开源软件开发", page.secondary[1].main)
    }

    /**
     * 「起点」有两个容易混淆的分支，必须分开验证：
     * 时间未知 → 从头显示；今天已上完 → 显示末尾。
     * 早期实现把两者合并成「一律取末尾」，导致没有 now 时（降级路径）显示的是当天最后几节课。
     */
    @Test
    fun `今天上完后窗口停在末尾，时间未知时从头显示`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "早课", start = 1, end = 2),
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
            course(name = "开源软件开发", start = 8, end = 10),
        )

        // 21:00 今天的课早结束了：显示末尾几节，而不是停在早课
        val evening = WidgetLogic.coursePage(
            courses = courses, week = 3, weekday = 3,
            now = LocalTime.of(21, 0), limit = 2, timeTable = t,
        )
        assertEquals("计算机系统导论", evening.primary?.main)
        assertEquals("开源软件开发", evening.secondary.single().main)
        assertNull(evening.primary?.highlight)

        // now 为 null（时间未知）：无从判断上没上完，从头显示
        val unknown = WidgetLogic.coursePage(
            courses = courses, week = 3, weekday = 3, limit = 2, timeTable = t,
        )
        assertEquals("早课", unknown.primary?.main)
    }

    @Test
    fun `高度决定行数，且不会超出布局能放下的行数`() {
        // 4×3（上报 193dp）→ 3 行；拖矮到 4×2（110dp）→ 2 行；再矮 → 1 行
        // ⚠️ 两行式行（课程名 + 时间/地点）每行更高，阈值与单行版不同
        assertEquals(3, WidgetLogic.rowsForHeight(193))
        assertEquals(3, WidgetLogic.rowsForHeight(145))
        assertEquals(2, WidgetLogic.rowsForHeight(144))
        assertEquals(2, WidgetLogic.rowsForHeight(110))
        assertEquals(2, WidgetLogic.rowsForHeight(95))
        assertEquals(1, WidgetLogic.rowsForHeight(94))
        // 系统没给尺寸（部分 ROM 不写 options）：按完整行数渲染，宁可多显示
        assertEquals(3, WidgetLogic.rowsForHeight(0))
        assertEquals(3, WidgetLogic.rowsForHeight(-1))
        // full 参数是上限，不能因为布局变高就超出去
        assertEquals(1, WidgetLogic.rowsForHeight(300, full = 1))
        assertEquals(2, WidgetLogic.rowsForHeight(300, full = 2))
    }

    @Test
    fun `课程行带上了上课时间`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course(start = 6, end = 7)),
            week = 3,
            weekday = 3,
            now = LocalTime.of(14, 12),
            timeTable = WidgetLogic.FALLBACK_TIME_TABLE,
        )
        assertEquals("13:20-14:55", page.primary?.time)
    }

    @Test
    fun `用户在设置里改的作息时间表会生效`() {
        // 真实场景：学校改作息 / 用户自己编辑课表设置，组件必须跟着变
        val custom = listOf(TimeTableItem(LocalTime.of(10, 0), LocalTime.of(10, 45)))
        assertEquals("10:00-10:45", WidgetLogic.courseTimeText(custom, 1, 1))
        assertEquals(
            0 to ClassState.ONGOING,
            WidgetLogic.focusOf(listOf(course(start = 1, end = 1)), LocalTime.of(10, 10), custom),
        )
    }

    @Test
    fun `行数上限受限时仍优先显示高亮的那一节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "早课", start = 1, end = 2),
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
        )
        // 组件被拖矮，只显示 1 行 —— 这一行必须是「正在上的那节」
        val page = WidgetLogic.coursePage(
            courses = courses,
            week = 3,
            weekday = 3,
            now = LocalTime.of(14, 12),
            limit = 1,
            timeTable = t,
        )
        assertEquals("计算机系统导论", page.primary?.main)
        assertTrue(page.secondary.isEmpty())
    }

    // ---------------------------------------- 显示窗口（2026-09-21 真机反馈修正）

    /**
     * 真机场景：下午看组件，当天 3 门课只看到 1 门。
     * 旧版「窗口从当前那节开始往后取」把上过的课全切掉了 ——
     * 用户要的是「看到当天的课」，高亮只是标记，不是筛选。
     */
    @Test
    fun `当天的课放得下就全部显示，同时高亮正在上的那节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
            course(name = "开源软件开发", start = 8, end = 10),
        )
        // 16:26：8-10 节（15:15-17:40）正在上 —— 旧版此时只剩这 1 门
        val page = WidgetLogic.coursePage(
            courses = courses, week = 3, weekday = 3,
            now = LocalTime.of(16, 26), timeTable = t,
        )
        assertEquals("操作系统", page.primary?.main)
        assertEquals(2, page.secondary.size)
        assertEquals("开源软件开发", page.secondary[1].main)
        assertEquals(ClassState.ONGOING, page.secondary[1].highlight)
        assertNull(page.primary?.highlight)
    }

    @Test
    fun `课多到放不下时以焦点为中心开窗`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = (1..6).map {
            course(name = "课$it", start = it * 2 - 1, end = it * 2)
        }
        // 12:00：第 5 节（11:35-12:20）正在上，属于第 3 门课
        val page = WidgetLogic.coursePage(
            courses = courses, week = 3, weekday = 3,
            now = LocalTime.of(12, 0), limit = 3, timeTable = t,
        )
        // 窗口以焦点为中心：显示第 2、3、4 门，焦点（第 3 门）落在中间
        assertEquals(3, page.secondary.size + 1)
        assertEquals("课2", page.primary?.main)
        assertNull(page.primary?.highlight)
        assertEquals("课3", page.secondary[0].main)
        assertEquals(ClassState.ONGOING, page.secondary[0].highlight)
        assertEquals("课4", page.secondary[1].main)
    }

    // ---------------------------------------- 登录引导（2026-09-21 新增）

    @Test
    fun `未登录时课程页整页换成登录引导`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), week = 3, weekday = 3, loggedIn = false,
        )
        assertNull(page.primary)
        assertEquals("未登录 BIT101", page.loginPrompt)
    }

    @Test
    fun `未登录时座位页提示未登录座位系统`() {
        val page = WidgetLogic.seatPage(lines = emptyList(), loggedIn = false)
        assertEquals("未登录座位系统", page.loginPrompt)
    }

    @Test
    fun `登录后不再显示引导`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), week = 3, weekday = 3, loggedIn = true,
        )
        assertNull(page.loginPrompt)
        assertEquals("高等数学", page.primary?.main)
    }

    @Test
    fun `未登录的动作键是登录且课程页去登录页`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), week = 3, weekday = 3, loggedIn = false,
        )
        val action = WidgetLogic.actionOf(page, rowLimit = 3)
        assertEquals("登录", action?.label)
        assertEquals(AppRoutes.LOGIN, action?.route)
    }

    @Test
    fun `座位页未登录的动作键也去座位页而不是立即预约`() {
        val page = WidgetLogic.seatPage(lines = emptyList(), loggedIn = false)
        val action = WidgetLogic.actionOf(page, rowLimit = 3)
        assertEquals("登录", action?.label)
        assertEquals("seat", action?.route)
    }

    @Test
    fun `座位页已登录且有余位时显示立即预约`() {
        val page = WidgetLogic.seatPage(
            lines = listOf(WidgetLine(lead = "座位 001", main = "进行中")),
            limit = 3,
            loggedIn = true,
        )
        val action = WidgetLogic.actionOf(page, rowLimit = 3)
        assertEquals("立即预约", action?.label)
        assertEquals("seat", action?.route)
    }

    @Test
    fun `座位页内容占满时不显示动作键`() {
        val page = WidgetLogic.seatPage(
            lines = (1..3).map { WidgetLine(lead = "座位 00$it", main = "进行中") },
            limit = 3,
            loggedIn = true,
        )
        assertNull(WidgetLogic.actionOf(page, rowLimit = 3))
    }
}
