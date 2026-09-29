package cn.bit101.android.features.schedule.course

import cn.bit101.android.data.school.DayPlan
import java.time.LocalDate

/**
 * 一周里的一列（一天）：那天是几号、要显示哪些日程、有没有教学安排覆盖。
 */
internal data class DayColumn(
    val date: LocalDate,
    /** 该列要显示的日程（**已按调休重排**：补课日装的是被指定那天的课）。 */
    val items: List<ScheduleItem>,
    /** 当天的教学安排覆盖；`null` = 正常，按星期几的循环模板走。 */
    val plan: DayPlan?,
)

/**
 * 把「教学安排调整」应用到某一周的列上。
 *
 * ## 为什么需要它
 * 学校的课表是「**星期几 + 周次**」的循环模板，表达不了「某一天按另一个星期几上课」。
 * 调休时（实测 2026 年 **10/10 周六按周四课表上课**）循环模板会给出**错误结论**：
 * 那天显示「没课」，而实际要上课。这不是缺功能，是数据会误导人。
 *
 * ## 三条规则（都是刻意的）
 * 1. **放假**：日程**照常保留**（学校下发的也在），只是那一列整体换成**灰调**
 *    —— 见 [ScheduleItemTint.Holiday]。
 *    ⚠️ 这里曾经是「把学校下发的日程藏掉」，但那会让用户以为「这天本来就没课」，
 *    连这一周的进度都看不出来。**要区分、不要消失**（用户 2026-09-30 明确提出）。
 * 2. **补课**：该列显示被指定那天的课程与考试（用户可以据此直接看教室），
 *    叠加该日**自己的**自定义日程。
 * 3. **不做任何推断**：`planOf` 返回 null 就原样照旧。**认不出来就什么都不改**
 *    —— 一个静默错误的课表比没有这个功能糟得多。
 */
internal object CourseScheduleAdjustmentLogic {

    fun applyWeek(
        byWeekday: List<List<ScheduleItem>>,
        weekFirstDate: LocalDate,
        planOf: (LocalDate) -> DayPlan?,
    ): List<DayColumn> = (0 until 7).map { i ->
        val date = weekFirstDate.plusDays(i.toLong())
        // ⚠️ 用日期自己的星期几定位列，**不假设「第 0 列就是周一」**
        //   （课表设置里的 firstDay 由用户/教务决定，别赌它是周一）
        val own = byWeekday.getOrNull(date.dayOfWeek.value - 1).orEmpty()
        val plan = planOf(date)

        DayColumn(
            date = date,
            items = when (plan) {
                // 放假：**原样保留**（不再过滤掉学校下发的日程）——
                // 那天的日程仍旧是「那天真的有的日程」，只是渲染层会把它画成灰的
                DayPlan.NoClass -> own
                is DayPlan.MakeUp ->
                    byWeekday.getOrNull(plan.targetWeekday - 1).orEmpty()
                        .filter { it.color != ScheduleColorEnum.Custom } +
                        own.filter { it.color == ScheduleColorEnum.Custom }
                null -> own
            },
            plan = plan,
        )
    }

    /**
     * 那一列该用哪套卡片配色。
     *
     * 单独抽出来的原因：**「这天不作数 / 这天是借来的」是要被看见的信息**，
     * 不该散落在 UI 里靠 `if (plan == ...)` 临时判断（漏一处就有一列不像样）。
     */
    fun tintOf(plan: DayPlan?): ScheduleItemTint = when (plan) {
        DayPlan.NoClass -> ScheduleItemTint.Holiday
        is DayPlan.MakeUp -> ScheduleItemTint.MakeUp
        null -> ScheduleItemTint.Normal
    }

    /** 列头角标文案；`null` = 不显示。 */
    fun badgeText(plan: DayPlan?): String? = when (plan) {
        DayPlan.NoClass -> "放假"
        is DayPlan.MakeUp -> "按周" + WEEKDAY_CN[plan.targetWeekday - 1]
        null -> null
    }

    /**
     * 提示条上对某天的完整说明，如「10/10（周六）按周四课表上课」。
     *
     * ⚠️ 放假那句说的是「**不上课**」而不是「无教学安排」：放假列的课程现在是**照常显示**
     * 的（灰调），所以那天的课上不上，用户需要一个明确的答案。
     */
    fun describe(date: LocalDate, plan: DayPlan): String = when (plan) {
        DayPlan.NoClass -> "%d/%d（%s）放假，当天的课不上".format(
            date.monthValue, date.dayOfMonth, weekLabel(date)
        )
        is DayPlan.MakeUp -> "%d/%d（%s）按周%s课表上课".format(
            date.monthValue, date.dayOfMonth, weekLabel(date),
            WEEKDAY_CN[plan.targetWeekday - 1],
        )
    }

    private fun weekLabel(date: LocalDate): String = "周" + WEEKDAY_CN[date.dayOfWeek.value - 1]

    private val WEEKDAY_CN = charArrayOf('一', '二', '三', '四', '五', '六', '日')
}
