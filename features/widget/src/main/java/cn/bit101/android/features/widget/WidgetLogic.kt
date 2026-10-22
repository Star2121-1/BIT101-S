package cn.bit101.android.features.widget

import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.courseTimeText
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.config.common.FocusKeys
import cn.bit101.android.config.common.durationText
import cn.bit101.android.config.setting.base.ScheduleTabs
import cn.bit101.android.config.setting.base.sectionEnd
import cn.bit101.android.config.setting.base.sectionStart
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.ddl.DdlSource
import cn.bit101.android.data.schedule.CourseColorLogic
import cn.bit101.android.data.school.CourseWeekdayLogic
import cn.bit101.android.data.school.DayPlan
import cn.bit101.android.data.school.ExamListLogic
import cn.bit101.android.data.school.StudySpotLogic
import cn.bit101.android.data.school.weekdayCn
import cn.bit101.android.features.common.SeatOpenRequest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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

    /**
     * 延河课堂的课程动态（作业 / 资料 / 公告）。
     *
     * ⚠️ 与 [SEAT] **换过一次位置**（2026-09-23，用户要求「DDL 与动态挨着」）：
     * 枚举顺序 = 页签顺序，所以重排后老用户的页号需要迁移 ——
     * 页号本身已改成存**页名**（`WidgetPageStore`），历史 int 按旧顺序还原，不会错页。
     */
    ACTIVITY("动态"),

    /** 座位预约 */
    SEAT("座位"),

    /**
     * 「现在去哪儿自习」。
     *
     * ⚠️ **新页只能追加在末尾** —— 枚举顺序 = 页签顺序，插在中间会让老用户的页号错位
     * （页号存的是页名，历史 int 按旧顺序还原）。
     *
     * ⚠️ 这一页**只到教学楼粒度、不给具体数字**：组件重绘不打网络，
     * 「12 间空教室」这种数字可能是几小时前的，写上去就是在骗人。
     * 数字留给 App 内那张卡（现拉现算），见 [StudySpotLogic.coarseTitle]。
     */
    STUDY("自习"),

    /**
     * 考试安排（未结束的）。
     *
     * 同样**追加在末尾**：枚举顺序 = 页签顺序，插队会让老用户页号错位。
     * 数据来自本地考试库，与 App 内考试列表同源。
     */
    EXAM("考试"),
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
     * 打开 App 后停在第几个 tab（取值见 `ScheduleTabs`）。
     * 只对课表页（`openRoute == "schedule"`）有意义。
     *
     * ⚠️ **不要留 null**：`GotoRequest.Focus(tab = null, key = null)` 会被
     * `takeIf` 判成「没有附加信息」而丢掉，`TabPager` 收到 `requestedPage = null`
     * 就不切页 —— App 已经停在课表页的别的 tab 时，点条目会「毫无反应」。
     */
    val openTab: Int? = null,

    /**
     * 课程 12 色板下标（0..11）；非课程行 = -1（DDL / 空闲 / 标题行没有课名可哈希）。
     *
     * 规范 §8.2：**同一门课在课表网格 / 时间线 / 桌面组件四处同色**。
     * 色号由 `:data` 的 `CourseColorLogic.of(课名)` 算出（全仓唯一哈希实现），
     * 这里只存号不存色 —— 色值在渲染时由 `CoursePalette`（features/theme）按号给出，
     * 这样亮暗两套色板切换时组件无需重新抓数据。
     */
    val colorIndex: Int = -1,

    /**
     * 「聚焦到哪一条」的键：DDL 用 `uid`、动态用 `eclass:{id}`。
     * App 侧据此滚到那一条（见 `GotoRequest.Focus`）。null = 不定位。
     */
    val focusKey: String? = null,

    /**
     * 是不是**栏目标题行**（如「未完成 · 3」「已完成 · 2」）。
     *
     * 标题行只作为分区标识：次要色、没有第二行、**不可点**。
     */
    val header: Boolean = false,

    /**
     * 倒计时**截止时刻**（epoch 毫秒）；null = [trail] 里的数字不需要实时刷新。
     *
     * ## 为什么需要它
     *
     * 座位模块每 **10 分钟**才写一次快照（`SeatWidgetPublisher.REFRESH_INTERVAL_MS`），
     * 组件只读快照 ⇒ 快照里算好的「剩余 48 分钟」会**卡住最多 10 分钟**。
     * 而这两种场景卡 10 分钟是有实际后果的：
     * - **暂离**：保留期到点自动释放座位并记违约 1 次 ⇒ 看错可能白跑一趟或违约
     * - **签到**：窗口只有 60 分钟，10 分钟占了 1/6
     *
     * ⇒ 快照存**截止时刻**，组件**渲染时现算**剩余分钟数。
     *
     * ⚠️ 组件与座位模块**不共享**这套规则（widget 不依赖 seat），
     * 所以规则全在座位侧算好，组件侧只做「按时刻算差值 + 替换占位符」这一件机械活。
     */
    val countdownDeadlineAt: Long? = null,

    /**
     * 倒计时**到期后**显示的文案（如「保留时间已到」「签到已超时」）；null = 沿用 [trail]。
     *
     * 为什么要单独一个字段：快照是陈旧的，渲染时截止时刻可能**已经过去**
     * （组件还没等到下一次刷新）⇒ 这时算出来是负数 ⇒ 不能显示「剩余 -3 分钟」。
     */
    val countdownExpiredText: String? = null,
)

/**
 * 倒计时占位符：**剩余的总分钟数**。
 *
 * 座位侧把 [WidgetLine.trail] 写成「剩余 {m} 分钟」这样的**模板**，
 * 组件渲染时把它替换成「距离 [WidgetLine.countdownDeadlineAt] 还有多少分钟」。
 *
 * ⚠️ 只用**分钟**、不区分「小时 + 分」：
 * 组件是「扫一眼」的东西，分钟数够用；而 App 里的座位卡片是用户主动去看的地方，
 * 用「1 小时 20 分」这种友好格式。两处**表示的是同一个截止时刻**，
 * 只是详略不同 —— 别为了让两边字面一致而改 App 侧的格式。
 *
 * ⚠️ 约定写在 widget 模块（座位模块依赖 widget ⇒ 方向对），
 * 但**填充规则归座位侧管** —— widget 不该知道「暂离保留 60 分钟」这种事。
 */
const val COUNTDOWN_MINUTES_PLACEHOLDER = "{m}"

/**
 * 倒计时占位符：**时长文本**（`42 分钟` / `1 小时 5 分`）。
 *
 * 与 [COUNTDOWN_MINUTES_PLACEHOLDER] 的区别：一个是**总分钟数**，一个是**说成人话的时长**。
 * 座位侧按场景挑一个用（两者不会同时出现在一行里）：
 * - 保留期（最多 120 分钟）⇒ 用 `{m}`，显示「剩余 48 分钟」
 * - 次日预约的签到 ⇒ 用 `{d}`，显示「剩 12 小时刷卡」而不是「剩 720 分钟刷卡」
 *
 * ⚠️ 措辞格式由 `config` 的 `durationText` 提供（**共用一份**，别在 widget 里另写）。
 */
const val COUNTDOWN_DURATION_PLACEHOLDER = "{d}"

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
    /** 那天的教学安排覆盖；`null` = 正常，按星期几的循环模板走。 */
    val plan: DayPlan? = null,
)

/**
 * 小组件的全部纯逻辑聚合。
 *
 * 输入全部来自 Room（[CourseScheduleEntity] / [DDLScheduleEntity]）与座位模块快照，
 * 输出 [WidgetData]，**不读取系统时间之外的外部状态**（[now] 显式传入，便于测试）。
 */
object WidgetLogic {

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

    // ------------------------------------------------------------ 倒计时（现算）

    /**
     * 倒计时文本：把 [WidgetLine.trail] 里的占位符换成**现算**的分钟数。
     *
     * ## 为什么组件要现算
     *
     * 座位模块每 10 分钟才写一次快照 ⇒ 快照里算好的「剩余 48 分钟」会**卡住最多 10 分钟**。
     * 而暂离保留期到点会**释放座位并记违约**、签到窗口只有 60 分钟 ⇒ 这 10 分钟有实际后果。
     * ⇒ 快照存**截止时刻**，组件渲染时现算。
     *
     * ## 这里的职责边界
     *
     * widget **不依赖** seat 模块 ⇒ 它不该知道「暂离保留 60 分钟」这类规则。
     * 所以规则全在座位侧算好（座位侧把 trail 写成带占位符的模板），
     * 这里只做两件机械活：① 按时刻算差值 ② 替换占位符。
     *
     * ⚠️ 放在纯逻辑层（而不是 `WidgetViews`）是为了**能在 JVM 单测里验** ——
     * RemoteViews 绑定跑不了单测，所以「可能出错的判断」必须下沉到这里。
     *
     * @param now 当前时刻（epoch 毫秒）
     */
    fun countdownText(line: WidgetLine, now: Long): String {
        val deadline = line.countdownDeadlineAt ?: return line.trail
        // 快照是陈旧的 ⇒ 渲染时截止时刻可能已经过去 ⇒ 不能显示「剩余 -3 分钟」
        if (now >= deadline) return line.countdownExpiredText ?: line.trail
        val minutes = (deadline - now) / 60_000L
        return line.trail
            // {d} = 时长文本（`42 分钟` / `1 小时 5 分`）—— 次日预约的签到倒计时要用它
            .replace(COUNTDOWN_DURATION_PLACEHOLDER, durationText(Duration.ofMinutes(minutes)))
            // {m} = 总分钟数 —— 保留期这类「本来就用分钟说」的场合用它
            .replace(COUNTDOWN_MINUTES_PLACEHOLDER, minutes.toString())
    }

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
        plan: DayPlan?,
    ): List<DayBlock> {
        val week = weekOf(firstDay, date)

        // ⚠️ 教学安排调整（放假 / 补课）**必须在这里生效**，否则组件与 App 内课表页
        //    说的是两回事：10/10（周六）按周四课表上课，组件却显示「今天没课」——
        //    用户会拿它当出门依据，这比不显示更糟。
        //
        // ⚠️ 判断**必须走 [CourseWeekdayLogic.effectiveWeekday]** —— 提醒、自习推荐、
        //    组件、课表页只能是同一套。这里曾经自写过一份等价的 when（放假→空 /
        //    补课→被指定那天 / 否则当天星期几），而 data 层那份**一条单测都没有**：
        //    两边各写一份的判断，改了一边另一边不会红（铁律 0.1b）。
        //    ⇒ 现在两边共用同一个函数，只有一处可改。
        //    传 `{ plan }` 而不是 `planOf`：传进来的 [plan] 本来就是**这一天**的安排。
        //    返回 null（放假）= 那天的课不作数 ⇒ 交给 [buildDayBlocks] 给出「全天空闲」，
        //    上面那行说明会写清是「放假」而不是「本来就没事」。
        val weekday = CourseWeekdayLogic.effectiveWeekday(date) { plan }
        val thatDay = if (weekday == null) emptyList()
        else courses.filter { it.weekday == weekday }

        return buildDayBlocks(
            thatDay.filter { week <= 0 || weeksContains(it.weeks, week) }
                .sortedBy { it.start_section },
            table,
        )
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
        planOf: (LocalDate) -> DayPlan? = { null },
    ): DayChoice {
        val todayBlocks = blocksOf(courses, today, firstDay, table, planOf(today))
        val tomorrow = today.plusDays(1)

        if (now != null && isAfterAll(todayBlocks, now, table)) {
            return DayChoice(
                date = tomorrow,
                isTomorrow = true,
                blocks = blocksOf(courses, tomorrow, firstDay, table, planOf(tomorrow)),
                plan = planOf(tomorrow),
            )
        }
        return DayChoice(
            date = today,
            isTomorrow = false,
            blocks = todayBlocks,
            plan = planOf(today),
        )
    }

    /**
     * 教学安排调整在组件上的**一行说明**；没覆盖返回 null（那就不占位置）。
     *
     * ⚠️ 措辞与课表页、补课提醒**同向**（放假 / 按周X课表上课），但字符串是各自生成的：
     * 组件要的是**短句**（页头一行 + 页尾后缀），课表页提示条要的是带日期的完整句。
     * 同一个「周X」必须走 [weekdayCn] —— 那是全 App 唯一一份中文星期。
     */
    fun adjustmentNote(plan: DayPlan?): String? = when (plan) {
        DayPlan.NoClass -> "放假"
        is DayPlan.MakeUp -> "按周${weekdayCn(plan.targetWeekday)}课表上课"
        null -> null
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
        exams: List<ExamScheduleEntity> = emptyList(),
        seat: SeatWidgetSnapshot.Snapshot = SeatWidgetSnapshot.Snapshot(),
        today: LocalDate,
        now: LocalDateTime,
        firstDay: LocalDate?,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        bit101LoggedIn: Boolean = true,
        seatLoggedIn: Boolean = true,
        activities: List<EclassActivityLogic.EclassActivity> = emptyList(),
        eclassLoggedIn: Boolean = true,
        /**
         * 教学安排调整（放假 / 补课）。`null` = 这天正常。
         *
         * ⚠️ 由调用方给 —— [WidgetLogic] 不该知道它是从哪来的（本地缓存 / 网络 / 单测造的）。
         */
        planOf: (LocalDate) -> DayPlan? = { null },
        /**
         * 「现在去哪儿自习」的**空教室那一行**（已粗化到不含数字）；`null` = 取不到 / 已过期。
         *
         * ⚠️ 传**字符串**而不是 `Suggestion`：组件重绘不打网络，这份数据只能来自
         * 周期任务 / 手动刷新时写下的快照，而快照里存的就是一句文案。
         *
         * ⚠️ **只放需要网络的那一半**（哪栋楼有空教室）。「正在上什么」在本地就能算，
         * 由 [studyCourseLine] 每次重绘现算 —— 存快照会让它变成几小时后的一句假话。
         */
        studySpotLine: String? = null,
    ): WidgetData = WidgetData(
        pages = listOf(
            coursePage(
                courses = courses,
                today = today,
                now = now,
                firstDay = firstDay,
                timeTable = timeTable,
                loggedIn = bit101LoggedIn,
                planOf = planOf,
            ),
            // ⚠️ DDL 页**不看登录态**（见 ddlPage 的说明）：它的数据来自本地库
            ddlPage(ddls, now),
            // ⚠️ 顺序必须与 PageKind.entries 一致（页签按索引画、页号按页名存）
            activityPage(activities, now, loggedIn = eclassLoggedIn),
            seatPage(seat, loggedIn = seatLoggedIn),
            // ⚠️ 顺序必须与 PageKind.entries 一致（页签按索引画、页号按页名存）
            // ⚠️ 课程那一句**在这里现算**（本地数据，重绘不违规），不读快照
            studyPage(
                courseLine = studyCourseLine(
                    courses = courses,
                    today = today,
                    now = now,
                    firstDay = firstDay,
                    timeTable = timeTable,
                    planOf = planOf,
                ),
                freeLine = studySpotLine,
            ),
            // ⚠️ 追加在末尾，保持 PageKind.entries 与页签槽位一致
            examPage(exams = exams, now = now, loggedIn = bit101LoggedIn),
        )
    )

    /**
     * 考试页：只显示**未结束**的考试（进行中 + 未来）。
     *
     * 已结束的考试对「出门前扫一眼」没有意义，还会把下一场挤出屏幕；
     * 需要看历史的用户进 App 的考试列表（那里保留完整排序）。
     *
     * ⚠️ 与课程页同样按登录态整页替换：本地考试库可能是几天前同步的，
     * 会话失效后继续展示会让人以为考试安排还是最新的。
     */
    fun examPage(
        exams: List<ExamScheduleEntity>,
        now: LocalDateTime,
        loggedIn: Boolean = true,
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.EXAM,
                title = PageKind.EXAM.label,
                emptyText = "暂无考试安排",
                loginPrompt = loginPromptOf(PageKind.EXAM, loggedIn = false),
            )
        }

        val items = ExamListLogic.sorted(exams, now)
            .filter { ExamListLogic.status(it, now) != ExamListLogic.Status.FINISHED }
            .map { exam ->
                WidgetLine(
                    lead = examLead(exam, now),
                    main = exam.name,
                    time = "${exam.beginTime.format(HH_MM)}-${exam.endTime.format(HH_MM)}",
                    trail = exam.classroom,
                    urgent = ExamListLogic.status(exam, now) == ExamListLogic.Status.ONGOING,
                    openRoute = PageShowOnNav.Schedule.toPageData().value,
                    openTab = ScheduleTabs.COURSE,
                )
            }

        return WidgetPage(
            kind = PageKind.EXAM,
            title = PageKind.EXAM.label,
            items = items,
            emptyText = "暂无考试安排",
        )
    }

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 考试行的日期标记：今天 / 明天 / 后天 / 6/12（跨年才带年份）。 */
    private fun examLead(exam: ExamScheduleEntity, now: LocalDateTime): String {
        val dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), exam.date)
        return when (dayDiff) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            else -> if (exam.date.year == now.year) {
                "${exam.date.monthValue}/${exam.date.dayOfMonth}"
            } else {
                "${exam.date.year}/${exam.date.monthValue}/${exam.date.dayOfMonth}"
            }
        }
    }

    /**
     * 「现在去哪儿自习」页 —— 最多两行，且刻意不含数量。
     *
     * 1. **「正在上 / 马上要上 X」** —— 每次重绘用本地课表现算（见 [studyCourseLine]）
     * 2. **「XX现在有空教室」** —— 来自快照（要网络，见 [StudySpotSnapshot]）
     *
     * ⚠️ 组件重绘不打网络（这是铁律），所以第 2 行里「12 间空教室」这种数字可能是几小时前的。
     * 写上去就是在骗人 ⇒ 用 [StudySpotLogic.coarseTitle] 粗化，只说「哪栋楼有空教室」。
     * 数字留给 App 内「我」页那张卡（现拉现算）。
     *
     * @param courseLine 本地现算的那一句；`null` = 此刻没课
     * @param freeLine 快照里的那一句；`null` = 取不到 / 已经过期
     */
    fun studyPage(courseLine: String?, freeLine: String?): WidgetPage {
        val items = listOfNotNull(
            courseLine?.takeIf { it.isNotBlank() }?.let { WidgetLine(lead = "", main = it) },
            freeLine?.takeIf { it.isNotBlank() }?.let { WidgetLine(lead = "", main = it) },
        )
        return WidgetPage(
            kind = PageKind.STUDY,
            title = PageKind.STUDY.label,
            items = items,
            // 两行都没有时才显示（取不到就不给建议）
            emptyText = "暂时看不了空教室",
        )
    }

    /**
     * 「正在上 / 马上要上」那一行 —— **每次重绘用本地数据现算，不走快照**。
     *
     * ## ⚠️ 为什么不存快照
     *
     * 「正在上操作系统」是**有时效的断言**，而组件真刷数据靠 30 分钟的周期任务
     * （Doze 下还会被拖到几小时）⇒ 存快照就会出现「晚上点开显示上午在上什么」。
     * 「有没有课」本来就全在本地（课表 + 时间表 + 教学调整）⇒ 现算**既准又不打网络**。
     *
     * ⚠️ 判断本身**只有一份实现**：[StudySpotLogic.currentCourseOf]（App 内那张卡也调它）。
     * ⚠️ 用 [pickDay] 拿当天的块 ⇒ 放假 / 补课（[planOf]）与「课都上完了就切明天」
     *    这两件事都**自动跟课程页一致**，不会各算各的。
     */
    fun studyCourseLine(
        courses: List<CourseScheduleEntity>,
        today: LocalDate,
        now: LocalDateTime,
        firstDay: LocalDate?,
        timeTable: TimeTable = FALLBACK_TIME_TABLE,
        planOf: (LocalDate) -> DayPlan? = { null },
    ): String? {
        val choice = pickDay(courses, today, now.toLocalTime(), firstDay, timeTable, planOf)
        // 已经切到明天 ⇒ 没有「正在上」
        if (choice.isTomorrow) return null

        val ofDay = choice.blocks.mapNotNull { it.course }
        if (ofDay.isEmpty()) return null

        val course = StudySpotLogic.currentCourseOf(
            courses = ofDay.map {
                StudySpotLogic.CourseAt(
                    name = it.name,
                    classroom = it.classroom,
                    startSection = it.start_section,
                    endSection = it.end_section,
                )
            },
            table = timeTable,
            now = now.toLocalTime(),
        ) ?: return null

        // ⚠️ 措辞**复用** App 内那张卡的同一份实现（[StudySpotLogic.courseLine]），
        //    别在组件里另写一套「正在上 / 马上要上」——两边迟早说不到一块去。
        return StudySpotLogic.courseLine(course)
    }

    // ---------------------------------------------------------- 登录引导与动作键

    /**
     * 未登录时该页应显示的引导文案；已登录返回 null。
     *
     * 措辞区分两套登录体系 —— 用户报告「组件没数据」时，这句话能直接告诉他
     * 该去登哪一个，而不是笼统的「未登录」。
     *
     * ⚠️ **DDL 页刻意不用它**：那页的数据在本地库，与登录态无关（见 [ddlPage]）。
     * 所以这里只覆盖三页 —— 课程页（BIT101 会话）、座位页（seatlib）、
     * 动态页（延河课堂会话）。
     */
    fun loginPromptOf(kind: PageKind, loggedIn: Boolean): String? = when {
        loggedIn -> null
        kind == PageKind.SEAT -> "未登录座位系统"
        kind == PageKind.ACTIVITY -> "在 App 里登录延河课堂后显示动态"
        // 只剩课程页走这里：它的数据确实来自 BIT101 的学校会话
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
        // ⚠️ DDL 页**不再有**「打开 DDL」动作键（2026-09-23 用户要求）：
        // 条目本身点了就跳进 App 的 DDL 页，再放一个进 App 的键是重复的。
        // 于是 DDL 页现在只在未登录/一键预约时有动作键。
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
        planOf: (LocalDate) -> DayPlan? = { null },
    ): WidgetPage {
        if (!loggedIn) {
            return WidgetPage(
                kind = PageKind.COURSE,
                title = PageKind.COURSE.label,
                emptyText = "今日无课",
                loginPrompt = loginPromptOf(PageKind.COURSE, loggedIn = false),
            )
        }

        val choice = pickDay(courses, today, now?.toLocalTime(), firstDay, timeTable, planOf)

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
                ).copy(
                    openRoute = PageShowOnNav.Schedule.toPageData().value,
                    openTab = ScheduleTabs.COURSE,
                )
            } else {
                // 空闲行同样可点：整页的语义都是「进 App 看课表」，
                // 只有课程行能点会让空档处点了没反应（用户 2026-09-26 反馈）
                freeLine(block, timeTable).copy(
                    openRoute = PageShowOnNav.Schedule.toPageData().value,
                    openTab = ScheduleTabs.COURSE,
                )
            }
        }

        // 调休说明（放假 / 按周X课表）：**同时**放在列表第一行和页尾后缀。
        // 页尾本来就写「今天 · 10月10日 周六」，「按周四课表」接在后面最顺；
        // 但页尾字小，所以列表顶部再给一行 —— 两处**同一个 note**，不会各说各的。
        val note = adjustmentNote(choice.plan)
        val lined = if (note == null) items else listOf(headerLine(note)) + items

        val footer = dayFooter(today, choice.date, weekOf(firstDay, choice.date))

        return WidgetPage(
            kind = PageKind.COURSE,
            title = PageKind.COURSE.label,
            items = lined,
            emptyText = if (choice.plan == DayPlan.NoClass) "放假" else "今日无课",
            footer = if (note == null) footer else "$footer · $note",
            // 顶部多了一行，滚动位置要跟着下移一位
            scrollTo = scrollIndexOf(
                blocks = choice.blocks,
                now = now?.toLocalTime(),
                table = timeTable,
                isTomorrow = choice.isTomorrow,
            ) + if (note == null) 0 else 1,
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
        // §8.2：课名 → 12 色号，与课表网格同源（CourseColorLogic 是全仓唯一哈希实现）
        colorIndex = CourseColorLogic.of(name),
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
     * DDL 页：**未完成 / 已完成两栏**，条目可点（点一下 = 勾选/取消）。
     *
     * ## ⚠️ 为什么这页**没有**「未登录」门禁（2026-09-23 修）
     *
     * 原来这里挂了 `bit101LoggedIn`，未登录就整页换成「未登录 BIT101」。
     * 但 v1.7.0 换源之后，DDL 的数据来源是**延河课堂（eclass）+ 用户手动添加**，
     * 全部存在**本地库**里，跟 BIT101 的学校会话没关系 —— 于是出现：
     *
     * > 用户在 App 里明明看得到这两条 DDL，桌面上却只有一句「未登录 BIT101」。
     *
     * 这正是用户反馈的原话。App 内的 DDL 页读本地库、从不问登录态，
     * 组件却把它挡住，两边行为不一致。现在对齐 App：**有数据就显示**。
     *
     * （登录引导各归其位：课程页看 BIT101 会话、动态页看延河课堂会话、
     * 座位页看 seatlib 会话。）
     *
     * ⚠️ **不再限制时间范围**（2026-09-23 用户要求「显示里面所有的 DDL」）：
     * 以前只看未来 14 天，于是 16 天/41 天后的作业在组件上完全看不见。
     */
    fun ddlPage(
        ddls: List<DDLScheduleEntity>,
        now: LocalDateTime,
    ): WidgetPage {
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
        // 点一下 = **打开 App 的 DDL 页并定位到这一条**（2026-09-23 用户要求）。
        // 组件里不再提供「勾选」：一个 collection 只能挂一个点击模板，
        // 没法「整行跳转 + 别处勾选」；而 RemoteViews 也没有长按 API。
        openRoute = PageShowOnNav.Schedule.toPageData().value,
        openTab = ScheduleTabs.DDL,
        // 定位键带归属前缀：DDL 与动态列表在同一个 Pager 里都活着，键必须自描述
        focusKey = FocusKeys.ddl(uid),
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
        // 点一条动态 → 打开 App 的**动态 tab**，并定位到这一条（2026-09-23 用户要求；
        // 以前只打开 App 且停在课表 tab，用户还得自己再点一下「动态」）。
        openRoute = PageShowOnNav.Schedule.toPageData().value,
        openTab = ScheduleTabs.ACTIVITY,
        focusKey = FocusKeys.activity(id),
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
        val seatRoute = PageShowOnNav.Seat.toPageData().value
        val lines = snapshot.lines.map {
            it.copy(openRoute = seatRoute, openTab = SeatOpenRequest.TAB_TASKS)
        }
        return WidgetPage(
            kind = PageKind.SEAT,
            title = PageKind.SEAT.label,
            items = (listOfNotNull(noticeLine) + lines).take(MAX_ITEMS),
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
    fun weekOf(firstDay: LocalDate?, today: LocalDate): Int =
        // ⚠️ 实现下沉到 data 层（与提醒侧、自习建议共用同一份）
        CourseWeekdayLogic.weekOf(firstDay, today)
}
