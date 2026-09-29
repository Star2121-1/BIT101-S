package cn.bit101.android.features.schedule.course

import cn.bit101.android.data.school.DayPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「教学安排调整」落到课表列上的单测。
 *
 * 用的就是真实那次调休：**2026-10-10（周六）按周四的课表上课**，
 * 那一周 10/05（周一）~ 10/11（周日）。
 */
class CourseScheduleAdjustmentLogicTest {

    private fun item(
        weekday: Int,
        title: String,
        color: ScheduleColorEnum = ScheduleColorEnum.Course,
    ) = ScheduleItem(
        dayOfWeek = weekday,
        startSection = 0f,
        endSection = 1f,
        title = title,
        subtitle = "",
        onClick = {},
        color = color,
    )

    /** 按星期几分好的日程（index = dayOfWeek - 1）。 */
    private fun byWeekday(vararg items: ScheduleItem): List<List<ScheduleItem>> =
        (1..7).map { d -> items.filter { it.dayOfWeek == d } }

    /** 10/05（周一）那一周。 */
    private val weekOfOct5 = LocalDate.of(2026, 10, 5)

    @Test
    fun `没有覆盖时原样照旧，日期按天递增`() {
        val thursday = item(4, "操作系统")
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(thursday),
            weekFirstDate = weekOfOct5,
            planOf = { null },
        )
        assertEquals(7, columns.size)
        assertEquals(LocalDate.of(2026, 10, 5), columns.first().date)
        assertEquals(LocalDate.of(2026, 10, 11), columns.last().date)
        // 周四（index 3）有课，其它列为空
        assertEquals(listOf("操作系统"), columns[3].items.map { it.title })
        assertTrue(columns[5].items.isEmpty())
        assertNull(columns[5].plan)
    }

    /**
     * ⚠️ **核心用例**：10/10 是周六，学校要求按**周四**的课表上课。
     * 循环模板会给出「那天没课」——正是要修的错。
     */
    @Test
    fun `补课日装的是被指定那天的课表`() {
        val thursday = item(4, "操作系统")
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(thursday),
            weekFirstDate = weekOfOct5,
            planOf = { date -> if (date == LocalDate.of(2026, 10, 10)) DayPlan.MakeUp(4) else null },
        )
        val sat = columns[5]
        assertEquals(LocalDate.of(2026, 10, 10), sat.date)
        assertEquals(DayPlan.MakeUp(4), sat.plan)
        assertEquals(listOf("操作系统"), sat.items.map { it.title })
        assertEquals("按周四", CourseScheduleAdjustmentLogic.badgeText(sat.plan))
    }

    /** 补课日的**星期几仍然是周六**（列头显示周六、日期是 10/10），不能改写成「周4」。 */
    @Test
    fun `补课日自己的日期不变`() {
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(item(4, "操作系统")),
            weekFirstDate = weekOfOct5,
            planOf = { d -> if (d.dayOfMonth == 10) DayPlan.MakeUp(4) else null },
        )
        assertEquals(6, columns[5].date.dayOfWeek.value)   // 仍是周六
        assertEquals("按周四", CourseScheduleAdjustmentLogic.badgeText(columns[5].plan))
    }

    /**
     * ⚠️ **放假不藏课**（2026-09-29 用户要求：「放假的那一天的课程变一种颜色以作区分」）。
     * 藏掉会让人以为「这天本来就没课」，连这一周的进度都看不出来。
     * 要的是**看得见、但一眼知道不作数** ⇒ 数据层原样保留，由
     * [ScheduleItemTint.Holiday] 把它画成灰的。
     */
    @Test
    fun `放假保留全部日程，区分交给配色`() {
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(
                item(4, "操作系统"),                                  // 教务课程
                item(4, "高等数学", ScheduleColorEnum.Exam),           // 考试
                item(4, "买火车票", ScheduleColorEnum.Custom),         // 用户自己加的
            ),
            weekFirstDate = weekOfOct5,
            planOf = { d -> if (d.dayOfMonth == 8) DayPlan.NoClass else null },
        )
        val holiday = columns[3]          // 10/08 周四
        assertEquals(DayPlan.NoClass, holiday.plan)
        assertEquals(
            listOf("操作系统", "高等数学", "买火车票"),
            holiday.items.map { it.title },
        )
        assertEquals("放假", CourseScheduleAdjustmentLogic.badgeText(holiday.plan))
        assertEquals(
            ScheduleItemTint.Holiday,
            CourseScheduleAdjustmentLogic.tintOf(holiday.plan),
        )
    }

    /** 覆盖 → 色调的映射：放假转灰、补课转第三色、没覆盖照常。 */
    @Test
    fun `色调映射`() {
        assertEquals(ScheduleItemTint.Normal, CourseScheduleAdjustmentLogic.tintOf(null))
        assertEquals(
            ScheduleItemTint.Holiday,
            CourseScheduleAdjustmentLogic.tintOf(DayPlan.NoClass),
        )
        assertEquals(
            ScheduleItemTint.MakeUp,
            CourseScheduleAdjustmentLogic.tintOf(DayPlan.MakeUp(4)),
        )
    }

    /** 补课日：被指定那天的**课程/考试** + 该日**自己的自定义日程**。 */
    @Test
    fun `补课日同时保留该日自己的自定义日程`() {
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(
                item(4, "操作系统"),                                  // 周四的课 → 装到周六
                item(6, "值班", ScheduleColorEnum.Custom),             // 周六自己的自定义
            ),
            weekFirstDate = weekOfOct5,
            planOf = { d -> if (d.dayOfMonth == 10) DayPlan.MakeUp(4) else null },
        )
        assertEquals(listOf("操作系统", "值班"), columns[5].items.map { it.title })
    }

    /**
     * ⚠️ 列与星期几的对应**由日期算**，不赌「第 0 列就是周一」。
     * 这条用周三开头的一周把差异暴露出来。
     */
    @Test
    fun `不假设第0列是周一`() {
        val wednesday = LocalDate.of(2026, 10, 7)
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(item(3, "周三的课")),
            weekFirstDate = wednesday,
            planOf = { null },
        )
        assertEquals(wednesday, columns.first().date)
        assertEquals(listOf("周三的课"), columns.first().items.map { it.title })
    }

    @Test
    fun `认不出覆盖就什么都不改`() {
        val columns = CourseScheduleAdjustmentLogic.applyWeek(
            byWeekday = byWeekday(item(6, "周六的课")),
            weekFirstDate = weekOfOct5,
            planOf = { null },                       // 解析不出 ⇒ null
        )
        assertEquals(listOf("周六的课"), columns[5].items.map { it.title })
        assertNull(CourseScheduleAdjustmentLogic.badgeText(null))
    }

    @Test
    fun `提示条文案`() {
        assertEquals(
            "10/10（周六）按周四课表上课",
            CourseScheduleAdjustmentLogic.describe(LocalDate.of(2026, 10, 10), DayPlan.MakeUp(4)),
        )
        assertEquals(
            "10/1（周四）放假，当天的课不上",
            CourseScheduleAdjustmentLogic.describe(LocalDate.of(2026, 10, 1), DayPlan.NoClass),
        )
    }
}
