package cn.bit101.android.data.bus

import cn.bit101.android.data.bus.ShuttleLogic.DayType
import cn.bit101.android.data.bus.ShuttleLogic.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 摆渡车时刻表与「下一班」推算的单测。
 *
 * 数据是**逐条抄自站牌实拍照片**的，所以这里既要锁**数据本身**
 * （条数、递增、首末班、易错的跳跃点），也要锁**推算逻辑**。
 */
class ShuttleLogicTest {

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    // ------------------------------------------------------------ 数据本身

    /**
     * 站牌上每行都有「班次」编号：工作日 1–39、周末及节假日 1–50。
     * 这是最硬的约束 —— 少抄一班或多抄一班，这里就红。
     */
    @Test
    fun `两站两套表的条数与站牌编号一致`() {
        assertEquals(39, ShuttleLogic.timetable(Stop.LIBRARY, DayType.WORKDAY).size)
        assertEquals(39, ShuttleLogic.timetable(Stop.METRO, DayType.WORKDAY).size)
        assertEquals(50, ShuttleLogic.timetable(Stop.LIBRARY, DayType.WEEKEND_HOLIDAY).size)
        assertEquals(50, ShuttleLogic.timetable(Stop.METRO, DayType.WEEKEND_HOLIDAY).size)
    }

    @Test
    fun `时刻严格递增且无重复`() {
        Stop.entries.forEach { stop ->
            DayType.entries.forEach { day ->
                val list = ShuttleLogic.timetable(stop, day)
                val minutes = list.map { it.hour * 60 + it.minute }
                assertEquals("$stop/$day 有重复", minutes.size, minutes.toSet().size)
                assertTrue("$stop/$day 未严格递增", minutes.zipWithNext().all { (a, b) -> a < b })
            }
        }
    }

    @Test
    fun `首末班与站牌一致`() {
        // 徐特立图书馆：7:30 首班，22:30 末班（两套表相同）
        assertEquals(t(7, 30), ShuttleLogic.timetable(Stop.LIBRARY, DayType.WORKDAY).first())
        assertEquals(t(22, 30), ShuttleLogic.timetable(Stop.LIBRARY, DayType.WORKDAY).last())
        assertEquals(t(7, 30), ShuttleLogic.timetable(Stop.LIBRARY, DayType.WEEKEND_HOLIDAY).first())
        assertEquals(t(22, 30), ShuttleLogic.timetable(Stop.LIBRARY, DayType.WEEKEND_HOLIDAY).last())

        // 地铁站比图书馆整体晚 8–10 分钟：7:38 首班，22:40 末班
        assertEquals(t(7, 38), ShuttleLogic.timetable(Stop.METRO, DayType.WORKDAY).first())
        assertEquals(t(22, 40), ShuttleLogic.timetable(Stop.METRO, DayType.WORKDAY).last())
        assertEquals(t(22, 40), ShuttleLogic.timetable(Stop.METRO, DayType.WEEKEND_HOLIDAY).last())
    }

    /**
     * ⚠️ 站牌上工作日**午间与下午各有一段明显空档**（9:22→11:30、13:10→15:30）。
     * 这两处最容易被当成「漏抄」而"补"上班次 —— 这里锁死它们确实存在。
     */
    @Test
    fun `工作日午间与下午的空档确实存在`() {
        val wd = ShuttleLogic.timetable(Stop.LIBRARY, DayType.WORKDAY)
        val gap1 = wd.indexOf(t(9, 22))
        assertEquals(t(11, 30), wd[gap1 + 1])
        val gap2 = wd.indexOf(t(13, 10))
        assertEquals(t(15, 30), wd[gap2 + 1])
    }

    /** 周末及节假日把工作日的空档补上了 —— 所以 50 班 > 39 班。 */
    @Test
    fun `周末比工作日多 11 班且补上了空档`() {
        Stop.entries.forEach { stop ->
            val wd = ShuttleLogic.timetable(stop, DayType.WORKDAY)
            val we = ShuttleLogic.timetable(stop, DayType.WEEKEND_HOLIDAY)
            assertEquals(11, we.size - wd.size)
        }
        // 工作日 9:22 之后直接跳到 11:30；周末中间还有 9:38/9:50/10:10…
        val we = ShuttleLogic.timetable(Stop.LIBRARY, DayType.WEEKEND_HOLIDAY)
        val i = we.indexOf(t(9, 22))
        assertEquals(t(9, 38), we[i + 1])
    }

    // ------------------------------------------------------------ 下一班

    @Test
    fun `正好卡在班次上时就是这一班`() {
        val d = ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(7, 30))!!
        assertEquals(t(7, 30), d.at)
        assertEquals(0L, d.inMinutes)
        assertEquals(1, d.sequence)
        assertEquals(39, d.remaining)
    }

    @Test
    fun `两班之间返回下一班并算出分钟数`() {
        // 7:30 与 7:46 之间 → 下一班是 7:46
        val d = ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(7, 40))!!
        assertEquals(t(7, 46), d.at)
        assertEquals(6L, d.inMinutes)
        assertEquals(2, d.sequence)
        assertEquals(38, d.remaining)
    }

    /** 早于首班时返回首班（"还没发车"由调用方按 inMinutes 决定怎么说）。 */
    @Test
    fun `早于首班返回首班`() {
        val d = ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(6, 0))!!
        assertEquals(t(7, 30), d.at)
        assertEquals(90L, d.inMinutes)
    }

    /** 过了末班就没有下一班了 —— 返回 null，而不是绕回明天。 */
    @Test
    fun `晚于末班返回 null`() {
        assertNull(ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(22, 31)))
        assertNull(ShuttleLogic.nextDeparture(Stop.METRO, DayType.WORKDAY, t(23, 0)))
        // 末班那一刻还算「赶得上」
        assertEquals(t(22, 30), ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(22, 30))!!.at)
    }

    /** 秒被忽略：22:30:59 仍应给出 22:30 这班（站牌精度就是分钟，别把人判成"已错过"）。 */
    @Test
    fun `秒被忽略`() {
        val d = ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, LocalTime.of(22, 30, 59))!!
        assertEquals(t(22, 30), d.at)
    }

    @Test
    fun `剩余班次数正确`() {
        // 上午 7:00 → 全天 39 班
        assertEquals(39, ShuttleLogic.remainingToday(Stop.LIBRARY, DayType.WORKDAY, t(7, 0)).size)
        // 末班时刻 → 只剩它自己
        assertEquals(1, ShuttleLogic.remainingToday(Stop.LIBRARY, DayType.WORKDAY, t(22, 30)).size)
        // 过了末班 → 空
        assertTrue(ShuttleLogic.remainingToday(Stop.LIBRARY, DayType.WORKDAY, t(22, 31)).isEmpty())
    }

    /** 两个站点给的下一班应当不同（间隔不是固定值，各存各的表才会对）。 */
    @Test
    fun `两站的下一班各自独立`() {
        val lib = ShuttleLogic.nextDeparture(Stop.LIBRARY, DayType.WORKDAY, t(7, 35))!!
        val met = ShuttleLogic.nextDeparture(Stop.METRO, DayType.WORKDAY, t(7, 35))!!
        assertEquals(t(7, 46), lib.at)
        assertEquals(t(7, 38), met.at)
    }

    // ------------------------------------------------------------ 日类型与文案

    @Test
    fun `周末按星期自动判定`() {
        assertEquals(DayType.WORKDAY, ShuttleLogic.dayTypeOf(LocalDate.of(2026, 9, 25)))       // 周五
        assertEquals(DayType.WEEKEND_HOLIDAY, ShuttleLogic.dayTypeOf(LocalDate.of(2026, 9, 26))) // 周六
        assertEquals(DayType.WEEKEND_HOLIDAY, ShuttleLogic.dayTypeOf(LocalDate.of(2026, 9, 27))) // 周日
        assertEquals(DayType.WORKDAY, ShuttleLogic.dayTypeOf(LocalDate.of(2026, 9, 28)))       // 周一
    }

    /**
     * ⚠️ 法定节假日**判断不了**（站牌上也写着寒暑假会调整）。
     * 这里锁住"只按星期"这个行为，避免以后有人以为它懂节假日。
     */
    @Test
    fun `法定节假日不会被自动识别`() {
        // 2026-10-01 是周四，但按本实现的默认口径仍是 WORKDAY
        assertEquals(DayType.WORKDAY, ShuttleLogic.dayTypeOf(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `倒计时文案`() {
        assertEquals("即将发车", ShuttleLogic.countdownText(0))
        assertEquals("即将发车", ShuttleLogic.countdownText(-3))
        assertEquals("6 分钟后", ShuttleLogic.countdownText(6))
        assertEquals("59 分钟后", ShuttleLogic.countdownText(59))
        assertEquals("1 小时 0 分钟后", ShuttleLogic.countdownText(60))
        assertEquals("1 小时 30 分钟后", ShuttleLogic.countdownText(90))
    }
}
