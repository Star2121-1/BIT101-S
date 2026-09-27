package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 校园网流量趋势的单测。
 *
 * 这里最容易错、也最该锁死的是**计费周期重置**：接口 `[6]` 换周期会归零，
 * 不检测就会算出「负增量」这种荒谬结果。
 */
class CampusNetTrafficLogicTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val GB = 1024L * 1024 * 1024

    /** 2026-09-day 的 10:00（epoch 毫秒）。 */
    private fun at(day: Int, hour: Int = 10): Long =
        LocalDate.of(2026, 9, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now: LocalDate = LocalDate.of(2026, 9, 22)

    // ------------------------------------------------------------ 追加采样

    /**
     * ⚠️ 本文件最关键的一条：累计用量变小 = **换了计费周期**，
     * 上一个周期的采样必须全部作废，否则会算出负的增量。
     */
    @Test
    fun `周期重置时旧采样全部作废`() {
        val old = listOf(
            TrafficSample(at(10), 50 * GB),
            TrafficSample(at(15), 290 * GB),
        )

        val result = CampusNetTrafficLogic.append(old, 3 * GB, at(23), zone)

        assertEquals(1, result.size)
        assertEquals(3 * GB, result.last().bytes)
        assertEquals(at(23), result.last().atMillis)
    }

    /** 没超上一条、就是正常增长 → 追加。 */
    @Test
    fun `正常增长时追加`() {
        val old = listOf(TrafficSample(at(20), 100 * GB))
        val result = CampusNetTrafficLogic.append(old, 110 * GB, at(21), zone)
        assertEquals(2, result.size)
        assertEquals(110 * GB, result.last().bytes)
    }

    /** 本页每次进都刷新一次 —— 同一天、同一个累计值不该重复落盘。 */
    @Test
    fun `同一天同一个值不重复记`() {
        val old = listOf(TrafficSample(at(22), 130 * GB))
        val result = CampusNetTrafficLogic.append(old, 130 * GB, at(22, 12), zone)
        assertEquals(1, result.size)
    }

    /** 但同一天**不同**值要保留 —— 今日用量就是靠它们算的。 */
    @Test
    fun `同一天不同值要保留`() {
        val old = listOf(TrafficSample(at(22), 130 * GB))
        val result = CampusNetTrafficLogic.append(old, 135 * GB, at(22, 12), zone)
        assertEquals(2, result.size)
    }

    @Test
    fun `负数的用量不记`() {
        val old = listOf(TrafficSample(at(22), 130 * GB))
        assertEquals(old, CampusNetTrafficLogic.append(old, -1L, at(22, 12), zone))
    }

    // ------------------------------------------------------------ 趋势

    @Test
    fun `没有采样时趋势全为空`() {
        val trend = CampusNetTrafficLogic.trend(emptyList(), now, zone)
        assertNull(trend.usedBytes)
        assertNull(trend.todayBytes)
        assertNull(trend.dailyAverageBytes)
        assertEquals(0, trend.spanDays)
        assertNull(trend.projectedMonthBytes)
    }

    /** 今天只采到一次 → 今日用量算不出来，就返回 null（不编数）。 */
    @Test
    fun `今天只采到一次时今日用量为空`() {
        val samples = listOf(TrafficSample(at(22), 130 * GB))
        assertNull(CampusNetTrafficLogic.trend(samples, now, zone).todayBytes)
    }

    @Test
    fun `今日用量是今天最早到最新的差`() {
        val samples = listOf(
            TrafficSample(at(21), 110 * GB),
            TrafficSample(at(22, 8), 130 * GB),
            TrafficSample(at(22, 12), 135 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertEquals(135 * GB, trend.usedBytes)
        assertEquals(5 * GB, trend.todayBytes)
    }

    /**
     * 日均按**自然日**跨度算 —— 不是按 24 小时。
     * 9/20 10 点 → 9/22 10 点是 2 个自然日。
     */
    @Test
    fun `日均按自然日跨度算`() {
        val samples = listOf(
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(21), 110 * GB),
            TrafficSample(at(22), 130 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertEquals(2, trend.spanDays)
        assertEquals(15 * GB, trend.dailyAverageBytes)
    }

    /** 采样都在同一天 → 日均算不出来（跨不满一个自然日），返回 null 而不是除以 0。 */
    @Test
    fun `同一天的采样算不出日均`() {
        val samples = listOf(
            TrafficSample(at(22, 8), 130 * GB),
            TrafficSample(at(22, 12), 135 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertNull(trend.dailyAverageBytes)
        assertEquals(0, trend.spanDays)
        assertNull(trend.projectedMonthBytes)
    }

    /**
     * 月末预测：`已用 + 日均 × 到月末剩余天数`。
     * 9/22 → 9/30 还剩 8 天；日均 15 GB ⇒ 130 + 15×8 = 250 GB。
     */
    @Test
    fun `月末预测按日均推算`() {
        val samples = listOf(
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(21), 110 * GB),
            TrafficSample(at(22), 130 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertEquals(250 * GB, trend.projectedMonthBytes)
    }

    /** 月末当天：剩余 0 天 ⇒ 预测就等于已用。 */
    @Test
    fun `月末当天不再往前推`() {
        val lastDay = LocalDate.of(2026, 9, 30)
        val samples = listOf(
            TrafficSample(at(29), 200 * GB),
            TrafficSample(at(30), 210 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, lastDay, zone)
        assertEquals(210 * GB, trend.projectedMonthBytes)
    }

    /** 窗口之外的老采样不计入日均（只看近 7 天）。 */
    @Test
    fun `窗口之外的老采样不参与日均`() {
        val samples = listOf(
            TrafficSample(at(1), 0),              // 21 天前，超出 7 天窗口
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(22), 130 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        // 基准取窗口内最早那条（9/20 的 100 GB），而不是 9/1 的 0
        assertEquals(2, trend.spanDays)
        assertEquals(15 * GB, trend.dailyAverageBytes)
    }

    // ------------------------------------------------------------ 编解码

    @Test
    fun `编解码往返`() {
        val samples = listOf(
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(22), 130 * GB + 12345),
        )
        assertEquals(samples, CampusNetTrafficLogic.decode(CampusNetTrafficLogic.encode(samples)))
    }

    @Test
    fun `形状不对的行整条丢弃`() {
        assertEquals(1, CampusNetTrafficLogic.decode("1000|2000\n坏了的行\n\n3000|abc").size)
        assertTrue(CampusNetTrafficLogic.decode(null).isEmpty())
        assertTrue(CampusNetTrafficLogic.decode("").isEmpty())
    }

    // ------------------------------------------------------------ 展示文案

    @Test
    fun `今日用量文案`() {
        val samples = listOf(
            TrafficSample(at(22, 8), 130 * GB),
            TrafficSample(at(22, 12), 135 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertEquals("5.0 GB", CampusNetTrafficLogic.todayText(trend))
    }

    /** 今天只采到一次 ⇒ 文案为 null（UI 显示「—」），而不是显示 0。 */
    @Test
    fun `今日用量算不出时文案为空`() {
        val trend = CampusNetTrafficLogic.trend(listOf(TrafficSample(at(22), 130 * GB)), now, zone)
        assertNull(CampusNetTrafficLogic.todayText(trend))
    }

    @Test
    fun `日均与预测文案`() {
        val samples = listOf(
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(21), 110 * GB),
            TrafficSample(at(22), 130 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertEquals(
            "日均 15.0 GB（近 2 天） · 月末约 250.0 GB",
            CampusNetTrafficLogic.forecastText(trend),
        )
    }

    /** 预测值越过限速阈值 → 必须补警示（这是本功能存在的意义）。 */
    @Test
    fun `预测会超限时文案带警示`() {
        val limit = 150 * GB
        val samples = listOf(
            TrafficSample(at(20), 100 * GB),
            TrafficSample(at(21), 110 * GB),
            TrafficSample(at(22), 130 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        val text = CampusNetTrafficLogic.forecastText(trend, limit)!!
        assertTrue(text.contains("会超限速阈值"))
        assertTrue(text.contains("月末约 250.0 GB"))
    }

    /** 采样跨不满一天 ⇒ 预测文案为 null（UI 说「数据积累中」），绝不编数。 */
    @Test
    fun `采样不足时预测文案为空`() {
        val samples = listOf(
            TrafficSample(at(22, 8), 130 * GB),
            TrafficSample(at(22, 12), 135 * GB),
        )
        val trend = CampusNetTrafficLogic.trend(samples, now, zone)
        assertNull(CampusNetTrafficLogic.forecastText(trend))
    }
}
