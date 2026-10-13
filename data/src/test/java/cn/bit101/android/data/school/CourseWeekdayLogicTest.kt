package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「那天实际上按哪个星期几上课」的单测。
 *
 * ## 为什么补这一份
 *
 * [CourseWeekdayLogic.effectiveWeekday] 是**提醒**（`NotifyLogic`）与**自习推荐**
 * （`DefaultStudySpotRepository.coursesOfDay`）判断「今天有没有课 / 有哪些课」的唯一入口，
 * 但它以前**一条单测都没有**。而桌面组件那边是一份**等价的内联判断**
 * （`WidgetLogic.blocksOf`，由 5 条调休用例兜着）—— 两边各写一份时，
 * 改了一边另一边不会红（铁律 0.1b）。
 *
 * ⇒ 两条动作：① 把组件那边也改成调这个函数（两边只剩一处可改）；
 * ② 补上这份测试，把三种结果（放假 / 补课 / 照常）与周次规则都锁死。
 *
 * ## 错一次的代价
 *
 * - 放假那天算成「有课」⇒ 提醒照发、课表照显示，**人白跑一趟**
 * - 补课日按当天星期几取课 ⇒ 一条都取不到，而那天**恰恰要上课**
 */
class CourseWeekdayLogicTest {

    /** 2026-10-14 是**星期三**（真机课表页表头「周1 10/12」即为同周，可反推）。 */
    private val wed = LocalDate.of(2026, 10, 14)

    /** 2026-10-12 周一 / 2026-10-18 周日 —— 两个端点（1 与 7）。 */
    private val mon = LocalDate.of(2026, 10, 12)
    private val sun = LocalDate.of(2026, 10, 18)

    private fun planOf(vararg pairs: Pair<LocalDate, DayPlan>): (LocalDate) -> DayPlan? {
        val map = pairs.toMap()
        return { date -> map[date] }
    }

    // ---------------------------------------------------------------- 星期几

    /** 前提桩：下面几条用例的「当天星期几」全靠这个，先把它钉住。 */
    @Test
    fun `前提：这几天分别是周几`() {
        assertEquals(1, mon.dayOfWeek.value)
        assertEquals(3, wed.dayOfWeek.value)
        assertEquals(7, sun.dayOfWeek.value)
    }

    @Test
    fun `没有覆盖时就是当天自己的星期几（含 1 与 7 两个端点）`() {
        val none = planOf()
        assertEquals(1, CourseWeekdayLogic.effectiveWeekday(mon, none))
        assertEquals(3, CourseWeekdayLogic.effectiveWeekday(wed, none))
        assertEquals(7, CourseWeekdayLogic.effectiveWeekday(sun, none))
    }

    @Test
    fun `放假日返回 null —— 那天没有教学安排`() {
        assertNull(CourseWeekdayLogic.effectiveWeekday(wed, planOf(wed to DayPlan.NoClass)))
    }

    /**
     * 真事（2026-09-29 事故、`TeachingAdjustmentLogicTest` 里那条回归用例的同一件事）：
     * 2026-10-10 **周六**按**周四**课表上课。
     * ⚠️ 断言的是「4」而不是「6」—— 只断言「非空」会漏掉「认成了当天星期几」这种错。
     */
    @Test
    fun `补课日按被指定那天的星期几，不是当天`() {
        val sat = LocalDate.of(2026, 10, 10)
        assertEquals(6, sat.dayOfWeek.value)
        assertEquals(4, CourseWeekdayLogic.effectiveWeekday(sat, planOf(sat to DayPlan.MakeUp(4))))
    }

    @Test
    fun `覆盖只对那一天生效，别的日子照旧`() {
        val only = planOf(wed to DayPlan.NoClass)
        assertNull(CourseWeekdayLogic.effectiveWeekday(wed, only))
        assertEquals(4, CourseWeekdayLogic.effectiveWeekday(wed.plusDays(1), only))
        assertEquals(3, CourseWeekdayLogic.effectiveWeekday(wed.minusDays(7), only))
    }

    /**
     * ⚠️ 传进去的日期必须就是**问出去的那一天**。
     * 这一条防的是「函数内部自己去拿今天」—— 那样补课日之外的日期会被静默答错，
     * 而且外面看不出来（返回的仍是 1~7 之内的合法值）。
     */
    @Test
    fun `问的是传进来的那一天`() {
        val asked = mutableListOf<LocalDate>()
        CourseWeekdayLogic.effectiveWeekday(wed) { date ->
            asked += date
            null
        }
        assertEquals(listOf(wed), asked)
    }

    // ---------------------------------------------------------------- 周次

    @Test
    fun `weekOf 首日即第 1 周，第 7 天仍是第 1 周，第 8 天进第 2 周`() {
        val first = LocalDate.of(2026, 9, 7)
        assertEquals(1, CourseWeekdayLogic.weekOf(first, first))
        assertEquals(1, CourseWeekdayLogic.weekOf(first, first.plusDays(6)))
        assertEquals(2, CourseWeekdayLogic.weekOf(first, first.plusDays(7)))
        assertEquals(2, CourseWeekdayLogic.weekOf(first, first.plusDays(13)))
        assertEquals(3, CourseWeekdayLogic.weekOf(first, first.plusDays(14)))
    }

    /** 判不出来返回 -1 ⇒ 调用方**跳过周次过滤**（宁可多算也不能漏）。 */
    @Test
    fun `weekOf 首日未知、或早于首日，都返回 -1`() {
        assertEquals(-1, CourseWeekdayLogic.weekOf(null, wed))
        assertEquals(-1, CourseWeekdayLogic.weekOf(wed, wed.minusDays(1)))
        // 首日当天是合法的第 1 周，别把它也当成 -1
        assertEquals(1, CourseWeekdayLogic.weekOf(wed, wed))
    }

    /**
     * ⚠️ **必须整段匹配**：周次串是 `[1][2][11]` 这种带方括号的标记，
     * 用 `contains("[$week]")` 而不是 `contains("$week")` ——
     * 后者会把「第 1 周」误命中 `[11]`、把「第 2 周」误命中 `[21]`，
     * 结果是**多显示一堆本不该出现的课**（用户按它出门就白跑）。
     */
    @Test
    fun `weeksContains 整段匹配：第 1 周不能被 11 命中`() {
        assertTrue(CourseWeekdayLogic.weeksContains("[1][2][11]", 1))
        assertTrue(CourseWeekdayLogic.weeksContains("[1][2][11]", 2))
        assertTrue(CourseWeekdayLogic.weeksContains("[1][2][11]", 11))
        assertFalse(CourseWeekdayLogic.weeksContains("[1][2][11]", 3))
        assertFalse(CourseWeekdayLogic.weeksContains("[11]", 1))
        assertFalse(CourseWeekdayLogic.weeksContains("[21]", 2))
    }
}
