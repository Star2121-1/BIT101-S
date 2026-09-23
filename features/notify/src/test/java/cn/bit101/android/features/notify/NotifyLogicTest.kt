package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.FALLBACK_TIME_TABLE
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒纯逻辑的单测。
 *
 * 覆盖的是**最容易出错也最不该出错**的部分：什么时候该提醒、什么时候不该。
 * 全部不依赖 Android，纯 JVM 跑。
 */
class NotifyLogicTest {

    private val table: TimeTable = FALLBACK_TIME_TABLE

    /** 2026-09-21 是周一（第 1 教学周）。 */
    private val monday: LocalDate = LocalDate.of(2026, 9, 21)
    private val firstDay: LocalDate = monday

    private fun course(
        name: String = "操作系统",
        weekday: Int = 1,
        start: Int = 3,
        end: Int = 5,
        weeks: String = "[1][2][3][4][5][6][7][8][9][10][11][12]",
        classroom: String = "文萃楼I404",
        number: String = "CS30001",
    ) = CourseScheduleEntity(
        id = 0,
        term = "2026-2027-1",
        name = name,
        teacher = "老师",
        classroom = classroom,
        description = "",
        weeks = weeks,
        weekday = weekday,
        start_section = start,
        end_section = end,
        campus = "中关村校区",
        number = number,
        credit = 3,
        hour = 48,
        type = "必修",
        category = "专业课",
        department = "计算机学院",
    )

    private fun ddl(
        title: String = "操作系统第三次作业",
        time: LocalDateTime,
        done: Boolean = false,
        uid: String = "lexue-123",
    ) = DDLScheduleEntity(
        id = 0,
        group = "lexue",
        uid = uid,
        title = title,
        text = "",
        time = time,
        done = done,
    )

    // ------------------------------------------------------------ 上课提醒

    @Test
    fun `上课提醒按提前量算时刻`() {
        // 周一第 3 节 09:55 开始 → 提前 10 分钟 = 09:45
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals(1, list.size)
        assertEquals(LocalDateTime.of(monday, LocalTime.of(9, 45)), list[0].at)
        assertEquals(ReminderKind.CLASS, list[0].kind)
    }

    @Test
    fun `提前量可配置`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(classLeadMinutes = 30),
        )

        assertEquals(LocalDateTime.of(monday, LocalTime.of(9, 25)), list[0].at)
    }

    @Test
    fun `已经过了提醒时刻的课不再提醒`() {
        // 09:50 时今天的课（提醒时刻 09:45）已经过去 —— 绝不补发迟到的提醒
        // ⚠️ 窗口 7 天会覆盖到下周一那节课，所以这里断言「今天没有 + 只剩下一周后那条」
        val now = LocalDateTime.of(monday, LocalTime.of(9, 50))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals(1, list.size)
        assertEquals(monday.plusWeeks(1), list[0].at.toLocalDate())
    }

    @Test
    fun `只排窗口内的课`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        // 默认 7 天：今天这条够，下周一那条（7 天零 45 分后）超出窗口 → 只有 1 条
        assertEquals(
            1,
            NotifyLogic.plan(
                courses = listOf(course()),
                ddls = emptyList(),
                now = now,
                firstDay = firstDay,
                table = table,
            ).size,
        )
        // 窗口放到 8 天：下周一那节课进入窗口
        val wider = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(horizonDays = 8),
        )
        assertEquals(2, wider.size)
        assertEquals(monday.plusWeeks(1), wider[1].at.toLocalDate())
    }

    @Test
    fun `窗口外的课不排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(horizonDays = 1),
        )

        assertEquals(1, list.size)
    }

    @Test
    fun `周次不包含当周时不排`() {
        // 第 8 周才上的课：第 1-2 周不该提醒
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course(weeks = "[8][9][10]")),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun `周次未知时不过滤`() {
        // firstDay 未知（开学日没设）→ 宁可多提醒也不漏
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course(weeks = "[8][9][10]")),
            ddls = emptyList(),
            now = now,
            firstDay = null,
            table = table,
        )

        assertEquals(1, list.size)
    }

    @Test
    fun `星期几不符的课不排`() {
        // 周一的窗口里，周三的课不该在今天被提醒（只会排到周三那天）
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course(weekday = 3)),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals(1, list.size)
        assertEquals(monday.plusDays(2), list[0].at.toLocalDate())
    }

    @Test
    fun `节次超出时间表时不排也不崩`() {
        // 学校新增节次而时间表没更新：拿不到时刻，宁可这次不提醒
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course(start = 20, end = 21)),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun `上课提醒文案含时间段课程名与教室`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals("09:55-12:20 · 操作系统 · 文萃楼I404", list[0].text)
        assertEquals("10 分钟后上课", list[0].title)
        assertEquals("schedule", list[0].route)
    }

    @Test
    fun `提前量为零时文案不说零分钟后`() {
        assertEquals("马上上课", NotifyLogic.classTitle(0))
    }

    @Test
    fun `没有教室时用校区兜底`() {
        val text = NotifyLogic.classText(
            course(classroom = ""),
            table,
            LocalTime.of(9, 55),
        )
        assertTrue(text.contains("中关村校区"))
    }

    // ------------------------------------------------------------ DDL 提醒

    @Test
    fun `DDL 排提前一天与提前一小时两条`() {
        // 截止放在周四 23:59（周一凌晨看）：两个窗口都还没过去
        val deadline = LocalDateTime.of(monday.plusDays(3), LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(0, 1))
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = listOf(ddl(time = deadline)),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals(2, list.size)
        assertEquals(LocalDateTime.of(monday.plusDays(2), LocalTime.of(23, 59)), list[0].at) // 提前 1 天
        assertEquals(LocalDateTime.of(monday.plusDays(3), LocalTime.of(22, 59)), list[1].at) // 提前 1 小时
    }

    @Test
    fun `DDL 窗口可分别关掉`() {
        val deadline = LocalDateTime.of(monday.plusDays(3), LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(0, 1))
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = listOf(ddl(time = deadline)),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(ddlDayEnabled = false),
        )

        assertEquals(1, list.size)
        assertEquals(LocalDateTime.of(monday.plusDays(3), LocalTime.of(22, 59)), list[0].at)
    }

    @Test
    fun `已完成的 DDL 不提醒`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(0, 1))
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = listOf(ddl(time = deadline, done = true)),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun `已过期的 DDL 不提醒`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val now = LocalDateTime.of(monday, LocalTime.of(12, 0))
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = listOf(ddl(time = deadline)),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun `DDL 文案写绝对时间`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(22, 0))

        assertEquals("今天 23:59 截止", NotifyLogic.deadlineHint(deadline, now))
        assertEquals(
            "明天 09:00 截止",
            NotifyLogic.deadlineHint(LocalDateTime.of(monday.plusDays(1), LocalTime.of(9, 0)), now),
        )
        assertEquals(
            "后天 09:00 截止",
            NotifyLogic.deadlineHint(LocalDateTime.of(monday.plusDays(2), LocalTime.of(9, 0)), now),
        )
        assertEquals(
            "9月30日 09:00 截止",
            NotifyLogic.deadlineHint(LocalDateTime.of(monday.plusDays(9), LocalTime.of(9, 0)), now),
        )
    }

    @Test
    fun `DDL 文案含标题与截止时间`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(22, 0))
        val text = NotifyLogic.ddlText(ddl(time = deadline), now)

        assertEquals("操作系统第三次作业 · 今天 23:59 截止", text)
    }

    @Test
    fun `标题为空时用内容兜底`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(23, 59))
        val now = LocalDateTime.of(monday, LocalTime.of(22, 0))
        val entity = ddl(time = deadline, title = "").copy(text = "见群通知")

        assertEquals("见群通知 · 今天 23:59 截止", NotifyLogic.ddlText(entity, now))
    }

    // ------------------------------------------------------------ 去重

    @Test
    fun `已发过的提醒不再排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val key = NotifyLogic.courseKey(course(), monday, 10)

        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            sentKeys = setOf(key),
        )

        // 今天的提醒被去重；下周一那条超出 7 天窗口 → 一条都不排
        assertTrue(list.isEmpty())
    }

    @Test
    fun `改提前量后视为新提醒`() {
        // 用户在设置里把 10 分钟改成 20 分钟，应该按新策略再提醒一次
        val k10 = NotifyLogic.courseKey(course(), monday, 10)
        val k20 = NotifyLogic.courseKey(course(), monday, 20)
        assertFalse(k10 == k20)
    }

    @Test
    fun `DDL 两个窗口各有独立去重键`() {
        val d = ddl(time = LocalDateTime.of(monday, LocalTime.of(23, 59)))
        val kDay = NotifyLogic.ddlKey(d, NotifyPolicy.DDL_WINDOW_DAY)
        val kHour = NotifyLogic.ddlKey(d, NotifyPolicy.DDL_WINDOW_HOUR)

        assertFalse(kDay == kHour)
        assertTrue(kDay.contains("lexue-123"))
    }

    // ------------------------------------------------------------ 整体

    @Test
    fun `总开关关掉时不排任何提醒`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = listOf(ddl(time = LocalDateTime.of(monday, LocalTime.of(23, 59)))),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(classEnabled = false, ddlEnabled = false),
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun `结果按时刻升序`() {
        val now = LocalDateTime.of(monday, LocalTime.of(0, 1))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = listOf(ddl(time = LocalDateTime.of(monday, LocalTime.of(23, 59)))),
            now = now,
            firstDay = firstDay,
            table = table,
        )

        assertEquals(list.sortedBy { it.at }, list)
    }

    @Test
    fun `自定义时间表生效`() {
        // 用户把第 3 节改到 10:00 开始 → 提醒相应后移
        val custom = table.toMutableList()
        custom[2] = TimeTableItem(LocalTime.of(10, 0), LocalTime.of(10, 45))
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))

        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = custom,
        )

        assertEquals(LocalDateTime.of(monday, LocalTime.of(9, 50)), list[0].at)
    }

    // ============================================================ 座位签到提醒

    /** 签到截止 = 预约开始 + 60 分钟（座位侧算好传进来），这里只负责定时。 */
    private fun signIn(seatNo: String = "018", deadline: LocalDateTime) =
        SeatReminderInput(seatNo = seatNo, signInDeadline = deadline)

    private fun seatPlan(
        signIns: List<SeatReminderInput>,
        now: LocalDateTime,
        policy: NotifyPolicy = NotifyPolicy(),
        sentKeys: Set<String> = emptySet(),
    ) = NotifyLogic.plan(
        courses = emptyList(),
        ddls = emptyList(),
        now = now,
        firstDay = firstDay,
        table = table,
        policy = policy,
        sentKeys = sentKeys,
        seatSignIns = signIns,
    )

    @Test
    fun `签到提醒排在截止前 N 分钟`() {
        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val deadline = LocalDateTime.of(monday, LocalTime.of(11, 0))

        val list = seatPlan(listOf(signIn(deadline = deadline)), now)

        assertEquals(1, list.size)
        assertEquals(LocalDateTime.of(monday, LocalTime.of(10, 45)), list[0].at)
        assertEquals(ReminderKind.SEAT_SIGN_IN, list[0].kind)
        assertEquals("15 分钟后截止签到", list[0].title)
        assertEquals("座位 018 · 请在 11:00 前刷卡", list[0].text)
        assertEquals(PageShowOnNav.Seat.toPageData().value, list[0].route)
    }

    /** 已经错过截止的不补发 —— 违约已成事实，通知只会添堵。 */
    @Test
    fun `错过签到截止的不再提醒`() {
        val now = LocalDateTime.of(monday, LocalTime.of(11, 30))
        val list = seatPlan(listOf(signIn(deadline = LocalDateTime.of(monday, LocalTime.of(11, 0)))), now)

        assertTrue(list.isEmpty())
    }

    /**
     * ⚠️ 这条与上课提醒**行为相反**，是刻意设计的：
     * 上课提醒过了时刻不补发（「10 分钟后上课」会变成假话），
     * 而签到只要截止还没到就该提醒 —— 用户还能走过去刷卡。
     */
    @Test
    fun `发现得晚也要立刻提醒`() {
        // 10:55 时才排期，按 15 分钟提前量本该 10:45 提醒（已过去）
        val now = LocalDateTime.of(monday, LocalTime.of(10, 55))
        val deadline = LocalDateTime.of(monday, LocalTime.of(11, 0))

        val list = seatPlan(listOf(signIn(deadline = deadline)), now)

        assertEquals(1, list.size)
        // 提醒时刻被抬到「现在」→ 立刻发，而不是被丢掉
        assertEquals(now, list[0].at)
        // 正文仍是绝对时刻，晚发也不会说错话
        assertEquals("座位 018 · 请在 11:00 前刷卡", list[0].text)
    }

    @Test
    fun `关掉座位提醒后不排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val list = seatPlan(
            signIns = listOf(signIn(deadline = LocalDateTime.of(monday, LocalTime.of(11, 0)))),
            now = now,
            policy = NotifyPolicy(seatEnabled = false),
        )

        assertTrue(list.isEmpty())
    }

    /** 截止太远（超出排期窗口）先不排，等它进入 7 天窗口后再排。 */
    @Test
    fun `超出窗口的签到不排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val list = seatPlan(
            signIns = listOf(signIn(deadline = LocalDateTime.of(monday.plusDays(9), LocalTime.of(11, 0)))),
            now = now,
        )

        assertTrue(list.isEmpty())
    }

    /** 同一座位今天约一次、明天再约一次 → 两条独立提醒（键带截止时刻）。 */
    @Test
    fun `同一座位不同时段是两条提醒`() {
        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val list = seatPlan(
            signIns = listOf(
                signIn(deadline = LocalDateTime.of(monday, LocalTime.of(10, 30))),
                signIn(deadline = LocalDateTime.of(monday.plusDays(1), LocalTime.of(10, 30))),
            ),
            now = now,
        )

        assertEquals(2, list.size)
        assertEquals(2, list.map { it.key }.toSet().size)
    }

    /** 已发过的键不再排（同一座位、同一截止时刻）。 */
    @Test
    fun `已发过的签到提醒不重复`() {
        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val input = signIn(deadline = LocalDateTime.of(monday, LocalTime.of(11, 0)))
        val key = NotifyLogic.seatKey(input, 15)

        val list = seatPlan(listOf(input), now, sentKeys = setOf(key))

        assertTrue(list.isEmpty())
    }

    /** 提前量进键：用户改成 30 分钟后应按新策略再提醒一次。 */
    @Test
    fun `改提前量视为新提醒`() {
        val deadline = LocalDateTime.of(monday, LocalTime.of(11, 0))
        val input = signIn(deadline = deadline)

        assertFalse(NotifyLogic.seatKey(input, 15) == NotifyLogic.seatKey(input, 30))

        val now = LocalDateTime.of(monday, LocalTime.of(10, 0))
        val list = seatPlan(listOf(input), now, policy = NotifyPolicy(seatSignInLeadMinutes = 30))

        assertEquals(LocalDateTime.of(monday, LocalTime.of(10, 30)), list[0].at)
    }

    /** 键里的截止时刻含 `:`，取座位号只能靠固定段位。 */
    @Test
    fun `能从去重键取回座位号`() {
        val input = signIn(seatNo = "018", deadline = LocalDateTime.of(monday, LocalTime.of(11, 3, 15)))
        val key = NotifyLogic.seatKey(input, 15)

        assertEquals("018", NotifyLogic.seatNoOfKey(key))
    }

    /** 座位号缺失时文案退化成「座位预约」，不出现「座位  · 」这种残缺拼接。 */
    @Test
    fun `座位号为空时文案降级`() {
        val text = NotifyLogic.seatText(
            signIn(seatNo = "  ", deadline = LocalDateTime.of(monday, LocalTime.of(11, 0)))
        )

        assertEquals("座位预约 · 请在 11:00 前刷卡", text)
    }

    /** 三条源混在一起时统一按时刻排序（排期器依赖这个顺序，便于人工核对）。 */
    @Test
    fun `三类提醒混排后按时刻排序`() {
        val now = LocalDateTime.of(monday, LocalTime.of(0, 1))
        val list = NotifyLogic.plan(
            courses = listOf(course()),
            ddls = listOf(ddl(time = LocalDateTime.of(monday.plusDays(3), LocalTime.of(23, 59)))),
            now = now,
            firstDay = firstDay,
            table = table,
            seatSignIns = listOf(signIn(deadline = LocalDateTime.of(monday, LocalTime.of(9, 0)))),
        )

        assertEquals(list.sortedBy { it.at }, list)
        assertTrue(list.any { it.kind == ReminderKind.SEAT_SIGN_IN })
        assertTrue(list.any { it.kind == ReminderKind.CLASS })
        assertTrue(list.any { it.kind == ReminderKind.DDL })
    }
}
