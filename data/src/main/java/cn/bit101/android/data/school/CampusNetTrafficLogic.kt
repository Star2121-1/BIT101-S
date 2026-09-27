package cn.bit101.android.data.school

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 校园网流量的一次采样。
 *
 * @property atMillis 采样时刻（epoch 毫秒）
 * @property bytes 当时**本计费周期累计用量**（接口 `[6]`，官方门户显示成「本月」）
 */
data class TrafficSample(
    val atMillis: Long,
    val bytes: Long,
)

/**
 * 流量趋势与月末预测。
 *
 * ⚠️ 所有字段都**可以为 null**：采样还没攒够时算不出来 —— 那就空着，
 * 绝不给一个看着像真的假数字（与一卡通余额趋势同一口径）。
 *
 * @property usedBytes 本周期已用（= 最新采样值）
 * @property todayBytes 今日用量；今天只采到一次时为 null
 * @property dailyAverageBytes 近 N 天日均；采样跨不满一个自然日时为 null
 * @property spanDays [dailyAverageBytes] 实际跨了几个自然日（用来写「近 N 天」）；0 = 不可用
 * @property projectedMonthBytes 按当前日均推到**本自然月末**的总用量；算不出时为 null
 */
data class TrafficTrend(
    val usedBytes: Long? = null,
    val todayBytes: Long? = null,
    val dailyAverageBytes: Long? = null,
    val spanDays: Int = 0,
    val projectedMonthBytes: Long? = null,
)

/**
 * 校园网流量的**本地历史与趋势**（纯逻辑，可单测；文件读写在 `CampusNetTrafficStore`）。
 *
 * ## 为什么要有它
 *
 * 校园网只给一个「本月已用 366 GB」的累计数字 —— 它回答不了用户真正想知道的：
 * **我这个月会不会用超（300 GB 后限速）**。
 * 既然每次刷新都能拿到这个累计值，就把它攒起来，用**增量速率**推算出月末用量。
 *
 * ## ⚠️ 与一卡通余额最大的不同：计费周期会重置
 *
 * `[6]` 是**按计费周期累计**的，换周期就从很小的值重新开始（`NetFlowChecker` 里
 * 就是靠「见到用量比上次小」判断周期重置的）。
 * ⇒ 采样必须**检测重置**：一旦新的累计值小于上一条，说明上周期的采样全废了，
 * 直接清空重来 —— 否则会算出「负的增量」这种荒谬结果。
 *
 * ## 口径
 *
 * - 每次成功取到校园网数据记一条 `(时刻, 累计字节)`
 * - 同一天、同一个累计值**不重复记**（本页每次进都会刷新一次，不合并会把文件撑大）
 * - 「今日」= 今天最早采样 → 最新；「日均」= 窗口内最早 → 最新，跨 N 个自然日
 * - **月末预测假设计费周期 = 自然月**（学校门户显示成「本月」）→
 *   `已用 + 日均 × 到月末剩余天数`。这是**估算**，UI 上要说清。
 */
object CampusNetTrafficLogic {

    /** 趋势窗口（自然日）。 */
    const val WINDOW_DAYS = 7L

    /** 最多保留多少条采样。 */
    const val MAX_COUNT = 400

    /** 最多保留多少天的采样。 */
    const val MAX_DAYS = 120L

    // ------------------------------------------------------------ 追加采样

    /**
     * 追加一条采样。
     *
     * @return 追加（并裁剪）后的完整列表
     */
    fun append(
        samples: List<TrafficSample>,
        bytes: Long,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TrafficSample> {
        if (bytes < 0) return samples

        val last = samples.lastOrNull()

        // ⚠️ 计费周期重置：累计用量变小 ⇒ 上周期的采样全部作废，从这条重新开始
        if (last != null && bytes < last.bytes) return listOf(TrafficSample(atMillis, bytes))

        // 同一天、同一个累计值 → 不重复记
        val date = dateOf(atMillis, zone)
        if (last != null && dateOf(last.atMillis, zone) == date && last.bytes == bytes) {
            return samples
        }

        val cutoff = atMillis - MAX_DAYS * 24 * 3600 * 1000L
        return (samples + TrafficSample(atMillis, bytes))
            .filter { it.atMillis >= cutoff }
            .takeLast(MAX_COUNT)
    }

    // ------------------------------------------------------------ 趋势

    /**
     * 算趋势与月末预测。
     *
     * @param now 今天（自然日），用于算「今日」与「到月末还剩几天」
     */
    fun trend(
        samples: List<TrafficSample>,
        now: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TrafficTrend {
        if (samples.isEmpty()) return TrafficTrend()
        val latest = samples.last()
        val used = latest.bytes

        // 今日：今天最早的采样 → 最新（今天只采到一次就是 null）
        val todayStart = now.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = samples.filter { it.atMillis >= todayStart }
        val todayBytes = if (today.size >= 2) (latest.bytes - today.first().bytes) else null

        // 日均：窗口内最早 → 最新，按**自然日**跨度算（不是按 24 小时）
        val windowStart = now.minusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val window = samples.filter { it.atMillis >= windowStart }
        // ⚠️ 窗口可能为空：用户很久没在校内，最新采样早于窗口起点。
        // 退化成只看最新那条（spanDays = 0 ⇒ 日均算不出来、返回 null），**而不是崩**
        val base = window.firstOrNull() ?: latest
        val spanDays = ChronoUnit.DAYS.between(dateOf(base.atMillis, zone), dateOf(latest.atMillis, zone)).toInt()
        val dailyAverageBytes =
            if (spanDays >= 1) (latest.bytes - base.bytes) / spanDays else null

        // 月末预测：已用 + 日均 × 到月末剩余天数
        val remainingDays = ChronoUnit.DAYS.between(now, now.withDayOfMonth(now.lengthOfMonth()))
        val projectedMonthBytes = dailyAverageBytes?.let { used + it * remainingDays }

        return TrafficTrend(
            usedBytes = used,
            todayBytes = todayBytes,
            dailyAverageBytes = dailyAverageBytes,
            spanDays = if (dailyAverageBytes != null) spanDays else 0,
            projectedMonthBytes = projectedMonthBytes,
        )
    }

    // ------------------------------------------------------------ 编解码

    /** 编码成单行文本：`毫秒|字节`，一行一条。 */
    fun encode(samples: List<TrafficSample>): String =
        samples.joinToString("\n") { "${it.atMillis}|${it.bytes}" }

    /** 解析；形状不对的**整条丢弃**（不猜）。 */
    fun decode(raw: String?): List<TrafficSample> =
        raw?.lineSequence()?.mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 2) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bytes = parts[1].toLongOrNull() ?: return@mapNotNull null
            TrafficSample(at, bytes)
        }?.toList().orEmpty()

    // ------------------------------------------------------------ 展示文案

    /**
     * 「今日用量」文案；算不出（今天只采到一次）返回 null。
     *
     * ⚠️ 调用方据此显示「—」而不是 0 —— **0 会让人以为今天没用流量**，而真相是"不知道"。
     */
    fun todayText(trend: TrafficTrend): String? =
        trend.todayBytes?.let { CampusNetLogic.formatTraffic(it) }

    /**
     * 「日均 + 月末预测」文案：`日均 15.0 GB（近 2 天）· 月末约 250.0 GB`；
     * 预测值越过 [limitBytes] 时补一句警示。
     *
     * ⚠️ 采样跨不满一个自然日时返回 null —— 此时 UI 说「数据积累中」，**绝不编一个数**。
     */
    fun forecastText(
        trend: TrafficTrend,
        limitBytes: Long = CampusNetLogic.LIMIT_BYTES,
    ): String? {
        val avg = trend.dailyAverageBytes ?: return null
        val projected = trend.projectedMonthBytes ?: return null
        val days = trend.spanDays.takeIf { it > 0 } ?: return null

        val head = "日均 ${CampusNetLogic.formatTraffic(avg)}（近 $days 天）"
        val tail = "月末约 ${CampusNetLogic.formatTraffic(projected)}"
        return if (projected >= limitBytes) "$head · $tail（按此速度会超限速阈值）"
        else "$head · $tail"
    }

    private fun dateOf(atMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
}
