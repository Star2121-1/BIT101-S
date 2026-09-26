package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [CampusCardBalanceLogic] 测试 —— 编码往返、采样合并、趋势口径与文案。
 *
 * 时间统一用 `Asia/Shanghai`（学校所在地），避免测试跟着机器时区漂。
 */
class CampusCardBalanceLogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDateTime.parse("${date}T%02d:%02d:00".format(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private val today = LocalDate.of(2026, 9, 26)

    // ------------------------------------------------------------------ 编码

    @Test
    fun `编码解码往返`() {
        val samples = listOf(
            BalanceSample(at("2026-09-20", 9), 51.33),
            BalanceSample(at("2026-09-26", 8), 34.05),
        )

        assertEquals(samples, CampusCardBalanceLogic.decode(CampusCardBalanceLogic.encode(samples)))
    }

    /** 坏行直接丢，不猜、也不让整份历史作废。 */
    @Test
    fun `解码容忍脏数据并按时序排序`() {
        val raw = """
            1758888000000|34.05
            不是一条记录
            1758801600000|51.33
            xyz|1.00
            1758974400000|abc
        """.trimIndent()

        val decoded = CampusCardBalanceLogic.decode(raw)

        assertEquals(2, decoded.size)
        assertEquals(1758801600000L, decoded[0].atMillis)
        assertEquals(1758888000000L, decoded[1].atMillis)
    }

    @Test
    fun `空输入解码为空表`() {
        assertEquals(emptyList<BalanceSample>(), CampusCardBalanceLogic.decode(null))
        assertEquals(emptyList<BalanceSample>(), CampusCardBalanceLogic.decode(""))
    }

    // ------------------------------------------------------------------ 采样合并

    /** 同一天、金额没变 → 不新增（余额大多时候不变，不合并会撑大文件）。 */
    @Test
    fun `同日同金额只留最早一条`() {
        val first = CampusCardBalanceLogic.append(emptyList(), 34.05, at("2026-09-26", 8), zone)
        val second = CampusCardBalanceLogic.append(first, 34.05, at("2026-09-26", 12), zone)

        assertEquals(1, second.size)
        // 保留的是**最早**那条时刻 —— 「今天最早那次余额」必须是今天第一次观察到的值
        assertEquals(at("2026-09-26", 8), second[0].atMillis)
    }

    /** 金额变了就要记：这正是消费/充值的证据。 */
    @Test
    fun `金额变化会新增采样`() {
        val list = CampusCardBalanceLogic.append(emptyList(), 40.00, at("2026-09-26", 8), zone)
        val after = CampusCardBalanceLogic.append(list, 34.05, at("2026-09-26", 12), zone)

        assertEquals(2, after.size)
    }

    /** 跨了自然日，金额没变也要记（否则「近 N 天」会缺基准）。 */
    @Test
    fun `跨天同金额仍记一条`() {
        val list = CampusCardBalanceLogic.append(emptyList(), 34.05, at("2026-09-25", 20), zone)
        val after = CampusCardBalanceLogic.append(list, 34.05, at("2026-09-26", 8), zone)

        assertEquals(2, after.size)
    }

    @Test
    fun `裁剪掉过老的采样并限制条数`() {
        val old = BalanceSample(at("2026-01-01", 9), 100.0)
        val fresh = BalanceSample(at("2026-09-26", 9), 34.05)

        val pruned = CampusCardBalanceLogic.append(listOf(old, fresh), 33.0, at("2026-09-26", 10), zone)

        assertEquals(2, pruned.size)
        assertEquals(33.0, pruned.last().amount, 0.001)
        // 半年多以前那条（MAX_DAYS=120）已被丢掉
        assertEquals(at("2026-09-26", 9), pruned.first().atMillis)
    }

    // ------------------------------------------------------------------ 趋势

    @Test
    fun `没有采样时趋势全空`() {
        val trend = CampusCardBalanceLogic.trend(emptyList(), today, zone)

        assertNull(trend.todayDelta)
        assertNull(trend.spanDelta)
        assertEquals(0, trend.spanDays)
        assertNull(CampusCardBalanceLogic.trendText(trend))
    }

    /**
     * 没有结论时**也要给一句话** —— 整行消失会被读成「功能没做」。
     */
    @Test
    fun `算不出来时给记录中占位`() {
        assertEquals(
            "记录中（攒够历史后显示变化）",
            CampusCardBalanceLogic.trendRowText(CampusCardBalanceLogic.trend(emptyList(), today, zone)),
        )
        assertEquals(
            "近 3 天 -¥15.95",
            CampusCardBalanceLogic.trendRowText(
                CampusCardBalanceLogic.trend(
                    listOf(
                        BalanceSample(at("2026-09-23", 9), 50.0),
                        BalanceSample(at("2026-09-26", 18), 34.05),
                    ),
                    today,
                    zone,
                )
            ),
        )
    }

    /** 今天只采到一次 → 算不出「今日变化」，就别说。 */
    @Test
    fun `今天只采一次时不给今日变化`() {
        val samples = listOf(BalanceSample(at("2026-09-26", 9), 40.0))

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertNull(trend.todayDelta)
        assertEquals(0, trend.spanDays)
        assertNull(trend.spanDelta)
    }

    @Test
    fun `今日变化等于今天最早到最新`() {
        val samples = listOf(
            BalanceSample(at("2026-09-26", 8), 40.00),
            BalanceSample(at("2026-09-26", 12), 36.50),
            BalanceSample(at("2026-09-26", 18), 34.05),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals(-5.95, trend.todayDelta!!, 0.001)
    }

    /** 样本攒够 7 天 → 基准取 7 天窗口内最早的那条，跨度就是 7 天。 */
    @Test
    fun `近七天跨度与金额`() {
        val samples = listOf(
            BalanceSample(at("2026-09-18", 9), 80.0),   // 窗口外，不作为基准
            BalanceSample(at("2026-09-19", 9), 60.0),
            BalanceSample(at("2026-09-26", 18), 34.05),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals(7, trend.spanDays)
        assertEquals(-25.95, trend.spanDelta!!, 0.001)
        assertEquals("近 7 天 -¥25.95", CampusCardBalanceLogic.trendText(trend))
    }

    /** 历史还不足 7 天 → 如实写「近 3 天」，不硬说 7 天。 */
    @Test
    fun `样本不足七天时如实写实际跨度`() {
        val samples = listOf(
            BalanceSample(at("2026-09-23", 9), 50.0),
            BalanceSample(at("2026-09-26", 18), 34.05),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals(3, trend.spanDays)
        assertEquals("近 3 天 -¥15.95", CampusCardBalanceLogic.trendText(trend))
    }

    /** 充值时是正数（余额变多）。 */
    @Test
    fun `充值显示为正号`() {
        val samples = listOf(
            BalanceSample(at("2026-09-23", 9), 10.0),
            BalanceSample(at("2026-09-26", 8), 60.0),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals("近 3 天 +¥50.00", CampusCardBalanceLogic.trendText(trend))
    }

    @Test
    fun `今日与近N天拼成一行`() {
        val samples = listOf(
            BalanceSample(at("2026-09-19", 9), 60.0),
            BalanceSample(at("2026-09-26", 8), 40.00),
            BalanceSample(at("2026-09-26", 18), 34.05),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals("今日 -¥5.95 · 近 7 天 -¥25.95", CampusCardBalanceLogic.trendText(trend))
    }

    @Test
    fun `没花钱写无变化`() {
        val samples = listOf(
            BalanceSample(at("2026-09-19", 9), 34.05),
            BalanceSample(at("2026-09-26", 8), 34.05),
            BalanceSample(at("2026-09-26", 18), 34.05),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertEquals("今日 无变化 · 近 7 天 无变化", CampusCardBalanceLogic.trendText(trend))
    }

    /** 超过 7 天没打开过 App（窗口内没有采样）→ 只报今日，不硬凑「近 N 天」。 */
    @Test
    fun `窗口内无采样时不给跨度结论`() {
        val samples = listOf(
            BalanceSample(at("2026-09-01", 9), 80.0),
            BalanceSample(at("2026-09-02", 9), 60.0),
        )

        val trend = CampusCardBalanceLogic.trend(samples, today, zone)

        assertNull(trend.spanDelta)
        assertEquals(0, trend.spanDays)
        assertNull(CampusCardBalanceLogic.trendText(trend))
    }

    /** 浮点残差（如 34.05-34.05 的 1e-15）不能被当成"变了 0.0000001 元"。 */
    @Test
    fun `极小残差按无变化处理`() {
        assertEquals("无变化", CampusCardBalanceLogic.signedText(1e-15))
        assertEquals("无变化", CampusCardBalanceLogic.signedText(0.001))
        assertEquals("-¥0.01", CampusCardBalanceLogic.signedText(-0.01))
        assertEquals("+¥0.01", CampusCardBalanceLogic.signedText(0.01))
    }
}
