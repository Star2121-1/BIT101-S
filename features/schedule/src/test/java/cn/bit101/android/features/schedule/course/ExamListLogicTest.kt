package cn.bit101.android.features.schedule.course

import cn.bit101.android.data.database.entity.ExamScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 考试列表纯逻辑的单测。
 *
 * 这里锁的是两件在真机上很难复现的事：**列表顺序**（哪一场该在最前）
 * 与**倒计时文案**（还剩多久）—— 考试日期不是随手能造的，
 * 等真机验证一次要等好几天。
 */
class ExamListLogicTest {

    /** 2026-09-21 周一 09:00。 */
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 21, 9, 0)

    private fun exam(
        name: String = "高等数学",
        date: LocalDate = LocalDate.of(2026, 9, 24),
        begin: LocalTime = LocalTime.of(8, 0),
        end: LocalTime = LocalTime.of(10, 0),
        classroom: String = "文萃楼I404",
        seatId: String = "012",
    ) = ExamScheduleEntity(
        id = 0,
        term = "2026-2027-1",
        name = name,
        courseId = "MA10001",
        teacher = "老师",
        classroom = classroom,
        date = date,
        beginTime = begin,
        endTime = end,
        examMode = "集中考试",
        seatId = seatId,
    )

    // ------------------------------------------------------------ 状态

    @Test
    fun `开始前是未开始`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(10, 0), end = LocalTime.of(12, 0))
        assertEquals(ExamListLogic.Status.UPCOMING, ExamListLogic.status(e, now))
    }

    /** 9:00 时考 8:00-10:00 的那场 —— 正在考，不能算已结束。 */
    @Test
    fun `已开始未结束是进行中`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(8, 0), end = LocalTime.of(10, 0))
        assertEquals(ExamListLogic.Status.ONGOING, ExamListLogic.status(e, now))
    }

    @Test
    fun `结束后是已结束`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(6, 0), end = LocalTime.of(8, 0))
        assertEquals(ExamListLogic.Status.FINISHED, ExamListLogic.status(e, now))
    }

    /** 结束时刻正好等于现在 → 已经结束（不留在「进行中」）。 */
    @Test
    fun `结束时刻等于现在算已结束`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(7, 0), end = LocalTime.of(9, 0))
        assertEquals(ExamListLogic.Status.FINISHED, ExamListLogic.status(e, now))
    }

    // ------------------------------------------------------------ 排序

    /** 正在进行的最要紧 —— 用户打开列表第一眼就要看到它。 */
    @Test
    fun `进行中的排最前`() {
        val ongoing = exam(name = "正在考的", date = now.toLocalDate(), begin = LocalTime.of(8, 0), end = LocalTime.of(10, 0))
        val soon = exam(name = "下一场", date = now.toLocalDate().plusDays(1))
        val later = exam(name = "更晚的", date = now.toLocalDate().plusDays(3))

        val sorted = ExamListLogic.sorted(listOf(later, soon, ongoing), now)

        assertEquals(listOf("正在考的", "下一场", "更晚的"), sorted.map { it.name })
    }

    @Test
    fun `未开始按时间升序`() {
        val a = exam(name = "A", date = now.toLocalDate().plusDays(5))
        val b = exam(name = "B", date = now.toLocalDate().plusDays(1))
        val c = exam(name = "C", date = now.toLocalDate().plusDays(3))

        assertEquals(listOf("B", "C", "A"), ExamListLogic.sorted(listOf(a, b, c), now).map { it.name })
    }

    /** 考过的垫底，且**最近考过的靠前**（越早考的越靠后）。 */
    @Test
    fun `已结束垫底且最近考过的靠前`() {
        val old = exam(name = "上周的", date = now.toLocalDate().minusDays(7))
        val recent = exam(name = "昨天的", date = now.toLocalDate().minusDays(1))
        val upcoming = exam(name = "明天的", date = now.toLocalDate().plusDays(1))

        val sorted = ExamListLogic.sorted(listOf(old, recent, upcoming), now)

        assertEquals(listOf("明天的", "昨天的", "上周的"), sorted.map { it.name })
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<ExamScheduleEntity>(), ExamListLogic.sorted(emptyList(), now))
    }

    /** 同一天考两场：按开始时刻升序，顺序确定。 */
    @Test
    fun `同一天两场按开始时刻升序`() {
        val afternoon = exam(name = "下午场", date = now.toLocalDate().plusDays(1), begin = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        val morning = exam(name = "上午场", date = now.toLocalDate().plusDays(1), begin = LocalTime.of(8, 0), end = LocalTime.of(10, 0))

        assertEquals(
            listOf("上午场", "下午场"),
            ExamListLogic.sorted(listOf(afternoon, morning), now).map { it.name },
        )
    }

    // ------------------------------------------------------------ 日期文案

    @Test
    fun `日期文案今天明天后天`() {
        assertEquals("今天 08:00", ExamListLogic.whenText(exam(date = now.toLocalDate()), now))
        assertEquals("明天 08:00", ExamListLogic.whenText(exam(date = now.toLocalDate().plusDays(1)), now))
        assertEquals("后天 08:00", ExamListLogic.whenText(exam(date = now.toLocalDate().plusDays(2)), now))
    }

    /** 三天以上写具体日期（同年不写年份）。 */
    @Test
    fun `更远的写月日`() {
        assertEquals("9月30日 08:00", ExamListLogic.whenText(exam(date = LocalDate.of(2026, 9, 30)), now))
    }

    /** 跨年要写年份，否则「1月5日」会让人以为是今年。 */
    @Test
    fun `跨年写年份`() {
        assertEquals("2027年1月5日 08:00", ExamListLogic.whenText(exam(date = LocalDate.of(2027, 1, 5)), now))
    }

    /** 开始时间跟着走 —— 下午场不能显示成 08:00。 */
    @Test
    fun `日期文案带开始时刻`() {
        val e = exam(date = now.toLocalDate().plusDays(1), begin = LocalTime.of(14, 30))
        assertEquals("明天 14:30", ExamListLogic.whenText(e, now))
    }

    // ------------------------------------------------------------ 倒计时文案

    @Test
    fun `倒计时分钟与小时与天`() {
        // 今天 09:00 → 明天 08:00，还剩 23 小时
        assertEquals("还有 23 小时", ExamListLogic.remainingText(exam(date = now.toLocalDate().plusDays(1)), now))
        // 今天 09:40，还剩 40 分钟
        val soon = exam(date = now.toLocalDate(), begin = LocalTime.of(9, 40), end = LocalTime.of(11, 0))
        assertEquals("还有 40 分钟", ExamListLogic.remainingText(soon, now))
        // 三天后 08:00，还剩 2 天（23 小时 → 不足 3 整天，向下取整是 2 天）
        assertEquals("还有 2 天", ExamListLogic.remainingText(exam(date = now.toLocalDate().plusDays(3)), now))
    }

    /** 不足一分钟写「马上开始」，不写「还有 0 分钟」。 */
    @Test
    fun `不足一分钟写马上开始`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(9, 0), end = LocalTime.of(11, 0))
        // now 正好 09:00，开始时刻也是 09:00 → 状态是进行中；改用 09:00:30 之外的场景
        val almost = exam(date = now.toLocalDate(), begin = LocalTime.of(9, 0, 30), end = LocalTime.of(11, 0))
        assertEquals("进行中", ExamListLogic.remainingText(e, now))
        assertEquals("马上开始", ExamListLogic.remainingText(almost, now))
    }

    @Test
    fun `进行中与已结束文案`() {
        val ongoing = exam(date = now.toLocalDate(), begin = LocalTime.of(8, 0), end = LocalTime.of(10, 0))
        val finished = exam(date = now.toLocalDate(), begin = LocalTime.of(6, 0), end = LocalTime.of(8, 0))

        assertEquals("进行中", ExamListLogic.remainingText(ongoing, now))
        assertEquals("已结束", ExamListLogic.remainingText(finished, now))
    }

    /** 整 60 分钟要走「小时」分支，不能变成「还有 60 分钟」。 */
    @Test
    fun `整小时走小时分支`() {
        val e = exam(date = now.toLocalDate(), begin = LocalTime.of(10, 0), end = LocalTime.of(12, 0))
        assertEquals("还有 1 小时", ExamListLogic.remainingText(e, now))
    }
}
