package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.FALLBACK_TIME_TABLE
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.data.school.DayPlan
import cn.bit101.android.data.school.TeachingAdjustmentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------ 考试提醒

    private fun exam(
        name: String = "高等数学",
        courseId: String = "MA10001",
        date: LocalDate = monday.plusDays(3),
        begin: LocalTime = LocalTime.of(8, 0),
        end: LocalTime = LocalTime.of(10, 0),
        classroom: String = "文萃楼I404",
        seatId: String = "012",
    ) = ExamScheduleEntity(
        id = 0,
        term = "2026-2027-1",
        name = name,
        courseId = courseId,
        teacher = "老师",
        classroom = classroom,
        date = date,
        beginTime = begin,
        endTime = end,
        examMode = "集中考试",
        seatId = seatId,
    )

    private fun examPlan(
        exams: List<ExamScheduleEntity>,
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
        exams = exams,
    )

    /** 周四 08:00 考试 → 前一天 08:00「明天有考试」+ 当天 07:00「1 小时后开考」。 */
    @Test
    fun `考试排两条提醒：前一天与考前N分钟`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))     // 周一 09:00
        val examDate = monday.plusDays(3)                          // 周四

        val list = examPlan(listOf(exam(date = examDate)), now)

        assertEquals(2, list.size)
        assertTrue(list.all { it.kind == ReminderKind.EXAM })
        assertEquals(LocalDateTime.of(examDate.minusDays(1), LocalTime.of(8, 0)), list[0].at)
        assertEquals("明天有考试", list[0].title)
        assertEquals(LocalDateTime.of(examDate, LocalTime.of(7, 0)), list[1].at)
        assertEquals("1 小时后开考", list[1].title)
    }

    /** 考试正文必须有座位号 —— 考试按座位号入座，这是它区别于上课提醒的地方。 */
    @Test
    fun `考试正文含时间课名教室与座位`() {
        assertEquals(
            "08:00-10:00 · 高等数学 · 文萃楼I404 · 座位 012",
            NotifyLogic.examText(exam()),
        )
    }

    /** 服务端没给座位号时不出现「座位 」这种残缺拼接。 */
    @Test
    fun `座位号为空时正文省略座位段`() {
        assertEquals(
            "08:00-10:00 · 高等数学 · 文萃楼I404",
            NotifyLogic.examText(exam(seatId = "   ")),
        )
    }

    /** 已经开考的考试再提醒也没用（正在考的那场别来添乱）。 */
    @Test
    fun `已开考的考试不再提醒`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        // 考试 08:00 开始，现在 09:00 —— 已经在考了
        val list = examPlan(listOf(exam(date = monday, begin = LocalTime.of(8, 0))), now)

        assertEquals(0, list.size)
    }

    /**
     * ⚠️ 与座位签到**相反**：过了提醒时刻不补发。
     *
     * 「1 小时后开考」迟发就是假话；考试也不是「发现得晚还能补救」的事。
     */
    @Test
    fun `过了时刻的考试窗口不补发`() {
        val examDate = monday.plusDays(1)                          // 周二 08:00 考试
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))     // 周一 09:00
        // 前一天窗口 = 周一 08:00（已过）→ 不发；考前窗口 = 周二 07:00（未来）→ 发

        val list = examPlan(listOf(exam(date = examDate)), now)

        assertEquals(1, list.size)
        assertEquals(LocalDateTime.of(examDate, LocalTime.of(7, 0)), list[0].at)
        assertEquals("1 小时后开考", list[0].title)
    }

    @Test
    fun `考前一天提醒可单独关闭`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = examPlan(
            listOf(exam(date = monday.plusDays(3))),
            now,
            policy = NotifyPolicy(examDayEnabled = false),
        )

        assertEquals(1, list.size)
        assertEquals("1 小时后开考", list[0].title)
    }

    @Test
    fun `考试提醒总开关关闭后一条都不排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val list = examPlan(
            listOf(exam(date = monday.plusDays(3))),
            now,
            policy = NotifyPolicy(examEnabled = false),
        )

        assertEquals(0, list.size)
    }

    /** 提前量可配置，且整小时写「N 小时」。 */
    @Test
    fun `考试提前量可配置且整小时写小时`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val examDate = monday.plusDays(3)

        val list = examPlan(
            listOf(exam(date = examDate)),
            now,
            policy = NotifyPolicy(examLeadMinutes = 120),
        )

        assertEquals(LocalDateTime.of(examDate, LocalTime.of(6, 0)), list[1].at)
        assertEquals("2 小时后开考", list[1].title)

        assertEquals("马上开考", NotifyLogic.examTitle(0))
        assertEquals("30 分钟后开考", NotifyLogic.examTitle(30))
        assertEquals("1 小时后开考", NotifyLogic.examTitle(60))
    }

    /** 同一门课考两次（期中 + 期末）是两条独立提醒 —— 键里带日期。 */
    @Test
    fun `同一门课的两场考试是两条提醒`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val mid = exam(date = monday.plusDays(3))
        val final = exam(date = monday.plusDays(5))

        val list = examPlan(listOf(mid, final), now)

        assertEquals(4, list.size)
        assertTrue(list.any { it.at == LocalDateTime.of(monday.plusDays(3), LocalTime.of(7, 0)) })
        assertTrue(list.any { it.at == LocalDateTime.of(monday.plusDays(5), LocalTime.of(7, 0)) })
    }

    /**
     * ⚠️ 去重键**刻意不含具体时刻**：服务端把 08:00 微调成 08:30 后，
     * 若键里带时刻就会判成「新提醒」而重复打扰一次。
     */
    @Test
    fun `考试键不含具体时刻_改时间不重复提醒`() {
        val a = exam(date = monday.plusDays(3), begin = LocalTime.of(8, 0))
        val b = exam(date = monday.plusDays(3), begin = LocalTime.of(8, 30))

        assertEquals(NotifyLogic.examKey(a, NotifyPolicy.EXAM_WINDOW_DAY), NotifyLogic.examKey(b, NotifyPolicy.EXAM_WINDOW_DAY))
    }

    /** 提前量进键：改了提前量应按新策略再提醒一次（与上课 / 座位提醒同口径）。 */
    @Test
    fun `考试提前量进键`() {
        assertTrue(NotifyLogic.examKey(exam(), NotifyLogic.examLeadWindow(60)) !=
            NotifyLogic.examKey(exam(), NotifyLogic.examLeadWindow(30)))
    }

    /** 已发过的键不再排。 */
    @Test
    fun `考试已发过的键不再排`() {
        val now = LocalDateTime.of(monday, LocalTime.of(9, 0))
        val e = exam(date = monday.plusDays(3))
        val sent = setOf(
            NotifyLogic.examKey(e, NotifyPolicy.EXAM_WINDOW_DAY),
            NotifyLogic.examKey(e, NotifyLogic.examLeadWindow(60)),
        )

        assertEquals(0, examPlan(listOf(e), now, sentKeys = sent).size)
    }

    /** 课程号缺失时退化成课名，键仍可用。 */
    @Test
    fun `课程号为空时键退化成课名`() {
        val e = exam(name = "大学物理", courseId = "  ")
        assertTrue(NotifyLogic.examKey(e, NotifyPolicy.EXAM_WINDOW_DAY).contains("大学物理"))
    }

    /** 考试开始时刻 = 日期 + 开始时间。 */
    @Test
    fun `考试开始时刻拼接正确`() {
        assertEquals(
            LocalDateTime.of(monday.plusDays(3), LocalTime.of(8, 0)),
            NotifyLogic.examStartAt(exam(date = monday.plusDays(3))),
        )
    }

    /**
     * 从键里取日期要**按格式认**、不依赖位置。
     *
     * ⚠️ 按位置（`split(":")[2]`）取的话，课名里混进一个半角冒号就会整段错位 →
     * worker 二次校验判成「这场考试不存在」→ **该发的提醒被静默丢掉**。
     */
    @Test
    fun `从考试键取日期不依赖位置`() {
        val normal = exam(date = LocalDate.of(2026, 9, 24))
        assertEquals(
            LocalDate.of(2026, 9, 24),
            NotifyLogic.examDateOfKey(NotifyLogic.examKey(normal, NotifyPolicy.EXAM_WINDOW_DAY)),
        )

        // 课名带半角冒号（课程号为空 → 键里第二段就是课名）
        val weird = exam(name = "专题: 前沿", courseId = "", date = LocalDate.of(2026, 10, 8))
        val key = NotifyLogic.examKey(weird, NotifyLogic.examLeadWindow(60))
        assertEquals(LocalDate.of(2026, 10, 8), NotifyLogic.examDateOfKey(key))
    }

    /** 形状对不上就返回 null —— 调用方据此放弃这次提醒（宁可漏一次，也不要发错）。 */
    @Test
    fun `考试键里没有日期时返回 null`() {
        assertNull(NotifyLogic.examDateOfKey("exam:MA10001:not-a-date:1d"))
        assertNull(NotifyLogic.examDateOfKey(""))
    }
    // -------------------------------------- 到点校正：迟到的提醒不能说谎、不该发的就别发

    /**
     * ⚠️ 这是**实机事故的回归测试**（2026-09-29 用户截图）：
     * 13:20 的课，排期时刻 13:10 写死标题「10 分钟后上课」；
     * 那天下午设备在休眠/免打扰，WorkManager 被拖到 **15:33** 才跑 ——
     * 于是**下课后 38 分钟**弹出一条「10 分钟后上课」。
     * 二次校验只拦了「换了一天」，从没拦「课已经开始」。
     */
    @Test
    fun `课已开始就不再补发 —— 下课后再弹 10 分钟后上课 比不弹更糟`() {
        val at = LocalDateTime.of(2026, 9, 29, 13, 10)
        assertNull(NotifyLogic.classLeadAtFireTime(at, 10, LocalDateTime.of(2026, 9, 29, 15, 33)))
    }

    /** 上课那一秒起就算「已开始」；早一分钟仍要发（提前 10 分钟 → 还剩 1 分钟）。 */
    @Test
    fun `上课时刻是补发的截止线`() {
        val at = LocalDateTime.of(2026, 9, 29, 13, 10)
        val start = LocalDateTime.of(2026, 9, 29, 13, 20)
        assertNull(NotifyLogic.classLeadAtFireTime(at, 10, start))
        assertEquals(1L, NotifyLogic.classLeadAtFireTime(at, 10, start.minusMinutes(1)))
    }

    /** 迟到但没迟到过头：标题要按**实际剩余分钟**重算，不能沿用排期时的数字。 */
    @Test
    fun `迟到的提醒按实际剩余分钟重算标题`() {
        val at = LocalDateTime.of(2026, 9, 29, 13, 10)
        val now = LocalDateTime.of(2026, 9, 29, 13, 16)   // 迟了 6 分钟
        val left = NotifyLogic.classLeadAtFireTime(at, 10, now)!!
        assertEquals(4L, left)
        assertEquals("4 分钟后上课", NotifyLogic.classTitle(left))
    }

    /** 提前量跨零点时，上课时刻要看 `at + 提前量`，不能拿提醒时刻的日期糊弄。 */
    @Test
    fun `提前量跨零点也按上课时刻判断`() {
        val at = LocalDateTime.of(2026, 9, 29, 23, 55)     // 次日 00:05 的课、提前 10 分钟
        assertEquals(10L, NotifyLogic.classLeadAtFireTime(at, 10, at))
        assertNull(NotifyLogic.classLeadAtFireTime(at, 10, LocalDateTime.of(2026, 9, 30, 0, 5)))
    }

    /**
     * ⚠️ 与上课 / 考试**方向相反**：座位签到「发现得晚也必须立刻补发」
     * （错过签到记一次违约、累计 5 次暂停 7 天），所以过了截止**照样发**，
     * 只是标题要换成实话。
     */
    @Test
    fun `座位过了签到截止照样要发，只是标题换成实话`() {
        val deadline = LocalDateTime.of(2026, 9, 29, 11, 3)
        assertTrue(NotifyLogic.seatLeadAtFireTime(deadline, deadline.plusMinutes(20)) < 0)
        assertEquals(
            "签到即将截止",
            NotifyLogic.seatTitle(NotifyLogic.seatLeadAtFireTime(deadline, deadline.plusMinutes(20))),
        )
        assertEquals(5L, NotifyLogic.seatLeadAtFireTime(deadline, deadline.minusMinutes(5)))
        assertEquals("5 分钟后截止签到", NotifyLogic.seatTitle(5))
    }

    /** 考试**过了时刻就不补发**（迟到 20 分钟才说「马上开考」只会让人更慌）。 */
    @Test
    fun `考试过了时刻不补发`() {
        val start = LocalDateTime.of(2026, 9, 29, 8, 0)
        assertNull(NotifyLogic.examLeadAtFireTime(start, start))
        assertNull(NotifyLogic.examLeadAtFireTime(start, start.plusMinutes(1)))
        assertEquals(55L, NotifyLogic.examLeadAtFireTime(start, start.minusMinutes(55)))
        assertEquals("55 分钟后开考", NotifyLogic.examTitle(55))
        assertEquals("1 小时后开考", NotifyLogic.examTitle(60))
    }

    // -------------------------------------------------- 去重键解析（都别按位置取）

    /**
     * 键的第二段是「课程号或课名」，**课名是自由文本**。
     * 课名里带一个半角冒号，老写法 `split(":")[2]` 就会拿到课名的后半截 →
     * 二次校验判成「那天没课」→ **该发的提醒被静默丢掉**（比多发一条糟得多）。
     */
    @Test
    fun `日期段按格式取 —— 课名带半角冒号也不能错位`() {
        val weird = course(name = "专题:前沿技术", number = "")
        val key = NotifyLogic.courseKey(weird, monday, 10)
        assertEquals("course:专题:前沿技术:$monday:3:10", key)
        assertEquals(monday, NotifyLogic.dateOfKey(key))
    }

    @Test
    fun `提前量从末段取，前面的冒号不影响`() {
        assertEquals(10L, NotifyLogic.trailingLeadMinutes("course:专题:前沿:$monday:3:10"))
        assertNull(NotifyLogic.trailingLeadMinutes("course:专题:$monday:3:abc"))
        assertNull(NotifyLogic.trailingLeadMinutes(""))
    }

    /** 座位键里的截止时刻**自身含冒号**，不能 split 后按位置取。 */
    @Test
    fun `座位键的截止时刻按切末段取回`() {
        val key = "seat:018:2026-09-23T11:03:15:15"
        assertEquals(LocalDateTime.of(2026, 9, 23, 11, 3, 15), NotifyLogic.seatDeadlineOfKey(key))
        assertEquals(15L, NotifyLogic.trailingLeadMinutes(key))
        assertEquals("018", NotifyLogic.seatNoOfKey(key))
    }

    /** 认格式：不是座位键就别硬猜（宁可保留原标题，也不要拿错时刻去算）。 */
    @Test
    fun `不是座位键时取不到截止时刻`() {
        assertNull(NotifyLogic.seatDeadlineOfKey("course:操作系统:$monday:3:10"))
        assertNull(NotifyLogic.seatDeadlineOfKey("seat:018:坏掉的时刻:15"))
        assertNull(NotifyLogic.seatDeadlineOfKey(""))
    }

    /** 考试键末段是 `lead15` 这类带前缀的窗口，要剥掉前缀才拿到分钟数。 */
    @Test
    fun `考试键末段剥前缀取提前量`() {
        assertEquals(15L, NotifyLogic.trailingExamLeadMinutes("exam:CS30001:2026-09-24:lead15"))
        assertNull(NotifyLogic.trailingExamLeadMinutes("exam:CS30001:2026-09-24:day1"))
    }

    /** 从 `courseKey` 往返：键末段必须与排期时的提前量一致（否则重算会用错提前量）。 */
    @Test
    fun `上课键末段就是提前量`() {
        for (lead in listOf(0L, 5L, 10L, 30L)) {
            val key = NotifyLogic.courseKey(course(), monday, lead)
            assertEquals(lead, NotifyLogic.trailingLeadMinutes(key))
        }
    }

    // ------------------------------------------------------------ 课表调整（补课）提醒

    /** 2026-10-10 是周六，那天按周四课表上课 —— 与学校《国庆教学安排调整》原文一致。 */
    private val makeUpDate: LocalDate = LocalDate.of(2026, 10, 10)

    private fun adjustment(
        date: LocalDate = makeUpDate,
        plan: DayPlan = DayPlan.MakeUp(targetWeekday = 4),
        note: String = "按 10月10日（周六）课表上课",
    ) = TeachingAdjustmentEntry(
        date = date,
        plan = plan,
        note = note,
        sourceTitle = "关于2026年国庆节教学安排调整的通知",
        sourceUrl = "https://jxzx.bit.edu.cn/jxyx/kctz/x.htm",
    )

    /**
     * 补课提醒排在**补课日前一天 20:00** —— 睡前能看到，够安排第二天。
     *
     * ⚠️ 固定钟点（而不是「提前 N 小时」）是有意的：20:00 是用户真正在刷手机的时刻。
     */
    @Test
    fun `补课提醒排在补课日前一天 20 点`() {
        val now = LocalDateTime.of(2026, 10, 8, 12, 0)
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            adjustments = listOf(adjustment()),
        )

        assertEquals(1, list.size)
        val r = list.single()
        assertEquals(ReminderKind.ADJUSTMENT, r.kind)
        assertEquals(LocalDateTime.of(2026, 10, 9, 20, 0), r.at)
        assertEquals("明天按周四课表上课", r.title)
        // 正文写**绝对日期**：通知可能晚到，"相对时间"会变成假话
        assertEquals("10/10（周六）按周四课表上课", r.text)
        assertEquals(PageShowOnNav.Schedule.toPageData().value, r.route)
    }

    /**
     * ⚠️ **只提醒补课、不提醒放假**（2026-10-09 与用户定）。
     *
     * 缺课是真实损失；放假只是「不用去」，而且假期连续（国庆 7 天会连发一周）——
     * 那是打扰，不是提醒。
     */
    @Test
    fun `放假不提醒`() {
        val now = LocalDateTime.of(2026, 10, 1, 9, 0)
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            adjustments = listOf(
                adjustment(date = LocalDate.of(2026, 10, 2), plan = DayPlan.NoClass, note = "补休·无教学安排"),
                adjustment(date = LocalDate.of(2026, 10, 3), plan = DayPlan.NoClass, note = "国庆假期"),
            ),
        )
        assertTrue(list.isEmpty())
    }

    /** 关掉开关就一条都不排（与其它提醒同一口径：设置要马上生效）。 */
    @Test
    fun `关掉补课提醒开关就不排`() {
        val now = LocalDateTime.of(2026, 10, 8, 12, 0)
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            policy = NotifyPolicy(adjustmentEnabled = false),
            adjustments = listOf(adjustment()),
        )
        assertTrue(list.isEmpty())
    }

    /**
     * ⚠️ **过时不补发**（与考试同向、与座位签到反向）：补课当天才弹「按周四上课」
     * 是句废话 —— 人都该出门了。用户需要的是**前一天晚上**知道。
     */
    @Test
    fun `过了补课日不补发`() {
        val date = LocalDate.of(2026, 10, 10)
        // 前一天：说「明天」
        assertEquals(
            "明天按周四课表上课",
            NotifyLogic.adjustmentTitleAtFireTime(date, 4, LocalDateTime.of(2026, 10, 9, 20, 0)),
        )
        // 当天：标题要说实话（WorkManager 被 Doze 拖过零点时就是这种情况）
        assertEquals(
            "今天按周四课表上课",
            NotifyLogic.adjustmentTitleAtFireTime(date, 4, LocalDateTime.of(2026, 10, 10, 0, 30)),
        )
        // 已经过了那一天：别发
        assertNull(NotifyLogic.adjustmentTitleAtFireTime(date, 4, LocalDateTime.of(2026, 10, 11, 9, 0)))
    }

    /**
     * ⚠️ 排期时「明天」，到点可能已经跨天 —— 所以标题必须**按到点的实际日期重算**。
     *
     * 这正是「正文写绝对时间」那条原则**没覆盖到的地方：正文是绝对的，标题不是**。
     */
    @Test
    fun `补课标题按到点时的实际日期重算`() {
        val date = LocalDate.of(2026, 10, 10)
        val at = LocalDateTime.of(2026, 10, 9, 20, 0)
        val late = LocalDateTime.of(2026, 10, 10, 7, 30)   // Doze 拖了 11.5 小时

        assertEquals("明天按周四课表上课", NotifyLogic.adjustmentTitleAtFireTime(date, 4, at))
        assertEquals("今天按周四课表上课", NotifyLogic.adjustmentTitleAtFireTime(date, 4, late))
    }

    /**
     * 键里**只有日期**：不带时刻（学校微调补课日时键不变 ⇒ 不重复打扰）、
     * 不带课名/备注（自由文本带一个半角冒号就会让「按格式认日期」整段错位）。
     */
    @Test
    fun `补课键只有日期且能往返取回`() {
        val key = NotifyLogic.adjustmentKey(adjustment())
        assertEquals("adjustment:2026-10-10", key)
        assertEquals(makeUpDate, NotifyLogic.dateOfKey(key))

        // 备注里带半角冒号也不该影响取日期（键里根本没有备注）
        val weird = NotifyLogic.adjustmentKey(adjustment(note = "调休: 按周四上课"))
        assertEquals(makeUpDate, NotifyLogic.dateOfKey(weird))
    }

    /** 已发过的键不再排（同一补课日只打扰一次）。 */
    @Test
    fun `已发过的补课提醒不再排`() {
        val now = LocalDateTime.of(2026, 10, 8, 12, 0)
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            sentKeys = setOf(NotifyLogic.adjustmentKey(adjustment())),
            adjustments = listOf(adjustment()),
        )
        assertTrue(list.isEmpty())
    }

    /** 超出排期窗口（7 天）的补课先不排，等下次重排时再进窗口。 */
    @Test
    fun `太远的补课这轮不排`() {
        val now = LocalDateTime.of(2026, 10, 1, 12, 0)
        val list = NotifyLogic.plan(
            courses = emptyList(),
            ddls = emptyList(),
            now = now,
            firstDay = firstDay,
            table = table,
            adjustments = listOf(adjustment(date = LocalDate.of(2026, 10, 20))),
        )
        assertTrue(list.isEmpty())
    }

    /**
     * ⚠️⚠️ **标题与正文里的「周X」必须是同一种写法**。
     *
     * 这是「两边该一致却各自有断言」的典型：标题曾写「明天按**四**课表上课」、
     * 正文写「按**周四**课表上课」，两个断言各锁各的、谁也不会红 ——
     * 但用户在同一条通知里读到的是两种说法（这正是写这条测试时抓出来的 bug）。
     * 课表列头角标 `badgeText` 用的是同一份 [weekdayCn]，三处一起对齐。
     */
    @Test
    fun `补课标题与正文的星期几写法一致`() {
        val title = NotifyLogic
            .adjustmentTitleAtFireTime(makeUpDate, 4, LocalDateTime.of(2026, 10, 9, 20, 0))!!
        val text = NotifyLogic.adjustmentText(makeUpDate, 4)
        assertTrue("标题里应出现「按周四」，实际：$title", title.contains("按周四"))
        assertTrue("正文里应出现「按周四」，实际：$text", text.contains("按周四"))
    }
}
