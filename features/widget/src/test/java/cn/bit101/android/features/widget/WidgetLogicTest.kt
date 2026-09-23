package cn.bit101.android.features.widget

import cn.bit101.android.config.common.FocusKeys
import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.ScheduleTabs
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.eclass.EclassActivityLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** 让「今天」落在第 3 周 */
    private val firstDay: LocalDate = today.minusDays(14)

    private fun WidgetPage.courses() = items.filter { !it.muted }
    private fun WidgetPage.frees() = items.filter { it.muted }
    private fun List<DayBlock>.courseNames() =
        mapNotNull { it.course?.name }

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
        val start = LocalDate.of(2026, 9, 7)

        assertEquals(1, WidgetLogic.weekOf(start, start))
        assertEquals(1, WidgetLogic.weekOf(start, start.plusDays(6)))
        assertEquals(2, WidgetLogic.weekOf(start, start.plusDays(7)))
        assertEquals(3, WidgetLogic.weekOf(start, start.plusDays(14)))
    }

    /** 学期首日未知时必须返回 -1，调用方据此**跳过周次过滤**而不是滤掉全部课程。 */
    @Test
    fun `weekOf 首日未知返回 -1，首日之前也返回 -1`() {
        assertEquals(-1, WidgetLogic.weekOf(null, today))
        assertEquals(-1, WidgetLogic.weekOf(LocalDate.of(2026, 10, 1), today))
    }

    // ------------------------------------------------ 一天时间轴：空闲合并

    /** 相邻的空节次必须合并成一段，否则「三节空档」会刷成三行「空闲」。 */
    @Test
    fun `相邻空闲时段自动合并成一段`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "课A", start = 1, end = 2),
            course(name = "课B", start = 6, end = 7),
        )

        val blocks = WidgetLogic.buildDayBlocks(courses, t)

        assertEquals(4, blocks.size)
        assertEquals(BlockKind.COURSE, blocks[0].kind)
        assertEquals("课A", blocks[0].course?.name)

        assertEquals(BlockKind.FREE, blocks[1].kind)
        assertEquals(3, blocks[1].startSection)
        assertEquals(5, blocks[1].endSection)

        assertEquals(BlockKind.COURSE, blocks[2].kind)
        assertEquals("课B", blocks[2].course?.name)

        assertEquals(BlockKind.FREE, blocks[3].kind)
        assertEquals(8, blocks[3].startSection)
        assertEquals(13, blocks[3].endSection)
    }

    @Test
    fun `第一节就有课时前面不插空的空闲`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val blocks = WidgetLogic.buildDayBlocks(listOf(course(start = 1, end = 13)), t)

        assertEquals(1, blocks.size)
        assertEquals(BlockKind.COURSE, blocks[0].kind)
    }

    /** 整天没课 → 给出一整段空闲，而不是让页面空着。 */
    @Test
    fun `整天没课时给出一整段空闲`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val blocks = WidgetLogic.buildDayBlocks(emptyList(), t)

        assertEquals(1, blocks.size)
        assertEquals(BlockKind.FREE, blocks[0].kind)
        assertEquals(1, blocks[0].startSection)
        assertEquals(13, blocks[0].endSection)
    }

    @Test
    fun `课程节次超出时间表范围时不被吞掉`() {
        // 学校新增了节次而设置里的表没更新：宁可时间显示为空，也要把课列出来
        val t = WidgetLogic.FALLBACK_TIME_TABLE // 13 节
        val blocks = WidgetLogic.buildDayBlocks(listOf(course(start = 14, end = 15)), t)

        assertEquals(2, blocks.size)
        assertEquals(BlockKind.FREE, blocks[0].kind)
        assertEquals(BlockKind.COURSE, blocks[1].kind)
        assertEquals(14, blocks[1].startSection)
        assertEquals("", WidgetLogic.courseTimeText(t, 14, 15))
    }

    // ------------------------------------------------ 选哪一天（今天 / 明天）

    @Test
    fun `还没到第一堂课时显示今天并滚到顶部`() {
        val courses = listOf(course(name = "早八", weekday = 3, start = 1, end = 2))
        val at7 = LocalTime.of(7, 0)

        val choice = WidgetLogic.pickDay(courses, today, at7, firstDay)

        assertFalse(choice.isTomorrow)
        assertEquals(today, choice.date)
        assertEquals(0, WidgetLogic.scrollIndexOf(choice.blocks, at7))
    }

    @Test
    fun `过了今天的最后一个时段就显示明天`() {
        val courses = listOf(
            course(name = "今天的课", weekday = 3, start = 1, end = 2),
            course(name = "明天的课", weekday = 4, start = 3, end = 4),
        )

        // 今天只有 1-2 节（08:00-09:35），21:00 早已下课
        val choice = WidgetLogic.pickDay(courses, today, LocalTime.of(21, 0), firstDay)

        assertTrue(choice.isTomorrow)
        assertEquals(today.plusDays(1), choice.date)
        assertEquals(listOf("明天的课"), choice.blocks.courseNames())
    }

    /** 2026-09-22 规则收窄：今天没课也不切明天，停在今天显示整段空闲。 */
    @Test
    fun `今天没课时仍停在今天`() {
        val courses = listOf(course(name = "明天的课", weekday = 4, start = 3, end = 4))

        val choice = WidgetLogic.pickDay(courses, today, LocalTime.of(9, 0), firstDay)

        assertFalse(choice.isTomorrow)
        assertEquals(1, choice.blocks.size)
        assertEquals(BlockKind.FREE, choice.blocks[0].kind)
    }

    /** 2026-09-22 第二次收窄：明天没课也切 —— 显示明天的整段空闲。 */
    @Test
    fun `过了末段即使明天没课也切明天`() {
        val choice = WidgetLogic.pickDay(emptyList(), today, LocalTime.of(21, 0), firstDay)

        assertTrue(choice.isTomorrow)
        assertEquals(today.plusDays(1), choice.date)
        assertEquals(1, choice.blocks.size)
        assertEquals(BlockKind.FREE, choice.blocks[0].kind)
    }

    // ------------------------------------------------ 滚动定位

    @Test
    fun `正在上课时滚到那一节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(course(name = "操作系统", start = 3, end = 5))
        // 3-5 节 = 09:55-12:20；前面 1-2 节是空闲
        val blocks = WidgetLogic.buildDayBlocks(courses, t)

        assertEquals(1, WidgetLogic.scrollIndexOf(blocks, LocalTime.of(11, 0), t))
    }

    @Test
    fun `课间落在空闲时段时滚到那个空闲块`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "课A", start = 1, end = 2),
            course(name = "课B", start = 5, end = 6),
        )
        val blocks = WidgetLogic.buildDayBlocks(courses, t)

        // 10:00 落在 3-5 节的空闲段（09:55-12:20）里
        assertEquals(1, WidgetLogic.scrollIndexOf(blocks, LocalTime.of(10, 0), t))
    }

    @Test
    fun `显示明天时从顶部开始`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val blocks = WidgetLogic.buildDayBlocks(listOf(course(start = 3, end = 5)), t)

        assertEquals(0, WidgetLogic.scrollIndexOf(blocks, LocalTime.of(21, 0), t, isTomorrow = true))
    }

    @Test
    fun `时间未知时从顶部开始`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val blocks = WidgetLogic.buildDayBlocks(listOf(course(start = 3, end = 5)), t)

        assertEquals(0, WidgetLogic.scrollIndexOf(blocks, null, t))
    }

    // ------------------------------------------------ 底部日期栏

    @Test
    fun `日期栏带今天明天前缀，避免把明天误认为今天`() {
        assertEquals("今天 · 9月23日 周三 · 第3周", WidgetLogic.dayFooter(today, today, 3))
        assertEquals(
            "明天 · 9月24日 周四 · 第3周",
            WidgetLogic.dayFooter(today, today.plusDays(1), 3),
        )
        assertEquals("后天 · 9月25日 周五 · 第3周", WidgetLogic.dayFooter(today, today.plusDays(2), 3))
    }

    @Test
    fun `教学周未知时日期栏省略周次`() {
        assertEquals("今天 · 9月23日 周三", WidgetLogic.dayFooter(today, today, -1))
    }

    @Test
    fun `星期几映射正确`() {
        assertEquals("周一", WidgetLogic.weekdayName(1))
        assertEquals("周五", WidgetLogic.weekdayName(5))
        assertEquals("周日", WidgetLogic.weekdayName(7))
    }

    // ------------------------------------------------------------ 课程页

    @Test
    fun `周次未知时不按周过滤，避免显示错误的空态`() {
        val courses = listOf(
            course(name = "高等数学", weekday = 3, start = 1, end = 2, weeks = "[1][2][3]"),
            course(name = "大学物理", weekday = 3, start = 3, end = 4, weeks = "[8][9][10]"),
        )

        // firstDay = null → week = -1：两门都应显示
        val page = WidgetLogic.coursePage(courses, today, now = null, firstDay = null)

        assertFalse("周次未知时不该判为空", page.isEmpty)
        assertEquals(
            listOf("高等数学", "大学物理"),
            page.courses().map { it.main },
        )
    }

    @Test
    fun `周次已知时只显示当周且当天的课`() {
        val courses = listOf(
            course(name = "高等数学", weekday = 3, start = 1, end = 2, weeks = "[1][2][3]"),
            course(name = "大学物理", weekday = 3, start = 3, end = 4, weeks = "[8][9][10]"),
            course(name = "英语", weekday = 4, start = 5, end = 6, weeks = "[1][2][3]"),
        )

        val page = WidgetLogic.coursePage(courses, today, now = null, firstDay = firstDay)

        assertEquals(listOf("高等数学"), page.courses().map { it.main })
    }

    /** 课程行可点（跳课表），空闲行不可点。 */
    @Test
    fun `课程行带 openRoute 空闲行不带`() {
        val courses = listOf(course(name = "高等数学", weekday = 3, start = 1, end = 2))

        val page = WidgetLogic.coursePage(courses, today, now = null, firstDay = firstDay)

        assertEquals(2, page.items.size)
        assertEquals("schedule", page.items[0].openRoute)
        assertNull(page.items[1].openRoute)
    }

    @Test
    fun `课程按节次升序，全天行程保留所有课程与空闲`() {
        val courses = listOf(
            course(name = "下午课", weekday = 3, start = 5, end = 6),
            course(name = "早课", weekday = 3, start = 1, end = 2),
            course(name = "晚课", weekday = 3, start = 9, end = 10),
        )

        val page = WidgetLogic.coursePage(courses, today, now = null, firstDay = firstDay)

        assertEquals(listOf("早课", "下午课", "晚课"), page.courses().map { it.main })
        assertEquals("课之间应插入空闲", 3, page.frees().size)
    }

    /** 列表可滚动后不再裁剪条目 —— 课多也要全部保留，由用户自己滑。 */
    @Test
    fun `课多时也全部保留，交给滚动而不是裁剪`() {
        val courses = (1..6).map { course(name = "课$it", weekday = 3, start = it, end = it) }

        val page = WidgetLogic.coursePage(courses, today, now = null, firstDay = firstDay)

        assertEquals((1..6).map { "课$it" }, page.courses().map { it.main })
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
        val page = WidgetLogic.coursePage(listOf(noRoom), today, now = null, firstDay = firstDay)

        assertEquals("良乡校区", page.courses().first().trail)
    }

    @Test
    fun `空闲行带时间段且标记为弱化`() {
        val page = WidgetLogic.coursePage(
            listOf(course(start = 3, end = 5)), today, now = null, firstDay = firstDay,
        )

        val free = page.frees().first()
        assertEquals("空闲", free.lead)
        assertEquals("08:00-09:35", free.time)
        assertTrue(free.muted)
    }

    @Test
    fun `课程页底部日期栏标明显示的是哪一天`() {
        val page = WidgetLogic.coursePage(
            listOf(course(start = 1, end = 2)), today, today.atTime(11, 0), firstDay,
        )

        assertEquals("今天 · 9月23日 周三 · 第3周", page.footer)
    }

    /** 晚上自动切到明天时，日期栏必须同步变成「明天」，否则会被当成 bug。 */
    @Test
    fun `切到明天时日期栏同步切换`() {
        val courses = listOf(
            course(name = "今天的课", weekday = 3, start = 1, end = 2),
            course(name = "明天的课", weekday = 4, start = 3, end = 4),
        )
        val page = WidgetLogic.coursePage(courses, today, today.atTime(21, 0), firstDay)

        assertEquals("明天 · 9月24日 周四 · 第3周", page.footer)
        assertEquals(listOf("明天的课"), page.courses().map { it.main })
        assertNull("明天的课不该被高亮", page.courses().first().highlight)
    }

    // ------------------------------------------------------------ DDL 页

    /**
     * 未完成在上、已完成在下，各自带栏目标题（2026-09-23 用户要求分区）。
     *
     * 以前已完成的**完全不显示** —— 用户在 App 里看得到、组件里看不到，
     * 会以为是同步出了问题。
     */
    @Test
    fun `DDL 分未完成与已完成两栏`() {
        val ddls = listOf(
            ddl(title = "已交作业", time = now.plusDays(1), done = true),
            ddl(title = "未交作业", time = now.plusDays(1), done = false),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals(
            listOf("未完成 · 1", "未交作业", "已完成 · 1", "已交作业"),
            page.items.map { it.main },
        )
    }

    /**
     * ⚠️ **回归测试（用户实际报过的 bug，2026-09-23 修）**
     *
     * DDL 的数据来自本地库（延河课堂同步 + 用户手动添加），跟 BIT101 的学校会话无关。
     * 原来这页挂了 `bit101LoggedIn`，未登录就整页换成「未登录 BIT101」——
     * 于是用户看到「App 里明明有两条 DDL，桌面上什么都没有」。
     */
    @Test
    fun `未登录 BIT101 时 DDL 页照常显示本地条目`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = listOf(ddl(title = "要交的报告", time = now.plusDays(1))),
            today = today,
            now = now,
            firstDay = firstDay,
            bit101LoggedIn = false,
        )

        val ddlPage = data.pages[PageKind.DDL.ordinal]
        assertNull("DDL 页不该有登录引导", ddlPage.loginPrompt)
        assertEquals(
            listOf("未完成 · 1", "要交的报告"),
            ddlPage.items.map { it.main },
        )
        // 对照：课程页仍按登录态挡住（它的数据确实来自 BIT101 会话）
        assertEquals(
            "未登录 BIT101",
            data.pages[PageKind.COURSE.ordinal].loginPrompt,
        )
    }

    /** 栏目行只是分区标识：不可点、没有第二行。 */
    @Test
    fun `栏目行不可点也不带第二行`() {
        val page = WidgetLogic.ddlPage(listOf(ddl(title = "作业", time = now.plusDays(1))), now)

        val header = page.items.first()
        assertTrue(header.header)
        assertTrue(header.muted)
        assertNull("栏目行不该挂切换 uid", header.toggleDdlUid)
        assertTrue("栏目行不该有右侧信息", header.trail.isEmpty())
    }

    /** 点条目 = 切换完成状态，所以每条都得带上自己的 uid。 */
    @Test
    fun `DDL 条目带切换用的 uid`() {
        val page = WidgetLogic.ddlPage(listOf(ddl(title = "作业A", time = now.plusDays(1))), now)

        val item = page.items.first { !it.header }
        assertEquals("u-作业A", item.toggleDdlUid)
    }

    /** 已完成的弱化显示，且**不写剩余时间** —— 都做完了还写「还剩 3 天」是误导。 */
    @Test
    fun `已完成的条目弱化且不显示剩余时间`() {
        val page = WidgetLogic.ddlPage(
            listOf(ddl(title = "交完了", time = now.plusDays(3), done = true)),
            now,
        )

        val item = page.items.first { !it.header }
        assertTrue(item.muted)
        assertTrue(item.trail.isEmpty())
        assertFalse("已完成的不该标紧急", item.urgent)
    }

    @Test
    fun `DDL 按到期时间升序，最紧急的排首位`() {
        val ddls = listOf(
            ddl(title = "后天", time = now.plusDays(2)),
            ddl(title = "今天", time = now.plusHours(3)),
            ddl(title = "明天", time = now.plusDays(1)),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals(listOf("今天", "明天", "后天"), page.items.filter { !it.header }.map { it.main })
    }

    /** 已过期的未完成项才真正要紧，必须显示（且标红），不能因为过期就滤掉。 */
    @Test
    fun `已过期的未完成 DDL 仍然显示且标记为紧急`() {
        val ddls = listOf(ddl(title = "错过了", time = now.minusDays(1)))

        val page = WidgetLogic.ddlPage(ddls, now)

        val item = page.items.first { !it.header }
        assertEquals("错过了", item.main)
        assertTrue("过期项必须标紧急以便高亮", item.urgent)
        assertEquals("已过期", item.trail)
    }

    /**
     * ⚠️ 远期 DDL **也要显示**（2026-09-23 用户明确要求「不要时间限制」）。
     *
     * 旧行为是只看未来 14 天，于是用户那两条 16 天 / 41 天后的作业在组件上
     * 完全看不见（App 里却有）—— 被当成 bug 报过来的就是这件事。
     */
    @Test
    fun `远期 DDL 也显示，不再限时`() {
        val ddls = listOf(
            ddl(title = "很近", time = now.plusDays(1)),
            ddl(title = "很远", time = now.plusDays(41)),
        )

        val page = WidgetLogic.ddlPage(ddls, now)

        assertEquals(listOf("很近", "很远"), page.items.filter { !it.header }.map { it.main })
    }

    /** 24 小时内到期标红；25 小时后不标 —— 否则组件上永远一片红，红色就失去意义。 */
    @Test
    fun `24 小时内到期标为紧急，超出则不标`() {
        val soon = WidgetLogic.ddlPage(listOf(ddl(time = now.plusHours(23))), now)
        val later = WidgetLogic.ddlPage(listOf(ddl(time = now.plusHours(25))), now)

        assertTrue(soon.items.first { !it.header }.urgent)
        assertFalse(later.items.first { !it.header }.urgent)
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
        val page = WidgetLogic.seatPage(SeatWidgetSnapshot.Snapshot())

        assertTrue(page.isEmpty)
        assertEquals("暂无预约", page.emptyText)
        assertEquals(PageKind.SEAT, page.kind)
    }

    @Test
    fun `座位页保留传入顺序`() {
        val lines = listOf(
            WidgetLine(lead = "14:00", main = "座位 051", trail = "待签到", urgent = true),
            WidgetLine(lead = "监控", main = "座位 108", trail = "尝试 3 次"),
        )

        val page = WidgetLogic.seatPage(SeatWidgetSnapshot.Snapshot(lines = lines))

        assertEquals(listOf("座位 051", "座位 108"), page.items.map { it.main })
        assertTrue(page.items.first().urgent)
    }

    // ------------------------------------------------------------ 整体装配

    @Test
    fun `build 固定返回四页且顺序为 课程-DDL-座位-动态`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = emptyList(),
            today = today,
            now = now,
            firstDay = firstDay,
        )

        assertEquals(4, data.pages.size)
        assertEquals(
            // ⚠️ 页序即页签顺序，用户 2026-09-23 要求「DDL 与动态挨着」：
            // 顺序变了没关系 —— 页号现在存的是**页名**（WidgetPageStore），老用户不会串页
            listOf(PageKind.COURSE, PageKind.DDL, PageKind.ACTIVITY, PageKind.SEAT),
            data.pages.map { it.kind },
        )
    }

    @Test
    fun `build 各页互不干扰：无课时 DDL 页仍正常`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = listOf(ddl(title = "要交的报告", time = now.plusHours(5))),
            today = today,
            now = now,
            firstDay = firstDay,
        )

        // 按页名取页而不是按下标：页序是可以调的，测试不该跟着页序一起改
        fun pageOf(kind: PageKind) = data.pages.first { it.kind == kind }

        assertEquals("没课时课程页应只剩空闲段", 0, pageOf(PageKind.COURSE).courses().size)
        assertEquals(
            "要交的报告",
            pageOf(PageKind.DDL).items.first { !it.header }.main,
        )
        assertTrue("座位页应为空", pageOf(PageKind.SEAT).isEmpty)
        assertTrue("没动态时动态页应为空", pageOf(PageKind.ACTIVITY).isEmpty)
    }

    // ------------------------------------------------ 页号迁移（页序调整后不错页）

    /**
     * ⚠️ 页序在 2026-09-23 被调整过（用户要求「DDL 与动态挨着」）：
     * 旧序 = 课程 → DDL → 座位 → 动态；新序 = 课程 → DDL → 动态 → 座位。
     *
     * 老用户的页号是**旧索引**（int），必须按**旧顺序**还原语义 ——
     * 否则原本停在「座位」的人会被静默带到「动态」页，而且没有任何报错。
     */
    @Test
    fun `旧页号按旧顺序迁移而不是直接当新索引`() {
        assertEquals(PageKind.COURSE, WidgetPageStore.legacyKindOf(0))
        assertEquals(PageKind.DDL, WidgetPageStore.legacyKindOf(1))
        assertEquals(PageKind.SEAT, WidgetPageStore.legacyKindOf(2))
        assertEquals(PageKind.ACTIVITY, WidgetPageStore.legacyKindOf(3))

        // 新旧顺序在 2/3 位上确实不同 —— 这就是不能直接复用旧 int 的原因
        assertEquals(PageKind.ACTIVITY, PageKind.entries[2])
        assertEquals(PageKind.SEAT, PageKind.entries[3])

        assertNull("越界的老页号不迁移", WidgetPageStore.legacyKindOf(9))
        assertNull("负数同理", WidgetPageStore.legacyKindOf(-1))
    }

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
    fun `当天的课全部显示，同时高亮正在上的那节`() {
        val t = WidgetLogic.FALLBACK_TIME_TABLE
        val courses = listOf(
            course(name = "操作系统", start = 3, end = 5),
            course(name = "计算机系统导论", start = 6, end = 7),
            course(name = "开源软件开发", start = 8, end = 10),
        )
        // 16:26：8-10 节（15:15-17:40）正在上
        val page = WidgetLogic.coursePage(courses, today, today.atTime(16, 26), firstDay, t)

        assertEquals(listOf("操作系统", "计算机系统导论", "开源软件开发"), page.courses().map { it.main })
        assertNull(page.courses()[0].highlight)
        assertEquals(ClassState.ONGOING, page.courses()[2].highlight)
        // 应滚动到「正在上的那节」所在的区块（前面有两段空闲夹着）
        assertTrue("应定位到当前时段而不是顶部", page.scrollTo > 0)
    }

    @Test
    fun `课程行带上了上课时间`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course(start = 6, end = 7)),
            today = today,
            now = today.atTime(14, 12),
            firstDay = firstDay,
            timeTable = WidgetLogic.FALLBACK_TIME_TABLE,
        )
        assertEquals("13:20-14:55", page.courses().first().time)
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

    // ---------------------------------------- 登录引导（2026-09-21 新增）

    @Test
    fun `未登录时课程页整页换成登录引导`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), today = today, now = now,
            firstDay = firstDay, loggedIn = false,
        )
        assertTrue(page.items.isEmpty())
        assertEquals("未登录 BIT101", page.loginPrompt)
    }

    @Test
    fun `未登录时座位页提示未登录座位系统`() {
        val page = WidgetLogic.seatPage(snapshot = SeatWidgetSnapshot.Snapshot(), loggedIn = false)
        assertEquals("未登录座位系统", page.loginPrompt)
    }

    @Test
    fun `登录后不再显示引导`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), today = today, now = now,
            firstDay = firstDay, loggedIn = true,
        )
        assertNull(page.loginPrompt)
        assertEquals("高等数学", page.courses().first().main)
    }

    @Test
    fun `未登录的动作键是登录且课程页去登录页`() {
        val page = WidgetLogic.coursePage(
            courses = listOf(course()), today = today, now = now,
            firstDay = firstDay, loggedIn = false,
        )
        val action = WidgetLogic.actionOf(page)
        assertEquals("登录", action?.label)
        assertEquals(AppRoutes.LOGIN, action?.route)
    }

    @Test
    fun `座位页未登录的动作键也去座位页而不是立即预约`() {
        val page = WidgetLogic.seatPage(snapshot = SeatWidgetSnapshot.Snapshot(), loggedIn = false)
        val action = WidgetLogic.actionOf(page)
        assertEquals("登录", action?.label)
        assertEquals("seat", action?.route)
    }

    /** 列表可滚动后动作键固定在列表下方，不再需要「有余位才显示」的判断。 */
    @Test
    fun `座位页已登录时总是显示立即预约`() {
        val page = WidgetLogic.seatPage(
            snapshot = SeatWidgetSnapshot.Snapshot(
                lines = (1..5).map { WidgetLine(lead = "座位 00$it", main = "进行中") },
            ),
            loggedIn = true,
        )
        val action = WidgetLogic.actionOf(page)
        assertEquals("立即预约", action?.label)
        assertEquals("seat", action?.route)
    }

    /**
     * 课程页与 DDL 页都**没有**动作键。
     *
     * DDL 页以前有个「打开 DDL」键（因为条目点击被用来勾选），
     * 2026-09-23 用户要求「点条目直接跳进 App 的 DDL 页」之后它就多余了 —— 已删。
     * 现在只有未登录（登录引导）与座位页（一键/立即预约）才有动作键。
     */
    @Test
    fun `课程页与 DDL 页都没有动作键`() {
        assertNull(WidgetLogic.actionOf(WidgetLogic.coursePage(emptyList(), today, now, firstDay)))
        assertNull(WidgetLogic.actionOf(WidgetLogic.ddlPage(emptyList(), now)))
    }

    /**
     * DDL 行 = **点一下跳进 App 的 DDL 页并定位到这一条**（不再就地勾选）。
     *
     * 勾选信息（[WidgetLine.toggleDdlUid]）仍然带着，留给将来的「勾选模式」用。
     */
    @Test
    fun `DDL 行点击跳进 App 的 DDL 页并带上定位键`() {
        val page = WidgetLogic.ddlPage(
            listOf(ddl(title = "第三章作业", time = now.plusDays(1))),
            now,
        )

        val row = page.items.first { !it.header }
        assertEquals("schedule", row.openRoute)
        assertEquals(ScheduleTabs.DDL, row.openTab)
        // 定位键 = 带归属前缀的 DDL uid（App 侧据此滚到那一条）
        assertEquals(FocusKeys.ddl("u-第三章作业"), row.focusKey)
        // 勾选信息仍是**裸 uid**（将来做「勾选模式」时按 uid 写库），别和定位键混
        assertEquals("u-第三章作业", row.toggleDdlUid)
    }

    /**
     * 动态行 = 点一下跳进 App 的**动态 tab**并定位到那一条。
     *
     * 以前只打开 App 且停在课表 tab（`openRoute = "schedule"` 没带 tab），
     * 用户还得自己再点一下「动态」—— 2026-09-23 用户要求直接落到动态页。
     */
    @Test
    fun `动态行点击跳进 App 的动态 tab 并带上定位键`() {
        val page = WidgetLogic.activityPage(
            listOf(activity(title = "第二章课件", kind = EclassActivityLogic.ActivityKind.MATERIAL)),
            now,
        )

        val row = page.items.first()
        assertEquals("schedule", row.openRoute)
        assertEquals(ScheduleTabs.ACTIVITY, row.openTab)
        assertEquals(FocusKeys.activity("a-第二章课件"), row.focusKey)
    }

    // ------------------------------------------------------------ 动态页

    private fun activity(
        title: String = "第二章课件",
        kind: EclassActivityLogic.ActivityKind = EclassActivityLogic.ActivityKind.MATERIAL,
        time: LocalDateTime? = now.minusHours(3),
    ) = EclassActivityLogic.EclassActivity(
        id = "a-$title",
        courseId = 20268,
        courseName = "操作系统",
        title = title,
        kind = kind,
        time = time,
        // 组件不消费它（点击统一回 App），给个占位即可
        targetUrl = "https://zy-eclass.bit.edu.cn/user/index",
    )

    @Test
    fun `动态页列出动态并带类型标签`() {
        val page = WidgetLogic.activityPage(
            listOf(
                activity(title = "第二章课件", kind = EclassActivityLogic.ActivityKind.MATERIAL),
                activity(title = "第三章作业", kind = EclassActivityLogic.ActivityKind.HOMEWORK),
            ),
            now,
        )

        assertEquals(PageKind.ACTIVITY, page.kind)
        assertEquals(listOf("资料", "作业"), page.items.map { it.lead })
        assertEquals(listOf("第二章课件", "第三章作业"), page.items.map { it.main })
    }

    @Test
    fun `动态页未登录时显示登录引导`() {
        val page = WidgetLogic.activityPage(emptyList(), now, loggedIn = false)

        assertNotNull(page.loginPrompt)
        assertTrue(page.loginPrompt!!.contains("延河课堂"))
        // 引导按钮带用户去「卷」页（延河课堂的登录入口在 App 的 DDL 页）
        assertEquals("schedule", WidgetLogic.loginRoute(PageKind.ACTIVITY))
    }

    @Test
    fun `动态页没有内容时空态是暂无动态`() {
        assertEquals("暂无动态", WidgetLogic.activityPage(emptyList(), now).emptyText)
    }
}
