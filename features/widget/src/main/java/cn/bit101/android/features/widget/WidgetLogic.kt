package cn.bit101.android.features.widget

import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.config.setting.base.toPageData
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
    /**
     * 未登录时的引导文案；**非 null 表示该页应显示登录引导而不是内容**。
     *
     * 为什么整页替换而不是「加个按钮但保留旧数据」：本地库里的课程/DDL 可能是
     * 几天前同步的，会话已失效时继续展示会让人以为数据是新的（2026-09-21 用户确认选此方案）。
     */
    val loginPrompt: String? = null,
) {
    val isEmpty: Boolean get() = primary == null

    companion object {
        /** 除主行外最多再显示几条 —— 组件高度有限，再多也看不全 */
        const val MAX_SECONDARY = 2
    }
}

/**
 * 页面底部的动作键：显示什么文字、点了去哪。
 *
 * [route] 是 App 侧的路由：页面路由取 `PageShowOnNav.toPageData().value`（如 `"seat"`），
 * 登录页取 `AppRoutes.LOGIN`（`"login"`）。
 */
data class WidgetAction(
    val label: String,
    val route: String,
)

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
    /** 上课时间 `09:55-12:20`，课程行才有 */
    val time: String = "",
    /** 是不是「正在上课 / 马上要上」的那一节 —— 决定要不要重点标识 */
    val highlight: ClassState? = null,
)

/**
 * 一节课相对于「现在」的状态。
 *
 * 组件只有 3 行，用户扫一眼就想知道「现在在上什么」或「下一节是什么」，
 * 所以把这两个状态显式建模出来，而不是只按节次排个序。
 */
enum class ClassState {
    /** 正在上 */
    ONGOING,

    /** 今天还没上，且是最近的一节 */
    UPCOMING,
}

/**
 * 小组件的全部纯逻辑聚合。
 *
 * 输入全部来自 Room（[CourseScheduleEntity] / [DDLScheduleEntity]）与座位模块快照，
 * 输出 [WidgetData]，**不读取系统时间之外的外部状态**（[now] 显式传入，便于测试）。
 */
object WidgetLogic {

    /** DDL 页最多往前看多少天内的到期项 */
    const val DDL_HORIZON_DAYS = 14L

    /**
     * 组件高度（dp）对应的内容行数。
     *
     * 组件纵向可缩放，行数必须跟着变 —— 否则拖矮后内容会被裁掉。
     * 内容区是 `weight=1 + center_vertical`（见 `widget_root.xml`），
     * 所以溢出时**上下都会裁**，第一行（高亮的那节）也会受损，必须避免。
     *
     * 阈值由实际布局算出（两行式行，2026-09-21 起每行含「课程名 + 时间/地点」两行）：
     * - 固定的：上下内边距 10+10、页签 36
     * - 每行 ≈ 38dp（15sp 名 20dp + 12sp 元信息 16dp + 2dp 间距）+ 行间距 8dp
     *
     * 于是 3 行需 ≈ 130dp、2 行 ≈ 84dp、1 行 ≈ 38dp，阈值取整留一点余量：
     * `>= 145` 给 3 行、`>= 95` 给 2 行、更矮 1 行。
     *
     * 实测参考（2026-09-21，Pixel 6）：4×3 时上报 193dp（实际 343dp）→ 3 行；
     * 用户拖到最小 110dp → 2 行（2 行只需 84dp，放得下）。
     *
     * @param heightDp 组件当前高度；**<= 0 表示系统没给尺寸**（部分 ROM 不写 options），
     *   此时按完整行数渲染 —— 宁可多显示几行被裁，也不要无端少显示信息。
     */
    fun rowsForHeight(heightDp: Int, full: Int = 3): Int = when {
        heightDp <= 0 -> full
        heightDp >= 145 -> full
        heightDp >= 95 -> minOf(2, full)
        else -> 1
    }

    /** 剩余时间少于该阈值标记为 urgent（UI 层高亮） */
    const val URGENT_HOURS = 24L

    /**
     * 兜底作息表。
     *
     * ⚠️ 正常情况下**不用它** —— 组件读的是课表设置里的时间表
     * （`CourseScheduleSettings.timeTable`，用户可在设置里自行编辑），
     * 见 [WidgetRepository.load]。这里只是设置读取失败时的保底，
     * 内容与 `SettingDataStore` 里的默认值一致
     * （学校官方作息，党政办公室印发，自 2021-08-23 起实行）。
     */
    val FALLBACK_TIME_TABLE: TimeTable = listOf(
        TimeTableItem(LocalTime.of(8, 0), LocalTime.of(8, 45)),
        TimeTableItem(LocalTime.of(8, 50), LocalTime.of(9, 35)),
        TimeTableItem(LocalTime.of(9, 55), LocalTime.of(10, 40)),
        TimeTableItem(LocalTime.of(10, 45), LocalTime.of(11, 30)),
        TimeTableItem(LocalTime.of(11, 35), LocalTime.of(12, 20)),
        TimeTableItem(LocalTime.of(13, 20), LocalTime.of(14, 5)),
        TimeTableItem(LocalTime.of(14, 10), LocalTime.of(14, 55)),
        TimeTableItem(LocalTime.of(15, 15), LocalTime.of(16, 0)),
        TimeTableItem(LocalTime.of(16, 5), LocalTime.of(16, 50)),
        TimeTableItem(LocalTime.of(16, 55), LocalTime.of(17, 40)),
        TimeTableItem(LocalTime.of(18, 30), LocalTime.of(19, 15)),
        TimeTableItem(LocalTime.of(19, 20), LocalTime.of(20, 5)),
        TimeTableItem(LocalTime.of(20, 10), LocalTime.of(20, 55)),
    )

    /** `9:5` → `09:05`。 */
    private fun hm(t: LocalTime): String =
        "%02d:%02d".format(t.hour, t.minute)

    /** 某一小节的开始时刻；节次越界返回 null。 */
    private fun sectionStart(table: TimeTable, section: Int): LocalTime? =
        table.getOrNull(section - 1)?.startTime

    /** 某一小节的结束时刻；节次越界返回 null。 */
    private fun sectionEnd(table: TimeTable, section: Int): LocalTime? =
        table.getOrNull(section - 1)?.endTime

    /**
     * 连续节次对应的时间段文本，如 `09:55-12:20`。
     *
     * 节次越界（学校新增了节次而时间表没更新）时返回空串 ——
     * 宁可少显示信息，也不要显示一个错的时间。
     */
    fun courseTimeText(
        table: TimeTable,
        startSection: Int,
        endSection: Int,
    ): String {
        val start = sectionStart(table, startSection) ?: return ""
        val end = sectionEnd(table, endSection) ?: return ""
        return "${hm(start)}-${hm(end)}"
    }

    /**
     * 找出「现在最该被关注的那节课」。
     *
     * 1. 正在上的 → [ClassState.ONGOING]（课间不算任何一节在上，会落到第 2 条）
     * 2. 否则今天还没开始的最近一节 → [ClassState.UPCOMING]
     * 3. 今天的课都上完了 → null
     *
     * @return 课程在 [courses] 中的下标 + 状态；[courses] 必须已按节次升序。
     */
    fun focusOf(
        courses: List<CourseScheduleEntity>,
        now: LocalTime,
        table: TimeTable,
    ): Pair<Int, ClassState>? {
        courses.forEachIndexed { i, course ->
            val start = sectionStart(table, course.start_section) ?: return@forEachIndexed
            val end = sectionEnd(table, course.end_section) ?: return@forEachIndexed
            if (!now.isBefore(start) && !now.isAfter(end)) {
                return i to ClassState.ONGOING
            }
        }
        courses.forEachIndexed { i, course ->
            val start = sectionStart(table, course.start_section) ?: return@forEachIndexed
            if (now.isBefore(start)) return i to ClassState.UPCOMING
        }
        return null
    }

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
     * @param limit    每页最多显示几行。组件可被拖矮，矮时只显示 1 行
     *                 （见 `WidgetViews.rowsForHeight`），所以行数由调用方给。
     */
    fun build(
        courses: List<CourseScheduleEntity>,
        ddls: List<DDLScheduleEntity>,
        seatLines: List<WidgetLine>,
        today: LocalDate,
        now: LocalDateTime,
        week: Int,
        weekday: Int,
        limit: Int = WidgetPage.MAX_SECONDARY + 1,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        bit101LoggedIn: Boolean = true,
        seatLoggedIn: Boolean = true,
    ): WidgetData = WidgetData(
        pages = listOf(
            coursePage(
                courses, week, weekday, now.toLocalTime(), limit, timeTable,
                loggedIn = bit101LoggedIn,
            ),
            ddlPage(ddls, now, limit = limit, loggedIn = bit101LoggedIn),
            seatPage(seatLines, limit, loggedIn = seatLoggedIn),
        )
    )

    // ---------------------------------------------------------- 登录引导与动作键

    /**
     * 未登录时该页应显示的引导文案；已登录返回 null。
     *
     * 措辞区分两套登录体系 —— 用户报告「组件没数据」时，这句话能直接告诉他
     * 该去登哪一个，而不是笼统的「未登录」。
     */
    fun loginPromptOf(kind: PageKind, loggedIn: Boolean): String? = when {
        loggedIn -> null
        kind == PageKind.SEAT -> "未登录座位系统"
        else -> "未登录 BIT101"
    }

    /**
     * 未登录时点「登录」应该去哪。
     *
     * ⚠️ 两套登录体系入口不同：
     * - 课程/DDL 的数据来自 BIT101（学校会话）→ 去 App 的**登录页**
     * - 座位是 seatlib 的独立会话 → 去**座位页**（那里有自己的登录门禁）
     *   把人送到 BIT101 登录页解决不了座位登录，纯属绕路。
     */
    fun loginRoute(kind: PageKind): String = when (kind) {
        PageKind.SEAT -> PageShowOnNav.Seat.toPageData().value
        else -> AppRoutes.LOGIN
    }

    /**
     * 该页底部的动作键；不需要时返回 null。
     *
     * 优先级：**登录引导 > 立即预约** —— 未登录时给「立即预约」没有意义。
     *
     * 「立即预约」只在**有余位**时出现：它排在内容行下面，
     * 行数占满时硬塞会被裁掉半截，不如不显示（用户仍可点页签进 App）。
     */
    fun actionOf(page: WidgetPage, rowLimit: Int): WidgetAction? {
        val rowCount = listOfNotNull(page.primary).size + page.secondary.size
        return when {
            page.loginPrompt != null ->
                WidgetAction(label = "登录", route = loginRoute(page.kind))
            page.kind == PageKind.SEAT && rowCount < rowLimit ->
                WidgetAction(
                    label = "立即预约",
                    route = PageShowOnNav.Seat.toPageData().value,
                )
            else -> null
        }
    }

    // ---------------------------------------------------------------- 课程页

    /**
     * 当日课程页。
     *
     * ⚠️ [week] <= 0 时**不做周次过滤** —— 教学周未知（未同步学期首日）时若强行过滤，
     * 会把所有课程都滤掉、组件显示「今日无课」，用户会以为是数据没同步。
     * 宁可多显示几条也不能显示错的空态。
     *
     * ## 显示窗口从哪开始（2026-09-21 依真机反馈修正）
     *
     * **默认显示当天全部课程**（放得下就全放）—— 用户要看的是「今天有什么课」，
     * 高亮标记告诉他现在上到哪了。
     *
     * 之前版本「从当前那节开始往后取」，结果下午看组件只剩最后一节课，
     * 前面上过的全被切掉 —— 用户明确反馈「我希望至少显示当天的几门课程」。
     *
     * 只有当天的课**多到放不下**时才开窗口，且**以焦点为中心**（不是从焦点开始），
     * 让用户既看得到刚上完的、也看得到接下来的：
     * - 今天的课都上完了（无焦点）→ 取最后几条，回看今天上了什么
     * - 时间未知（`now == null`，仅测试与降级路径）→ 从头显示
     */
    fun coursePage(
        courses: List<CourseScheduleEntity>,
        week: Int,
        weekday: Int,
        now: LocalTime? = null,
        limit: Int = WidgetPage.MAX_SECONDARY + 1,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        loggedIn: Boolean = true,
    ): WidgetPage {
        // 未登录 → 整页换成登录引导（本地数据可能是几天前的，继续展示会误导）
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.COURSE,
                title = PageKind.COURSE.label,
                primary = null,
                emptyText = "今日无课",
                loginPrompt = loginPromptOf(PageKind.COURSE, loggedIn = false),
            )
        }

        val todays = courses
            .filter { it.weekday == weekday }
            .filter { week <= 0 || weeksContains(it.weeks, week) }
            .sortedBy { it.start_section }

        val focus = now?.let { focusOf(todays, it, timeTable) }
        val from = when {
            // 放得下 → 全放，这是用户最想要的形态
            todays.size <= limit -> 0
            // 放不下 → 以焦点为中心；无焦点（都上完了）取末尾，时间未知取开头
            focus != null ->
                (focus.first - (limit - 1) / 2).coerceIn(0, (todays.size - limit).coerceAtLeast(0))
            now == null -> 0
            else -> (todays.size - limit).coerceAtLeast(0)
        }
        val lines = todays.drop(from).take(limit).mapIndexed { i, course ->
            course.toLine(
                state = if (focus != null && from + i == focus.first) focus.second else null,
                table = timeTable,
            )
        }

        return WidgetPage(
            kind = PageKind.COURSE,
            title = PageKind.COURSE.label,
            primary = lines.firstOrNull(),
            secondary = lines.drop(1),
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

    private fun CourseScheduleEntity.toLine(
        state: ClassState? = null,
        table: TimeTable = FALLBACK_TIME_TABLE,
    ) = WidgetLine(
        lead = sectionLabel(start_section, end_section),
        main = name,
        trail = classroom.ifBlank { campus },
        time = courseTimeText(table, start_section, end_section),
        highlight = state,
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
        limit: Int = WidgetPage.MAX_SECONDARY + 1,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.DDL,
                title = PageKind.DDL.label,
                primary = null,
                emptyText = "暂无待办",
                loginPrompt = loginPromptOf(PageKind.DDL, loggedIn = false),
            )
        }

        val horizon = now.plusDays(horizonDays)
        val pending = ddls
            .filter { !it.done }
            .filter { !it.time.isAfter(horizon) }
            .sortedBy { it.time }

        return WidgetPage(
            kind = PageKind.DDL,
            title = PageKind.DDL.label,
            primary = pending.firstOrNull()?.toLine(now),
            secondary = pending.drop(1).take(limit - 1).map { it.toLine(now) },
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
     *
     * ⚠️ 未登录时**整页换成登录引导**：座位页的动作键（立即预约）在未登录时
     * 没有意义，且不登录也拉不到任何预约数据。
     */
    fun seatPage(
        lines: List<WidgetLine>,
        limit: Int = WidgetPage.MAX_SECONDARY + 1,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.SEAT,
                title = PageKind.SEAT.label,
                primary = null,
                emptyText = "暂无预约",
                loginPrompt = loginPromptOf(PageKind.SEAT, loggedIn = false),
            )
        }
        return WidgetPage(
            kind = PageKind.SEAT,
            title = PageKind.SEAT.label,
            primary = lines.firstOrNull(),
            secondary = lines.drop(1).take(limit - 1),
            emptyText = "暂无预约",
        )
    }

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

}
