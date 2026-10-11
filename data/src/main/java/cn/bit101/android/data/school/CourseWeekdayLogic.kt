package cn.bit101.android.data.school

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「那一天**实际上按哪个星期几上课**」—— **全 App 只有这一份**。
 *
 * 凡是「按星期几取课」的地方都要过这一层（放假 / 补课），否则：
 *
 * - 放假那天按原课表算出「有课」⇒ **错的**（课根本不上）
 * - 补课日按当天星期几取课 ⇒ 一条都取不到，而那天恰恰**要上课**
 *
 * 已接入：提醒（`NotifyLogic`）、自习建议（`StudySpotLogic` 的调用方）。
 * 课表页与桌面组件各有自己的一份等价判断（历史如此），收敛是另一件事。
 */
object CourseWeekdayLogic {

    /**
     * @return 有效星期几（1~7）；`null` = 那天不上课（放假）。
     */
    fun effectiveWeekday(date: LocalDate, planOf: (LocalDate) -> DayPlan?): Int? =
        when (val plan = planOf(date)) {
            is DayPlan.MakeUp -> plan.targetWeekday
            DayPlan.NoClass -> null
            null -> date.dayOfWeek.value
        }

    /**
     * 教学周（1-based）。**判不出来返回 -1** —— 调用方应据此**跳过周次过滤**，
     * 宁可多算也不能漏（与提醒侧、组件侧的原实现同一套规则）。
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
