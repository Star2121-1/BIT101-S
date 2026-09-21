package cn.bit101.android.features.widget

import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 小组件的数据模型。
 *
 * 刻意与 Room 实体解耦：小组件只关心「要显示什么」，不关心数据库长什么样。
 * 这样聚合逻辑可以完全用纯函数表达并单测（Glance 的 UI 无法在 JVM 单测里跑）。
 */
data class WidgetData(
    val pages: List<WidgetPage>,
)

/**
 * 小组件的一页。用户左右滑动在各页之间切换。
 */
data class WidgetPage(
    val kind: PageKind,
    val title: String,
    /** 主行（最醒目的一条，通常是"最近的一条"） */
    val primary: WidgetLine?,
    /** 次行（后续若干条，最多 [MAX_SECONDARY] 条） */
    val secondary: List<WidgetLine> = emptyList(),
    /** 空态文案（无内容时显示） */
    val emptyText: String = "暂无内容",
) {
    val isEmpty: Boolean get() = primary == null

    companion object {
        /** 除主行外最多再显示几条 —— 组件高度有限，再多也看不全 */
        const val MAX_SECONDARY = 2
    }
}

enum class PageKind(val label: String) {
    /** 当日课程 */
    COURSE("课程"),

    /** 近期 DDL */
    DDL("DDL"),

    /** 座位预约 */
    SEAT("座位"),
}

/**
 * 组件上的一行文本。
 *
 * [lead] 是左侧的短标记（节次 / 序号 / 状态），[main] 是主体，
 * [trail] 是右侧的次要信息（教室 / 剩余时间）。
 */
data class WidgetLine(
    val lead: String,
    val main: String,
    val trail: String = "",
    /** 是否标记为「紧急」（Glance 层用它决定是否高亮） */
    val urgent: Boolean = false,
)

/**
 * 小组件的全部纯逻辑聚合。
 *
 * 输入全部来自 Room（[CourseScheduleEntity] / [DDLScheduleEntity]）与座位模块快照，
 * 输出 [WidgetData]，**不读取系统时间之外的外部状态**（[now] 显式传入，便于测试）。
 */
object WidgetLogic {

    /** DDL 页最多往前看多少天内的到期项 */
    const val DDL_HORIZON_DAYS = 14L

    /** 剩余时间少于该阈值标记为 urgent（Glance 层高亮） */
    const val URGENT_HOURS = 24L

    /**
     * 组装组件数据。
     *
     * @param courses  当前学期的全部课程（来自 `coursesDao`）
     * @param ddls     全部 DDL（来自 `ddlScheduleDao`）
     * @param seatLines 座位模块提供的行（由上层组装，见 SeatWidgetSnapshot）
     * @param today    今天
     * @param now      当前时刻
     * @param week     当前教学周（第几周）。<=0 表示未知，此时**不按周过滤**课程
     * @param weekday  星期几（1=周一 … 7=周日）
     */
    fun build(
        courses: List<CourseScheduleEntity>,
        ddls: List<DDLScheduleEntity>,
        seatLines: List<WidgetLine>,
        today: LocalDate,
        now: LocalDateTime,
        week: Int,
        weekday: Int,
    ): WidgetData = WidgetData(
        pages = listOf(
            coursePage(courses, week, weekday),
            ddlPage(ddls, now),
            seatPage(seatLines),
        )
    )

    // ---------------------------------------------------------------- 课程页

    /**
     * 当日课程页。
     *
     * ⚠️ [week] <= 0 时**不做周次过滤** —— 教学周未知（未同步学期首日）时若强行过滤，
     * 会把所有课程都滤掉、组件显示「今日无课」，用户会以为是数据没同步。
     * 宁可多显示几条也不能显示错的空态。
     */
    fun coursePage(
        courses: List<CourseScheduleEntity>,
        week: Int,
        weekday: Int,
    ): WidgetPage {
        val todays = courses
            .filter { it.weekday == weekday }
            .filter { week <= 0 || weeksContains(it.weeks, week) }
            .sortedBy { it.start_section }

        return WidgetPage(
            kind = PageKind.COURSE,
            title = PageKind.COURSE.label,
            primary = todays.firstOrNull()?.toLine(),
            secondary = todays.drop(1).take(WidgetPage.MAX_SECONDARY).map { it.toLine() },
            emptyText = "今日无课",
        )
    }

    /**
     * `weeks` 字段形如 `[1][2][3][4][5][6]`（见 `CourseScheduleEntity` 的注释）。
     *
     * 必须**按完整标记匹配**，不能用 `contains(week.toString())` ——
     * 那样第 1 周会匹配到 `[11]`、`[21]`，第 2 周会匹配到 `[12]`、`[22]`。
     */
    fun weeksContains(weeks: String, week: Int): Boolean = weeks.contains("[$week]")

    private fun CourseScheduleEntity.toLine() = WidgetLine(
        lead = sectionLabel(start_section, end_section),
        main = name,
        trail = classroom.ifBlank { campus },
    )

    /** 节次区间 → 「1-2 节」。单节次不显示区间，避免「5-5 节」这种别扭写法。 */
    fun sectionLabel(start: Int, end: Int): String =
        if (start == end) "$start 节" else "$start-${end}节"

    // ---------------------------------------------------------------- DDL 页

    /**
     * DDL 页。
     *
     * 只取「未完成」且到期时间在 `[now, now + DDL_HORIZON_DAYS]` 内的项，按到期时间升序。
     * **已过期的未完成项仍然显示**（它们才是真正要紧的），只是会被标为 urgent。
     * 已完成的一律不显示 —— 组件空间有限，只放还需要行动的事。
     */
    fun ddlPage(
        ddls: List<DDLScheduleEntity>,
        now: LocalDateTime,
        horizonDays: Long = DDL_HORIZON_DAYS,
    ): WidgetPage {
        val horizon = now.plusDays(horizonDays)
        val pending = ddls
            .filter { !it.done }
            .filter { !it.time.isAfter(horizon) }
            .sortedBy { it.time }

        return WidgetPage(
            kind = PageKind.DDL,
            title = PageKind.DDL.label,
            primary = pending.firstOrNull()?.toLine(now),
            secondary = pending.drop(1).take(WidgetPage.MAX_SECONDARY).map { it.toLine(now) },
            emptyText = "暂无待办",
        )
    }

    private fun DDLScheduleEntity.toLine(now: LocalDateTime): WidgetLine {
        val remaining = remainText(time, now)
        return WidgetLine(
            lead = group.ifBlank { "DDL" },
            main = title,
            trail = remaining,
            // 已过期或 24 小时内到期 —— 都算紧急
            urgent = time.isBefore(now.plusHours(URGENT_HOURS)),
        )
    }

    /**
     * 剩余时间文案。
     *
     * ⚠️ 已过期要说「已过期」，不能显示负数或「还剩 -3 小时」——
     * 用户看到负数是不知道该干什么的，必须明确告知已错过。
     */
    fun remainText(target: LocalDateTime, now: LocalDateTime): String {
        if (!target.isAfter(now)) return "已过期"

        val minutes = ChronoUnit.MINUTES.between(now, target)
        return when {
            minutes < 60 -> "$minutes 分钟"
            minutes < 60 * 24 -> "${minutes / 60} 小时"
            else -> "${minutes / (60 * 24)} 天"
        }
    }

    // ---------------------------------------------------------------- 座位页

    /**
     * 座位预约页。
     *
     * 行由座位模块组装后传入（见 [SeatWidgetSnapshot]）。这里只做空态兜底 ——
     * 座位模块状态由它自己的仓库持有，小组件不重复实现那套逻辑。
     */
    fun seatPage(lines: List<WidgetLine>): WidgetPage = WidgetPage(
        kind = PageKind.SEAT,
        title = PageKind.SEAT.label,
        primary = lines.firstOrNull(),
        secondary = lines.drop(1).take(WidgetPage.MAX_SECONDARY),
        emptyText = "暂无预约",
    )

    // ---------------------------------------------------------------- 其他

    /**
     * 教学周计算。
     *
     * @param firstDay 学期第一天。为 null 时返回 -1（未知），调用方应据此**跳过周次过滤**。
     */
    fun weekOf(firstDay: LocalDate?, today: LocalDate): Int {
        if (firstDay == null) return -1
        val days = ChronoUnit.DAYS.between(firstDay, today)
        if (days < 0) return -1
        return (days / 7).toInt() + 1
    }

    /** 卡片副标题用：`周三 · 第 3 周`。周次未知时只显示星期。 */
    fun dateSubtitle(today: LocalDate, week: Int): String {
        val wd = when (today.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
        return if (week <= 0) wd else "$wd · 第 $week 周"
    }

    /** 判断当前时刻是否应视为「新的一天」——用于跨零点刷新（见 WidgetUpdater）。 */
    fun dayChanged(lastRendered: LocalDate?, now: LocalDate): Boolean = lastRendered != now

    /** 一天中的时刻是否在上课时间内（用于决定组件是否值得高频刷新） */
    fun isClassHours(t: LocalTime): Boolean =
        !t.isBefore(LocalTime.of(7, 0)) && t.isBefore(LocalTime.of(22, 0))
}
