package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.courseTimeText
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.config.setting.base.sectionStart
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.data.database.entity.startAt
import cn.bit101.android.data.school.DayPlan
import cn.bit101.android.data.school.TeachingAdjustmentEntry
import cn.bit101.android.data.school.dateLabelCn
import cn.bit101.android.data.school.weekdayCn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 提醒的种类。
 *
 * 用来决定通知的渠道与文案，也用于「同一门课的多个提醒」的去重键。
 */
enum class ReminderKind {
    /** 上课提醒：课前 N 分钟。 */
    CLASS,

    /** 作业/DDL 截止提醒。 */
    DDL,

    /**
     * 座位签到提醒：签到截止前 N 分钟。
     *
     * ⚠️ 这条**必须单独成类**，不能并进 DDL —— 它有时效性极强的后果：
     * 错过签到会**记一次违约**，累计 5 次暂停 7 天（见 `docs/seatlib-contract.md` 第 10 节）。
     */
    SEAT_SIGN_IN,

    /**
     * 考试提醒：考前一天 + 考前 N 分钟。
     *
     * ⚠️ 单独成类（而不是并进上课提醒）：考试只有一次机会，错过无法补救，
     * 通知要给 HIGH 并且**带上座位号** —— 上课迟到还能进教室，考试迟到就得联系老师。
     */
    EXAM,

    /**
     * 教学安排调整（**补课**）：补课日**前一天 20:00**。
     *
     * ⚠️ 单独成类（而不是并进上课提醒）：这条说的是「**课表本身变了**」——
     * 用户按平时的课表出门就会走错、甚至整天缺课。它比「快上课了」更早、
     * 也更需要看进去，所以文案与时机都不一样。
     *
     * ⚠️ **只报补课、不报放假**（2026-10-09 与用户定）：缺课是真实损失；
     * 放假只是「不用去」，而且假期是连续的（国庆 7 天会连发一周）—— 那是打扰，不是提醒。
     */
    ADJUSTMENT,
}

/**
 * 座位提醒的输入。
 *
 * ⚠️ 定义在 notify 侧，而不是直接用座位模块的 `ReservationRecord` ——
 * **notify 不依赖 `features:seat`**（依赖方向见 `docs/notify.md`），
 * 由座位侧转换成这个模型后传进来。
 */
data class SeatReminderInput(
    /** 座位号，如 `018`。 */
    val seatNo: String,
    /**
     * 签到截止时刻。
     *
     * 规则：当日预约需在开始后 **60 分钟**内刷卡，次日预约需在次日 **9:00** 前刷卡。
     * 座位侧已经算好了这个时刻（`Reservation.signInDeadline`），这里直接用。
     */
    val signInDeadline: LocalDateTime,
)

/**
 * 一条**已排定**的提醒。
 *
 * 纯数据，不含任何 Android 依赖 —— 这样「什么时候该提醒」可以完全用 JVM 单测覆盖。
 */
data class Reminder(
    /** 去重键：同一个键只发一次（见 [NotifyLogic.reminderKey]）。 */
    val key: String,
    val kind: ReminderKind,
    /** 应该提醒的时刻。 */
    val at: LocalDateTime,
    val title: String,
    val text: String,
    /** 点击通知跳到哪一页（组件那套路由约定，值取 `PageShowOnNav.toPageData().value`）。 */
    val route: String,
)

/**
 * 提醒策略（由设置项组装）。
 *
 * 单独抽出来是为了单测能直接给不同策略、不必碰 DataStore。
 */
data class NotifyPolicy(
    val classEnabled: Boolean = true,
    val classLeadMinutes: Long = 10,
    val ddlEnabled: Boolean = true,
    val ddlDayEnabled: Boolean = true,
    val ddlHourEnabled: Boolean = true,
    /** 座位签到提醒。 */
    val seatEnabled: Boolean = true,
    /**
     * 签到提醒的提前量（分钟）。
     *
     * 契约规则是「开始后 60 分钟内刷卡」，提前 15 分钟足够从容走过去；
     * 再早反而会被当成"还早着呢"而忽略。
     */
    val seatSignInLeadMinutes: Long = 15,
    /** 考试提醒。考试只有一次机会，默认开。 */
    val examEnabled: Boolean = true,
    /**
     * 教学安排调整（补课）提醒。
     *
     * 默认开 —— **缺课是真实损失**（点名、进度落下），而且这条是「课表变了」，
     * 按平时的课表出门就会走错。
     */
    val adjustmentEnabled: Boolean = true,
    /** 考试前一天提醒（固定提前 24 小时）。 */
    val examDayEnabled: Boolean = true,
    /**
     * 考试前多少分钟提醒。默认 60：够从容走到考场、找座位、上厕所。
     *
     * 与上课提醒不同，**不改小**：考试要提前到场，提前 10 分钟才动身就晚了。
     */
    val examLeadMinutes: Long = 60,
    /** 只排未来这么多天内的提醒；更远的等下次重排。 */
    val horizonDays: Long = 7,
) {
    companion object {
        /** DDL 的两个提醒窗口（键会进去重键，**改这里等于改历史记录**，谨慎）。 */
        const val DDL_WINDOW_DAY = "1d"
        const val DDL_WINDOW_HOUR = "1h"

        /** 考试「前一天」窗口。 */
        const val EXAM_WINDOW_DAY = "1d"
    }
}

/**
 * 提醒的全部纯逻辑。
 *
 * 设计要点：
 * - 输入是**已从 Room 取好的数据** + 当前时间，输出是「该排哪些提醒」——
 *   不碰数据库、不碰 Android、不碰设置读取，因此可以完整单测。
 * - 所有筛选（未过期 / 在窗口内 / 未发过）都在这里做完，
 *   调用方（worker / 排期器）只负责「把结果交给 WorkManager」。
 */
object NotifyLogic {

    /** 排提醒的时间范围：从现在起 [NotifyPolicy.horizonDays] 天。 */
    fun plan(
        courses: List<CourseScheduleEntity>,
        ddls: List<DDLScheduleEntity>,
        now: LocalDateTime,
        firstDay: LocalDate?,
        table: TimeTable,
        policy: NotifyPolicy = NotifyPolicy(),
        sentKeys: Set<String> = emptySet(),
        seatSignIns: List<SeatReminderInput> = emptyList(),
        exams: List<ExamScheduleEntity> = emptyList(),
        adjustments: List<TeachingAdjustmentEntry> = emptyList(),
    ): List<Reminder> {
        val until = now.plusDays(policy.horizonDays)
        val out = mutableListOf<Reminder>()

        if (policy.classEnabled) {
            out += classReminders(courses, now, until, firstDay, table, policy, sentKeys)
        }
        if (policy.ddlEnabled) {
            out += ddlReminders(ddls, now, until, policy, sentKeys)
        }
        if (policy.seatEnabled) {
            out += seatReminders(seatSignIns, now, until, policy, sentKeys)
        }
        if (policy.examEnabled) {
            out += examReminders(exams, now, until, policy, sentKeys)
        }
        if (policy.adjustmentEnabled) {
            out += adjustmentReminders(adjustments, now, until, sentKeys)
        }

        // 按时刻排序：便于人工核对，也让「最近的一条」一目了然
        return out.sortedBy { it.at }
    }

    // ------------------------------------------------------------ 座位签到提醒

    /**
     * 预约签到时限提醒。
     *
     * ⚠️ 与上课提醒**故意不同**：上课提醒过了时刻就**不补发**（「10 分钟后上课」
     * 迟发会变成假话）；而这里即使我们**发现得晚**也要发 —— 只要签到截止时刻
     * 还没到，用户就还能补救（走过去刷卡）。所以 `at` 落在过去时**改为立刻发**。
     *
     * 通知正文写**绝对截止时刻**，因此早发晚发都不会说错话。
     */
    private fun seatReminders(
        signIns: List<SeatReminderInput>,
        now: LocalDateTime,
        until: LocalDateTime,
        policy: NotifyPolicy,
        sentKeys: Set<String>,
    ): List<Reminder> {
        val lead = policy.seatSignInLeadMinutes.coerceAtLeast(0)
        val out = mutableListOf<Reminder>()

        signIns.forEach { input ->
            // 截止时刻已过：不补发（违约已成事实，提醒只会让人懊恼）
            if (!input.signInDeadline.isAfter(now)) return@forEach
            if (input.signInDeadline.isAfter(until)) return@forEach

            // 「该提醒的时刻」已过去 → 立刻发（见上面的说明）
            val at = input.signInDeadline.minusMinutes(lead).coerceAtLeast(now)

            val key = seatKey(input, lead)
            if (key in sentKeys) return@forEach

            out += Reminder(
                key = key,
                kind = ReminderKind.SEAT_SIGN_IN,
                at = at,
                title = seatTitle(lead),
                text = seatText(input),
                route = PageShowOnNav.Seat.toPageData().value,
            )
        }
        return out
    }

    /** 「15 分钟后截止签到」；提前量为 0 时不写「0 分钟后」。 */
    fun seatTitle(leadMinutes: Long): String =
        if (leadMinutes <= 0) "签到即将截止" else "$leadMinutes 分钟后截止签到"

    /**
     * 通知正文，如 `座位 018 · 请在 11:03 前刷卡`。
     *
     * ⚠️ 用**绝对时刻**而不是「还剩 15 分钟」：通知可能因系统调度晚到，
     * 绝对时刻永远是对的。
     */
    fun seatText(input: SeatReminderInput): String {
        val seat = input.seatNo.trim()
        val prefix = if (seat.isBlank()) "座位预约" else "座位 $seat"
        return "$prefix · 请在 ${hm(input.signInDeadline.toLocalTime())} 前刷卡"
    }

    // ------------------------------------------------------------ 上课提醒

    private fun classReminders(
        courses: List<CourseScheduleEntity>,
        now: LocalDateTime,
        until: LocalDateTime,
        firstDay: LocalDate?,
        table: TimeTable,
        policy: NotifyPolicy,
        sentKeys: Set<String>,
    ): List<Reminder> {
        val lead = policy.classLeadMinutes.coerceAtLeast(0)
        val out = mutableListOf<Reminder>()

        // 逐日扫描（而不是逐课程 × 逐日）—— 覆盖天数通常远小于课程数，且更容易看出边界
        var date = now.toLocalDate()
        while (!date.isAfter(until.toLocalDate())) {
            val week = weekOf(firstDay, date)
            courses.asSequence()
                .filter { it.weekday == date.dayOfWeek.value }
                .filter { week <= 0 || weeksContains(it.weeks, week) }
                .forEach { course ->
                    val start = sectionStart(table, course.start_section) ?: return@forEach
                    val startAt = LocalDateTime.of(date, start)
                    val at = startAt.minusMinutes(lead)
                    if (at.isBefore(now) || at.isAfter(until)) return@forEach

                    val key = courseKey(course, date, lead)
                    if (key in sentKeys) return@forEach

                    out += Reminder(
                        key = key,
                        kind = ReminderKind.CLASS,
                        at = at,
                        title = classTitle(lead),
                        text = classText(course, table, start),
                        route = PageShowOnNav.Schedule.toPageData().value,
                    )
                }
            date = date.plusDays(1)
        }
        return out
    }

    /** 「马上上课」/「10 分钟后上课」——提前量为 0 时不写「0 分钟后」。 */
    fun classTitle(leadMinutes: Long): String =
        if (leadMinutes <= 0) "马上上课" else "$leadMinutes 分钟后上课"

    /**
     * 通知正文，如 `09:55-12:20 操作系统 · 文萃楼I404`。
     *
     * ⚠️ 写成**绝对时间**而不是「还有 10 分钟」：通知可能因系统调度晚到几分钟，
     * 绝对时间永远是对的，相对时间会变成假话。
     */
    fun classText(
        course: CourseScheduleEntity,
        table: TimeTable,
        startTime: LocalTime,
    ): String {
        val span = courseTimeText(table, course.start_section, course.end_section)
            .ifBlank { hm(startTime) }
        val where = course.classroom.ifBlank { course.campus }
        return listOf(span, course.name, where)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    // ------------------------------------------------------------ DDL 提醒

    private fun ddlReminders(
        ddls: List<DDLScheduleEntity>,
        now: LocalDateTime,
        until: LocalDateTime,
        policy: NotifyPolicy,
        sentKeys: Set<String>,
    ): List<Reminder> {
        val windows = buildList {
            if (policy.ddlDayEnabled) add(NotifyPolicy.DDL_WINDOW_DAY to 1L)
            if (policy.ddlHourEnabled) add(NotifyPolicy.DDL_WINDOW_HOUR to 0L)
        }
        val out = mutableListOf<Reminder>()

        ddls.asSequence()
            // 已完成的不提醒；已过期的也不提醒（迟到的提醒只添堵）
            .filter { !it.done && it.time.isAfter(now) }
            .forEach { ddl ->
                windows.forEach { (windowKey, days) ->
                    val at = if (days > 0) ddl.time.minusDays(days) else ddl.time.minusHours(1)
                    if (at.isBefore(now) || at.isAfter(until)) return@forEach

                    val key = ddlKey(ddl, windowKey)
                    if (key in sentKeys) return@forEach

                    out += Reminder(
                        key = key,
                        kind = ReminderKind.DDL,
                        at = at,
                        title = "作业即将截止",
                        text = ddlText(ddl, now),
                        route = PageShowOnNav.Schedule.toPageData().value,
                    )
                }
            }
        return out
    }

    /** `操作系统第三次作业 · 明天 23:59 截止`。 */
    fun ddlText(ddl: DDLScheduleEntity, now: LocalDateTime): String {
        val title = ddl.title.ifBlank { ddl.text }.trim()
        val when_ = deadlineHint(ddl.time, now)
        return if (title.isBlank()) when_ else "$title · $when_"
    }

    /**
     * 截止时刻的人话描述：今天 / 明天 / 8月25日 + 时分。
     *
     * 跨年才写年份 —— 学生看到的 DDL 基本都在几周内，写年份反而更长。
     */
    fun deadlineHint(deadline: LocalDateTime, now: LocalDateTime): String {
        val dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), deadline.toLocalDate())
        val day = when {
            dayDiff == 0L -> "今天"
            dayDiff == 1L -> "明天"
            dayDiff == 2L -> "后天"
            deadline.year == now.year -> "${deadline.monthValue}月${deadline.dayOfMonth}日"
            else -> "${deadline.year}年${deadline.monthValue}月${deadline.dayOfMonth}日"
        }
        return "$day ${hm(deadline.toLocalTime())} 截止"
    }

    // ------------------------------------------------------------ 考试提醒

    /**
     * 考试提醒：**考前一天 + 考前 N 分钟**，各一条。
     *
     * ⚠️ 与座位签到提醒**故意相反**：这里过了时刻就**不补发**。
     * 「1 小时后开考」迟发就是假话；考试也不像签到那样「发现得晚还能补救」
     * —— 都开考了才弹提醒，只会让人慌张。
     *
     * ⚠️ 不按教学周过滤：考试日期是服务端给的**绝对日期**，与第几周无关。
     */
    private fun examReminders(
        exams: List<ExamScheduleEntity>,
        now: LocalDateTime,
        until: LocalDateTime,
        policy: NotifyPolicy,
        sentKeys: Set<String>,
    ): List<Reminder> {
        val lead = policy.examLeadMinutes.coerceAtLeast(0)
        val out = mutableListOf<Reminder>()

        exams.asSequence()
            // 已经开考（或更早）的跳过：正在考的那场再提醒也没用
            .filter { examStartAt(it).isAfter(now) }
            .forEach { exam ->
                val start = examStartAt(exam)

                // (窗口标识, 提醒时刻, 标题)
                val windows: List<Triple<String, LocalDateTime, String>> = buildList {
                    if (policy.examDayEnabled) {
                        add(Triple(NotifyPolicy.EXAM_WINDOW_DAY, start.minusDays(1), "明天有考试"))
                    }
                    add(Triple(examLeadWindow(lead), start.minusMinutes(lead), examTitle(lead)))
                }

                windows.forEach { (window, at, title) ->
                    // 与上课提醒同一口径：过时不补、超出窗口不排
                    if (at.isBefore(now) || at.isAfter(until)) return@forEach

                    val key = examKey(exam, window)
                    if (key in sentKeys) return@forEach

                    out += Reminder(
                        key = key,
                        kind = ReminderKind.EXAM,
                        at = at,
                        title = title,
                        text = examText(exam),
                        route = PageShowOnNav.Schedule.toPageData().value,
                    )
                }
            }
        return out
    }

    /**
     * 考试开始的绝对时刻。
     *
     * 委托给 data 层的 `ExamScheduleEntity.startAt` —— 考试列表页（`features:schedule`）
     * 用的是同一个，两边各写一遍迟早会在跨天/跨年这种边界上走偏。
     */
    fun examStartAt(exam: ExamScheduleEntity): LocalDateTime = exam.startAt

    /**
     * 考前提醒标题：「1 小时后开考」。
     *
     * 整小时写「N 小时」更顺口；提前量为 0 时不写「0 分钟后」。
     */
    fun examTitle(leadMinutes: Long): String = when {
        leadMinutes <= 0 -> "马上开考"
        leadMinutes % 60 == 0L -> "${leadMinutes / 60} 小时后开考"
        else -> "$leadMinutes 分钟后开考"
    }

    /**
     * 通知正文，如 `08:00-10:00 高等数学 · 文萃楼I404 · 座位 012`。
     *
     * ⚠️ **座位号必须写**：考试按座位号入座，找座位的几分钟决定了你能不能坐下来喘口气。
     * 上课提醒没有这个概念，这是考试独有的字段。
     */
    fun examText(exam: ExamScheduleEntity): String {
        val span = "${hm(exam.beginTime)}-${hm(exam.endTime)}"
        val seat = exam.seatId.trim().let { if (it.isBlank()) "" else "座位 $it" }
        return listOf(span, exam.name, exam.classroom, seat)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    /**
     * 考试提醒的去重键：`exam:{课程号或课名}:{日期}:{窗口}`。
     *
     * ⚠️ **刻意不带具体时刻**：同一天同一门课正常只有一场考试，日期 + 窗口已足够区分；
     * 带上时刻反而会在服务端微调考试时间后，让「已提醒过」的记录作废 → 重复提醒一次。
     */
    fun examKey(exam: ExamScheduleEntity, window: String): String {
        val id = exam.courseId.trim().ifBlank { exam.name }
        return "exam:$id:${exam.date}:$window"
    }

    /**
     * 考前 N 分钟的窗口标识。
     *
     * ⚠️ 提前量进键（与上课 / 座位提醒同一口径）：用户改了提前量，应按新策略再提醒一次。
     */
    fun examLeadWindow(leadMinutes: Long): String = "lead$leadMinutes"

    // ------------------------------------------------------------ 教学安排调整（补课）提醒

    /**
     * 补课提醒的钟点 —— **补课日的前一天 20:00**。
     *
     * ⚠️ 写死钟点（而不是「提前 N 小时」）是刻意的：20:00 是睡前，用户看到能安排
     * 第二天（带哪本书、要不要早起）。固定钟点还顺手省掉了「提前量进键」那套复杂度
     * —— 键里只剩一个日期，学校改安排也不会被误判成「新提醒」而重复打扰。
     */
    private val ADJUSTMENT_AT: LocalTime = LocalTime.of(20, 0)

    /**
     * 补课日提醒：**只报 `MakeUp`，不报放假**（理由见 [ReminderKind.ADJUSTMENT]）。
     *
     * ⚠️ **过时不补发**（与考试同向、与座位签到反向）：补课当天才弹「按周四上课」
     * 是句废话 —— 人都该出门了。用户需要的是**前一天晚上**知道这件事。
     */
    private fun adjustmentReminders(
        adjustments: List<TeachingAdjustmentEntry>,
        now: LocalDateTime,
        until: LocalDateTime,
        sentKeys: Set<String>,
    ): List<Reminder> {
        val out = mutableListOf<Reminder>()

        adjustments.asSequence()
            .filter { it.plan is DayPlan.MakeUp }
            .forEach { entry ->
                val target = (entry.plan as DayPlan.MakeUp).targetWeekday
                val at = entry.date.minusDays(1).atTime(ADJUSTMENT_AT)
                if (at.isBefore(now) || at.isAfter(until)) return@forEach

                val key = adjustmentKey(entry)
                if (key in sentKeys) return@forEach

                val title = adjustmentTitleAtFireTime(entry.date, target, now) ?: return@forEach
                out += Reminder(
                    key = key,
                    kind = ReminderKind.ADJUSTMENT,
                    at = at,
                    title = title,
                    text = adjustmentText(entry.date, target),
                    route = PageShowOnNav.Schedule.toPageData().value,
                )
            }
        return out
    }

    /**
     * 补课提醒的标题：**按到点时的实际日期重算**（排期时是「明天」，到点可能已跨天）。
     *
     * ⚠️ **补课日已过返回 null = 不发**。与上课提醒同一口径：标题是**相对时间**，
     * 而 WorkManager 会被 Doze / 省电策略大幅延后（实机迟到过 2 小时 23 分钟）——
     * 沿用排期时写死的「明天」就会说假话。
     *
     * ⚠️ 星期几一律写「周X」（`按周**四**课表上课`），与 [adjustmentText] 的正文、
     * 课表列头角标 `CourseScheduleAdjustmentLogic.badgeText` 同一口径。
     * 少写一个「周」字单看无害，但三处并列时就成了两种说法。
     */
    fun adjustmentTitleAtFireTime(
        date: LocalDate,
        targetWeekday: Int,
        now: LocalDateTime,
    ): String? {
        val wd = weekdayCn(targetWeekday)
        return when {
            now.toLocalDate().isBefore(date) -> "明天按周${wd}课表上课"
            now.toLocalDate() == date -> "今天按周${wd}课表上课"
            else -> null
        }
    }

    /**
     * 通知正文，如 `10/10（周六）按周四课表上课`。
     *
     * ⚠️ 写**绝对日期**（与上课 / DDL / 考试同一原则）：通知可能晚到，
     * 绝对日期永远是对的，相对时间会变成假话。
     */
    fun adjustmentText(date: LocalDate, targetWeekday: Int): String =
        "${dateLabelCn(date)}按周${weekdayCn(targetWeekday)}课表上课"

    /**
     * 补课提醒的去重键：`adjustment:{日期}`。
     *
     * ⚠️ **刻意不带时刻、不带课名**：
     * - 不带时刻 —— 学校微调补课日（罕见但可能）时键不变 ⇒ 不会重复打扰（考试键同款教训）
     * - 不带课名/备注 —— 键里一旦有自由文本，`dateOfKey` 那种「按格式认」的解析就没法做
     *   （备注带一个半角冒号就会整段错位，把该发的提醒静默丢掉）
     */
    fun adjustmentKey(entry: TeachingAdjustmentEntry): String = "adjustment:${entry.date}"

    /**
     * 从去重键里取回**日期段**。
     *
     * ⚠️ **不能按位置取**（不能用 `split(":")[2]`）：键的第二段是「课程号或课名」，
     * 课名是自由文本，万一带一个半角冒号就会整段错位 → 二次校验判成「那天没课」
     * → **该发的提醒被静默丢掉**（比多发一条糟得多）。
     *
     * 改为**按格式认**：键里唯一能解析成 `LocalDate` 的就是日期段。
     * 取不到（格式对不上）返回 null，调用方据此放弃这次提醒 —— 宁可漏一次，也不要发错。
     */
    fun dateOfKey(key: String): LocalDate? =
        key.split(':')
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .firstOrNull()

    /** 考试日期段（等价于 [dateOfKey]，保留旧名给调用方）。 */
    fun examDateOfKey(key: String): LocalDate? = dateOfKey(key)

    /**
     * 从座位去重键里取回**签到截止时刻**（`seat:018:2026-09-23T11:03:15:15`）。
     *
     * ⚠️ **不能 `split(':')` 后按位置取**：截止时刻是 `LocalDateTime`，
     * **自身就含冒号**，一拆就碎。做法是先切掉末段的提前量，
     * 再取「第二个冒号之后」的整段。
     *
     * 认格式：第一段不是 `seat` 就返回 null（不是座位键就别硬猜）。
     */
    fun seatDeadlineOfKey(key: String): LocalDateTime? {
        if (key.substringBefore(':') != "seat") return null
        val withoutLead = key.substringBeforeLast(':')            // seat:018:2026-09-23T11:03:15
        val deadline = withoutLead.substringAfter(':').substringAfter(':')
        return runCatching { LocalDateTime.parse(deadline) }.getOrNull()
    }

    /**
     * 从去重键**末段**取提前量（上课 / 座位提醒的键都以裸数字结尾）。
     *
     * 取末段是安全的：提前量恒定在最后，前面无论含多少冒号都不影响。
     * （考试键末段是 `lead15` 这类非数字，会返回 null —— 它本来也用不到提前量。）
     */
    fun trailingLeadMinutes(key: String): Long? =
        key.substringAfterLast(':').toLongOrNull()

    /**
     * 从去重键末段取**考试提醒的提前量**（键末段形如 `lead15`；考前一天的窗口是 `day1`）。
     */
    fun trailingExamLeadMinutes(key: String): Long? =
        key.substringAfterLast(':').removePrefix("lead").toLongOrNull()

    // ------------------------------------------------ 到点时的「实际提前量」

    /** 上课时刻 = 提醒时刻 + 提前量（提前量跨零点时两者不在同一天）。 */
    fun classStartAt(at: LocalDateTime, leadMinutes: Long): LocalDateTime =
        at.plusMinutes(leadMinutes)

    /** [target] 距 [now] 还有几分钟（向下取整，已过则为负）。 */
    fun minutesUntil(target: LocalDateTime, now: LocalDateTime): Long =
        ChronoUnit.MINUTES.between(now, target)

    /**
     * 上课提醒**到点时的实际提前量**；**课已开始返回 null**（= 不该再发）。
     *
     * ⚠️⚠️ 为什么必须重算 + 必须拦：排期时写死的「10 分钟后上课」是**相对时间**，
     * 而 WorkManager 会被 Doze / 省电策略大幅延后 —— **实机迟到 2 小时 23 分钟**，
     * 于是**下课之后**才弹出「10 分钟后上课」（2026-09-29 用户截图实证）。
     * 这正是「正文写绝对时间」那条原则**没覆盖到的地方：正文是绝对的，标题不是**。
     * 下课后再弹，比不弹更糟。
     */
    fun classLeadAtFireTime(at: LocalDateTime, leadMinutes: Long, now: LocalDateTime): Long? {
        val startAt = classStartAt(at, leadMinutes)
        return if (now.isBefore(startAt)) minutesUntil(startAt, now) else null
    }

    /**
     * 座位签到提醒**到点时的实际提前量**。
     *
     * ⚠️ **已过截止也要返回负数，而不是 null** —— 座位签到是「**发现得晚也必须立刻补发**」
     * （与考试**方向相反**）：错过签到会记一次违约，累计 5 次暂停 7 天，
     * 用户至少要知道这件事。这里只负责**把标题换成实话**（`seatTitle` 对 ≤0 写「签到即将截止」）。
     */
    fun seatLeadAtFireTime(deadline: LocalDateTime, now: LocalDateTime): Long =
        minutesUntil(deadline, now)

    /**
     * 考试提醒**到点时的实际提前量**；**已开考返回 null**（= 不该再发）。
     *
     * ⚠️ 与座位相反：考试**过了时刻就不补发** —— 迟到 20 分钟才说「马上开考」
     * 只会让人更慌，而且已经来不及。
     */
    fun examLeadAtFireTime(startAt: LocalDateTime, now: LocalDateTime): Long? =
        if (now.isBefore(startAt)) minutesUntil(startAt, now) else null

    // ------------------------------------------------------------ 去重键

    /**
     * 上课提醒的去重键：`course:{课程号或课名}:{日期}:{起始节次}:{提前量}`。
     *
     * ⚠️ 提前量进键是**有意的**：用户把「提前 10 分钟」改成「提前 20 分钟」后，
     * 应该按新策略再提醒一次，而不是被旧记录判成「已发过」。
     */
    fun courseKey(course: CourseScheduleEntity, date: LocalDate, leadMinutes: Long): String {
        val id = course.number.ifBlank { course.name }
        return "course:$id:$date:${course.start_section}:$leadMinutes"
    }

    /** DDL 提醒的去重键：`ddl:{uid}:{窗口}`。 */
    fun ddlKey(ddl: DDLScheduleEntity, window: String): String = "ddl:${ddl.uid}:$window"

    /**
     * 座位签到提醒的去重键：`seat:{座位号}:{签到截止时刻}:{提前量}`。
     *
     * ⚠️ **带座位号与截止时刻**（不只是座位号）：同一座位今天预约、明天又预约，
     * 这是两条独立提醒；否则第二天会被判成「已发过」而静默丢失。
     * 提前量同样进键 —— 用户改了提前量应按新策略再提醒一次（与上课提醒一致）。
     */
    fun seatKey(input: SeatReminderInput, leadMinutes: Long): String =
        "seat:${input.seatNo.trim()}:${input.signInDeadline}:$leadMinutes"

    /**
     * 从去重键里取座位号。
     *
     * 键形如 `seat:018:2026-09-23T11:03:15`（第 4 段是提前量）——
     * 截止时刻自身含 `:`，所以**只用前两段**，不做完整拆分。
     */
    fun seatNoOfKey(key: String): String? =
        key.split(":").getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------ 周次

    /**
     * 当前是第几教学周；`firstDay` 未知或早于开学日时返回 -1（= 无法判断）。
     *
     * 与组件侧 `WidgetLogic.weekOf` 同一套规则：**判不出来就不按周次过滤**，
     * 宁可多提醒也不漏。
     */
    fun weekOf(firstDay: LocalDate?, date: LocalDate): Int {
        if (firstDay == null) return -1
        val days = ChronoUnit.DAYS.between(firstDay, date)
        if (days < 0) return -1
        return (days / 7).toInt() + 1
    }

    /** 周次串（`[1][2][3]`）是否包含第 [week] 周。 */
    fun weeksContains(weeks: String, week: Int): Boolean = weeks.contains("[$week]")
}
