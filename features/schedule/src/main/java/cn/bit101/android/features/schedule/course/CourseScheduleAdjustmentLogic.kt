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
 * 1. **放假**：隐藏**学校下发的**日程（课程 / 考试），但**保留用户自己加的日程**
 *    —— 我们只该抑制学校安排，不该动用户自己的东西。
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
                DayPlan.NoClass -> own.filter { it.color == ScheduleColorEnum.Custom }
                is DayPlan.MakeUp ->
                    byWeekday.getOrNull(plan.targetWeekday - 1).orEmpty()
                        .filter { it.color != ScheduleColorEnum.Custom } +
                        own.filter { it.color == ScheduleColorEnum.Custom }
                null -> own
            },
            plan = plan,
        )
    }

    /** 列头角标文案；`null` = 不显示。 */
    fun badgeText(plan: DayPlan?): String? = when (plan) {
        DayPlan.NoClass -> "放假"
        is DayPlan.MakeUp -> "按周" + WEEKDAY_CN[plan.targetWeekday - 1]
        null -> null
    }

    /** 提示条上对某天的完整说明，如「10/10（周六）按周四课表上课」。 */
    fun describe(date: LocalDate, plan: DayPlan): String = when (plan) {
        DayPlan.NoClass -> "%d/%d（%s）放假，无教学安排".format(
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
