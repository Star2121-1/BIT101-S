package cn.bit101.android.features.widget

import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.config.setting.base.courseTimeText
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.config.setting.base.sectionEnd
import cn.bit101.android.config.setting.base.sectionStart
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.ddl.DdlSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 小组件的数据模型。
 *
 * 刻意与 Room 实体解耦：小组件只关心「要显示什么」，不关心数据库长什么样。
 * 这样聚合逻辑可以完全用纯函数表达并单测（RemoteViews 无法在 JVM 单测里跑）。
 */
data class WidgetData(
    val pages: List<WidgetPage>,
)

/**
 * 小组件的一页。用户点顶部页签在各页之间切换。
 *
 * [items] 是**该页的全部条目**，交给可滚动列表渲染 —— 不再由组件高度裁剪行数，
 * 用户自己上下滑就能看到全天行程（2026-09-21 依反馈改造）。
 */
data class WidgetPage(
    val kind: PageKind,
    val title: String,
    val items: List<WidgetLine> = emptyList(),
    /** 空态文案（无内容时显示） */
    val emptyText: String = "暂无内容",
    /**
     * 未登录时的引导文案；**非 null 表示该页应显示登录引导而不是内容**。
     *
     * 为什么整页替换而不是「加个按钮但保留旧数据」：本地库里的课程/DDL 可能是
     * 几天前同步的，会话已失效时继续展示会让人以为数据是新的（2026-09-21 用户确认选此方案）。
     */
    val loginPrompt: String? = null,
    /**
     * 列表下方的一行说明（课程页是「今天 · 9月21日 周一 · 第3周」）。
     *
     * ⚠️ 必须能看出**显示的是哪一天** —— 晚上看过全部课程后组件会自动切到明天，
     * 不标出来会让人以为日期错了。
     */
    val footer: String? = null,
    /**
     * 初次渲染时列表滚动到第几条。课程页用来直接定位到「现在」所处的时段。
     */
    val scrollTo: Int = 0,

    /**
     * 座位页专用：**可立即下单的目标任务 id**。
     *
     * 由座位侧随快照推来（见 `SeatWidgetSnapshot.Snapshot`），非空时底部按钮
     * 从「立即预约」变成「一键预约」。组件自己不理解座位状态，只透传。
     */
    val quickReserveTaskId: String? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 页面底部的动作键：显示什么文字、点了做什么。
 *
 * [route] 是 App 侧的路由：页面路由取 `PageShowOnNav.toPageData().value`（如 `"seat"`），
 * 登录页取 `AppRoutes.LOGIN`（`"login"`）。
 *
 * [quickReserveTaskId] 非 null 时**优先于 [route]**：点按钮直接在后台对
 * 该任务的目标座位下单，**不打开 App**（2026-09-21 用户选定）。
 */
data class WidgetAction(
    val label: String,
    val route: String,
    val quickReserveTaskId: String? = null,
) {
    val isQuickReserve: Boolean get() = quickReserveTaskId != null
}

enum class PageKind(val label: String) {
    /** 当日课程（含空闲时段） */
    COURSE("课程"),

    /** 近期 DDL */
    DDL("DDL"),

    /** 座位预约 */
    SEAT("座位"),

    /**
     * 延河课堂的课程动态（作业 / 资料 / 公告）。
     *
     * ⚠️ **追加在最后**而不是插在中间：页号存在 `WidgetPageStore` 里，
     * 插队会让所有既有用户的组件跳到别的页。
     */
    ACTIVITY("动态"),
}

/**
 * 列表里的一行。
 *
 * [lead] 是左侧的短标记（节次 / 序号 / 状态），[main] 是主体，
 * [trail] 是右侧的次要信息（教室 / 剩余时间）。
 */
data class WidgetLine(
    val lead: String,
    val main: String,
    val trail: String = "",
    /** 是否标记为「紧急」（UI 层用它决定是否高亮） */
    val urgent: Boolean = false,
    /** 上课时间 `09:55-12:20` */
    val time: String = "",
    /** 是不是「正在上课 / 马上要上」的那一节 —— 决定要不要重点标识 */
    val highlight: ClassState? = null,
    /** 弱化显示（空闲时段用次要色，不与课程抢视觉） */
    val muted: Boolean = false,
    /** 点这条打开 App 的哪个路由（如课表页 `"schedule"`）；null = 不可点 */
    val openRoute: String? = null,

    /**
     * 是不是**栏目标题行**（如「未完成 · 3」「已完成 · 2」）。
     *
     * 标题行只作为分区标识：次要色、没有第二行、**不可点**。
     */
    val header: Boolean = false,

    /**
     * 点这条要**切换完成状态**的 DDL uid；null = 这条不切换。
     *
     * 组件里给 DDL 行的点击语义就是「勾上 / 取消勾选」—— 与 App 里的复选框一致，
     * 不用为此打开 App（见 Provider 的 `ACTION_ITEM_TAP`）。
     */
    val toggleDdlUid: String? = null,
)

/**
 * 一节课相对于「现在」的状态。
 *
 * 用户扫一眼就想知道「现在在上什么」或「下一节是什么」，
 * 所以把这两个状态显式建模出来，而不是只按节次排个序。
 */
enum class ClassState {
    /** 正在上 */
    ONGOING,

    /** 今天还没上，且是最近的一节 */
    UPCOMING,
}

/** 一天时间轴上的区块类型。 */
enum class BlockKind {
    /** 一节课 */
    COURSE,

    /** 一段连续的空闲（已自动合并相邻的空节次） */
    FREE,
}

/**
 * 一天时间轴上的一个区块：要么是一节课，要么是一段合并后的空闲。
 *
 * 把两者统一建模，是因为用户要的是「一天的完整行程」而不只是课程清单 ——
 * 空档期有多长、能不能用来吃饭/自习，是和「几点上课」同等重要的信息。
 */
data class DayBlock(
    val kind: BlockKind,
    val startSection: Int,
    val endSection: Int,
    /** [BlockKind.COURSE] 时非空 */
    val course: CourseScheduleEntity? = null,
)

/**
 * [WidgetLogic.pickDay] 的结果：组件此刻应该显示哪一天。
 */
data class DayChoice(
    val date: LocalDate,
    val isTomorrow: Boolean,
    val blocks: List<DayBlock>,
)

/**
 * 小组件的全部纯逻辑聚合。
 *
 * 输入全部来自 Room（[CourseScheduleEntity] / [DDLScheduleEntity]）与座位模块快照，
 * 输出 [WidgetData]，**不读取系统时间之外的外部状态**（[now] 显式传入，便于测试）。
 */
object WidgetLogic {

    /** DDL 页最多往前看多少天内的到期项 */
    /** @deprecated DDL 不再限制时间范围（用户要求显示全部）；保留常量只为兼容旧引用。 */
    @Deprecated("DDL 不再限时")
    const val DDL_HORIZON_DAYS = 14L

    /** 剩余时间少于该阈值标记为 urgent（UI 层高亮） */
    const val URGENT_HOURS = 24L

    /**
     * 单页条目上限。
     *
     * 列表可滚动，理论上不用限制；但异常数据（比如周次解析出错导致几百条）
     * 会让 `RemoteViewsFactory` 反复构造视图拖慢渲染，给个宽松的上限兜底。
     */
    const val MAX_ITEMS = 60

    /** 空闲区块的行首文案 */
    const val FREE_LABEL = "空闲"

    /**
     * 兜底作息表。
     *
     * ⚠️ 正常情况下**不用它** —— 组件读的是课表设置里的时间表
     * （`CourseScheduleSettings.timeTable`，用户可在设置里自行编辑），
     * 见 [WidgetRepository.load]。这里只是设置读取失败时的保底，
     * 内容与 `SettingDataStore` 里的默认值一致
     * （学校官方作息，党政办公室印发，自 2021-08-23 起实行）。
     */
    val FALLBACK_TIME_TABLE: TimeTable =
        cn.bit101.android.config.setting.base.FALLBACK_TIME_TABLE

    /** 连续节次对应的时间段文本，如 `09:55-12:20`（实现见 config 的 courseTimeText）。 */
    fun courseTimeText(table: TimeTable, startSection: Int, endSection: Int): String =
        cn.bit101.android.config.setting.base.courseTimeText(table, startSection, endSection)

    // ------------------------------------------------------------ 一天的时间轴

    /**
     * 把某一天的课程铺成**完整的时间轴**：课与课之间的空档自动合并成「空闲」区块。
     *
     * ## 为什么要这么做
     *
     * 用户要的是「一天的完整行程」，不只是课程清单 ——
     * 空档有多长、能不能吃饭/自习，和「几点上课」一样重要。
     *
     * ## 合并规则
     *
     * - 相邻的空闲小节合并成**一个**区块（3 节空档显示成「空闲 09:55-12:20」，
     *   而不是三行「空闲」刷屏）
     * - 时间表之外还有课（异常数据）时，把扫描范围扩到最后一节，避免漏课
     * - 整天没课 → 给出一整段空闲，这样空态也能表达「今天全天没课」
     *
     * @param courses 该天的课程，无需预先排序
     */
    fun buildDayBlocks(
        courses: List<CourseScheduleEntity>,
        table: TimeTable,
    ): List<DayBlock> {
        // 节次 → 课。同一节次被多门课占用时取先出现的（异常数据，取一个就够）
        val occupied = HashMap<Int, CourseScheduleEntity>()
        courses.forEach { course ->
            val from = course.start_section.coerceAtLeast(1)
            val to = course.end_section.coerceAtLeast(from)
            (from..to).forEach { section -> occupied.putIfAbsent(section, course) }
        }

        if (occupied.isEmpty()) {
            return if (table.isEmpty()) emptyList()
            else listOf(DayBlock(BlockKind.FREE, 1, table.size))
        }

        val lastSection = maxOf(table.size, occupied.keys.maxOrNull() ?: 0)
        val blocks = ArrayList<DayBlock>()

        var section = 1
        while (section <= lastSection) {
            val course = occupied[section]
            if (course != null) {
                val end = course.end_section.coerceAtLeast(course.start_section)
                blocks.add(DayBlock(BlockKind.COURSE, course.start_section, end, course))
                section = end + 1
            } else {
                val start = section
                while (section <= lastSection && occupied[section] == null) section++
                blocks.add(DayBlock(BlockKind.FREE, start, section - 1))
            }
        }
        return blocks
    }

    /** 某一天的时间轴（含周次过滤与节次排序）。 */
    private fun blocksOf(
        courses: List<CourseScheduleEntity>,
        date: LocalDate,
        firstDay: LocalDate?,
        table: TimeTable,
    ): List<DayBlock> {
        val week = weekOf(firstDay, date)
        val weekday = date.dayOfWeek.value
        val thatDay = courses
            .filter { it.weekday == weekday }
            .filter { week <= 0 || weeksContains(it.weeks, week) }
            .sortedBy { it.start_section }
        return buildDayBlocks(thatDay, table)
    }

    /** 「现在」是否已经晚于[blocks]里最后一个区块的结束时刻。 */
    private fun isAfterAll(blocks: List<DayBlock>, now: LocalTime, table: TimeTable): Boolean {
        val last = blocks.lastOrNull() ?: return true
        val end = sectionEnd(table, last.endSection) ?: return false
        return now.isAfter(end)
    }

    /**
     * 决定组件此刻显示哪一天。
     *
     * 规则（2026-09-22 第二次收窄，依用户反馈）：
     * **只要「现在」过了今天最后一个时段就切明天** —— 哪怕明天没课
     * （明天没课时显示明天的整段空闲，日期栏会写清是哪天）。
     * 今天没课不切，停在今天。
     */
    fun pickDay(
        courses: List<CourseScheduleEntity>,
        today: LocalDate,
        now: LocalTime?,
        firstDay: LocalDate?,
        table: TimeTable = FALLBACK_TIME_TABLE,
    ): DayChoice {
        val todayBlocks = blocksOf(courses, today, firstDay, table)
        val tomorrow = today.plusDays(1)

        if (now != null && isAfterAll(todayBlocks, now, table)) {
            return DayChoice(tomorrow, isTomorrow = true, blocksOf(courses, tomorrow, firstDay, table))
        }
        return DayChoice(today, isTomorrow = false, todayBlocks)
    }

    /**
     * 初次渲染时列表应滚到第几条。
     *
     * - 显示的是明天 / 时间未知 → 顶部
     * - 「现在」正落在某个区块内（含课间所在的空闲区块）→ 那一条
     * - 「现在」早于所有区块的第一节 → 第 0 条（即"展示最上面的课程"）
     * - 「现在」晚于所有区块 → 第 0 条（此时 [pickDay] 已切到明天，兜底而已）
     */
    fun scrollIndexOf(
        blocks: List<DayBlock>,
        now: LocalTime?,
        table: TimeTable = FALLBACK_TIME_TABLE,
        isTomorrow: Boolean = false,
    ): Int {
        if (now == null || isTomorrow) return 0

        blocks.forEachIndexed { i, block ->
            val start = sectionStart(table, block.startSection) ?: return@forEachIndexed
            val end = sectionEnd(table, block.endSection) ?: return@forEachIndexed
            if (!now.isBefore(start) && !now.isAfter(end)) return i
        }
        blocks.forEachIndexed { i, block ->
            val start = sectionStart(table, block.startSection) ?: return@forEachIndexed
            if (now.isBefore(start)) return i
        }
        return 0
    }

    /**
     * 底部说明行：`今天 · 9月21日 周一 · 第3周`。
     *
     * ⚠️ 前缀（今天/明天）不是装饰：晚上组件会自动切到明天，
     * 没有这个前缀，用户看到的是明天的课却以为是今天的，会直接判定"日期错了"。
     */
    fun dayFooter(today: LocalDate, date: LocalDate, week: Int): String {
        val prefix = when (ChronoUnit.DAYS.between(today, date).toInt()) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            else -> ""
        }
        val dateText = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayName(date.dayOfWeek.value)}"
        val weekText = if (week > 0) " · 第${week}周" else ""
        return listOf(prefix, dateText).filter { it.isNotBlank() }.joinToString(" · ") + weekText
    }

    /** 1=周一 … 7=周日 */
    fun weekdayName(weekday: Int): String = when (weekday) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }

    // ------------------------------------------------------------ 组装

    /**
     * 组装组件数据。
     *
     * @param courses  当前学期的全部课程（来自 `coursesDao`）
     * @param ddls     全部 DDL（来自 `ddlScheduleDao`）
     * @param seatLines 座位模块提供的行（由上层组装，见 SeatWidgetSnapshot）
     * @param today    今天
     * @param now      当前时刻
     * @param firstDay 学期第一天；为 null 表示未知，此时**不按周过滤**课程
     */
    fun build(
        courses: List<CourseScheduleEntity>,
        ddls: List<DDLScheduleEntity>,
        seat: SeatWidgetSnapshot.Snapshot = SeatWidgetSnapshot.Snapshot(),
        today: LocalDate,
        now: LocalDateTime,
        firstDay: LocalDate?,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        bit101LoggedIn: Boolean = true,
        seatLoggedIn: Boolean = true,
        activities: List<EclassActivityLogic.EclassActivity> = emptyList(),
        eclassLoggedIn: Boolean = true,
    ): WidgetData = WidgetData(
        pages = listOf(
            coursePage(
                courses = courses,
                today = today,
                now = now,
                firstDay = firstDay,
                timeTable = timeTable,
                loggedIn = bit101LoggedIn,
            ),
            ddlPage(ddls, now, loggedIn = bit101LoggedIn),
            seatPage(seat, loggedIn = seatLoggedIn),
            // ⚠️ 顺序必须与 PageKind.entries 一致（页号按索引存）
            activityPage(activities, now, loggedIn = eclassLoggedIn),
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
        kind == PageKind.ACTIVITY -> "在 App 里登录延河课堂后显示动态"
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
        // 延河课堂的登录入口在 App 的 DDL 页（那个学校图标按钮），不去登录页
        PageKind.ACTIVITY -> PageShowOnNav.Schedule.toPageData().value
        else -> AppRoutes.LOGIN
    }

    /**
     * 该页底部的动作键；不需要时返回 null。
     *
     * 优先级：**登录引导 > 一键预约 > 立即预约** —— 未登录时后两者都没有意义。
     *
     * 「一键预约」在座位页**存在可立即下单的目标**（监控/优先任务带着座位目标）时出现：
     * 点了直接在后台预约，不打开 App。语义是"别等了，现在就约"——
     * 不是"检测到空位"（监控任务发现空位会立刻自动下单，等用户点来不及）。
     */
    fun actionOf(page: WidgetPage): WidgetAction? = when {
        page.loginPrompt != null ->
            WidgetAction(label = "登录", route = loginRoute(page.kind))
        page.kind == PageKind.SEAT && page.quickReserveTaskId != null ->
            WidgetAction(
                label = "一键预约",
                route = PageShowOnNav.Seat.toPageData().value,
                quickReserveTaskId = page.quickReserveTaskId,
            )
        page.kind == PageKind.SEAT ->
            WidgetAction(
                label = "立即预约",
                route = PageShowOnNav.Seat.toPageData().value,
            )
        // DDL 行的点击语义是「勾选/取消」，所以要另给一个进 App 的入口
        page.kind == PageKind.DDL ->
            WidgetAction(
                label = "打开 DDL",
                route = PageShowOnNav.Schedule.toPageData().value,
            )
        else -> null
    }

    // ---------------------------------------------------------------- 课程页

    /**
     * 当日行程页：**课程 + 合并后的空闲时段**，可上下滚动看全天。
     *
     * ⚠️ [firstDay] 为 null（教学周未知）时**不做周次过滤** ——
     * 强行过滤会把所有课程都滤掉、组件显示「今日无课」，用户会以为是数据没同步。
     * 宁可多显示几条也不能显示错的空态。
     */
    fun coursePage(
        courses: List<CourseScheduleEntity>,
        today: LocalDate,
        now: LocalDateTime?,
        firstDay: LocalDate?,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.COURSE,
                title = PageKind.COURSE.label,
                emptyText = "今日无课",
                loginPrompt = loginPromptOf(PageKind.COURSE, loggedIn = false),
            )
        }

        val choice = pickDay(courses, today, now?.toLocalTime(), firstDay, timeTable)

        // 高亮只标「今天」：显示明天时，没有哪一节是"正在上/马上上"
        val focus = if (!choice.isTomorrow && now != null) {
            val only = choice.blocks.mapNotNull { it.course }
            focusOf(only, now.toLocalTime(), timeTable)
        } else null

        var courseNo = -1
        val items = choice.blocks.map { block ->
            if (block.kind == BlockKind.COURSE && block.course != null) {
                courseNo++
                block.course.toLine(
                    state = if (focus?.first == courseNo) focus.second else null,
                    table = timeTable,
                ).copy(openRoute = PageShowOnNav.Schedule.toPageData().value)
            } else {
                freeLine(block, timeTable)
            }
        }

        return WidgetPage(
            kind = PageKind.COURSE,
            title = PageKind.COURSE.label,
            items = items,
            emptyText = "今日无课",
            footer = dayFooter(today, choice.date, weekOf(firstDay, choice.date)),
            scrollTo = scrollIndexOf(
                blocks = choice.blocks,
                now = now?.toLocalTime(),
                table = timeTable,
                isTomorrow = choice.isTomorrow,
            ),
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

    private fun freeLine(
        block: DayBlock,
        table: TimeTable,
    ) = WidgetLine(
        lead = FREE_LABEL,
        main = "",
        time = courseTimeText(table, block.startSection, block.endSection),
        muted = true,
    )

    /** 节次区间 → 「3-5节」。单节次不显示区间，避免「5-5节」这种别扭写法。 */
    fun sectionLabel(start: Int, end: Int): String =
        if (start == end) "$start 节" else "$start-${end}节"

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
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.DDL,
                title = PageKind.DDL.label,
                emptyText = "暂无待办",
                loginPrompt = loginPromptOf(PageKind.DDL, loggedIn = false),
            )
        }

        // ⚠️ **不再限制时间范围**（2026-09-23 用户要求「显示里面所有的 DDL」）：
        // 以前只看未来 14 天，于是 16 天/41 天后的作业在组件上完全看不见 —— 用户
        // 在 App 里看得到、组件里看不到，只会以为是 bug。
        val pending = ddls.filter { !it.done }.sortedBy { it.time }
        // 已完成的按**时间倒序**：最近完成的排前面，翻旧账的往下沉
        val done = ddls.filter { it.done }.sortedByDescending { it.time }

        val items = buildList {
            if (pending.isNotEmpty()) {
                add(headerLine("未完成 · ${pending.size}"))
                addAll(pending.map { it.toLine(now) })
            }
            if (done.isNotEmpty()) {
                add(headerLine("已完成 · ${done.size}"))
                addAll(done.map { it.toLine(now) })
            }
        }

        return WidgetPage(
            kind = PageKind.DDL,
            title = PageKind.DDL.label,
            items = items.take(MAX_ITEMS),
            emptyText = "暂无待办",
        )
    }

    /** 栏目标题行（「未完成 · 3」）：不可点、无第二行。 */
    private fun headerLine(text: String): WidgetLine =
        WidgetLine(lead = "", main = text, header = true, muted = true)


    private fun DDLScheduleEntity.toLine(now: LocalDateTime): WidgetLine = WidgetLine(
        // ⚠️ 必须走 DdlSource 转成中文：这里以前直接写 group，
        // 于是组件上会出现「lexue 第三次作业」这种把技术标识当文案的情况
        lead = DdlSource.displayName(group),
        main = title,
        // 已完成的没有「剩余时间」可言 —— 写剩余时间反而误导
        trail = if (done) "" else remainText(time, now),
        // 已过期或 24 小时内到期 —— 都算紧急（已完成的不再标红）
        urgent = !done && time.isBefore(now.plusHours(URGENT_HOURS)),
        muted = done,
        // 点一下切换完成状态（在组件里直接勾，不必打开 App）
        toggleDdlUid = uid,
    )

    // ------------------------------------------------------------ 延河课堂动态

    /**
     * 动态页（延河课堂的作业 / 资料 / 公告）。
     *
     * 与 DDL 页的区别：**不筛选**、按时间倒序（最近发生的在上）——
     * 「最近课程里发生了什么」和「我还有什么要交」是两种视角。
     */
    fun activityPage(
        activities: List<EclassActivityLogic.EclassActivity>,
        now: LocalDateTime,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.ACTIVITY,
                title = PageKind.ACTIVITY.label,
                emptyText = "暂无动态",
                loginPrompt = loginPromptOf(PageKind.ACTIVITY, loggedIn = false),
            )
        }
        return WidgetPage(
            kind = PageKind.ACTIVITY,
            title = PageKind.ACTIVITY.label,
            items = activities.take(MAX_ITEMS).map { it.toLine(now) },
            emptyText = "暂无动态",
        )
    }

    /** 动态行：`作业  第二章作业        09:30`。 */
    private fun EclassActivityLogic.EclassActivity.toLine(now: LocalDateTime): WidgetLine = WidgetLine(
        lead = kind.label,
        main = title,
        trail = EclassActivityLogic.shortAgoText(time, now),
        // 点一条动态 → 打开 App（动态页在「卷」里，这与课程行的做法一致）
        openRoute = PageShowOnNav.Schedule.toPageData().value,
    )

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
     * 行由座位模块组装后随快照推来（见 [SeatWidgetSnapshot]）——
     * 「已预约待签到 / 使用中 / 暂离 / 监控中」的判定都在座位侧的
     * `SeatStatusLogic` 里，组件只负责显示，**不重复实现那套规则**。
     *
     * @param snapshot 座位侧推来的快照（含显示行、可一键预约的目标、最近一次操作结果）
     *
     * ⚠️ 未登录时**整页换成登录引导**：座位页的动作键（一键/立即预约）在未登录时
     * 没有意义，且不登录也拉不到任何预约数据。
     */
    fun seatPage(
        snapshot: SeatWidgetSnapshot.Snapshot,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.SEAT,
                title = PageKind.SEAT.label,
                emptyText = "暂无预约",
                loginPrompt = loginPromptOf(PageKind.SEAT, loggedIn = false),
            )
        }
        // 「一键预约」的结果提示放在最上面（60 秒后自动消失，见 SeatWidgetSnapshot）。
        // 不用弹窗/横幅：组件没有弹窗，列表首行是最不打扰又一定看得到的位置。
        val noticeLine = snapshot.activeNotice()?.let {
            WidgetLine(lead = "提示", main = it, muted = true)
        }
        return WidgetPage(
            kind = PageKind.SEAT,
            title = PageKind.SEAT.label,
            items = (listOfNotNull(noticeLine) + snapshot.lines).take(MAX_ITEMS),
            emptyText = "暂无预约",
            quickReserveTaskId = snapshot.quickReserveTaskId,
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
