package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.courseTimeText
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.config.setting.base.sectionStart
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
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
    /** 只排未来这么多天内的提醒；更远的等下次重排。 */
    val horizonDays: Long = 7,
) {
    companion object {
        /** DDL 的两个提醒窗口（键会进去重键，**改这里等于改历史记录**，谨慎）。 */
        const val DDL_WINDOW_DAY = "1d"
        const val DDL_WINDOW_HOUR = "1h"
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
