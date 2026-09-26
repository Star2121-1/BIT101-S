package cn.bit101.android.data.school

import java.time.LocalDate
import java.time.ZoneId

/**
 * 一卡通余额的一次采样。
 *
 * @property atMillis 采样时刻（epoch 毫秒）
 * @property amount 余额金额（元）
 */
data class BalanceSample(
    val atMillis: Long,
    val amount: Double,
)

/**
 * 余额趋势。
 *
 * @property todayDelta 今天的变化 = 今天最新的余额 − 今天**最早**的那次余额。
 *   今天只采到一次时为 null —— 算不出来就别说，宁可空着也不给个错的数
 * @property spanDelta 相对基准采样的变化（减少=花钱，增加=充值）
 * @property spanDays [spanDelta] 实际跨了几个自然日，用来写「近 N 天」。
 *   0 表示 [spanDelta] 不可用
 */
data class BalanceTrend(
    val todayDelta: Double? = null,
    val spanDelta: Double? = null,
    val spanDays: Int = 0,
)

/**
 * 一卡通余额的**本地历史与趋势**（纯逻辑，可单测；文件读写在 `CampusCardBalanceStore`）。
 *
 * ## 为什么要有它
 *
 * 一卡通流水是**钉钉客户端专属**、App 端拿不到（结论与复现见 `CampusCardProbe`），
 * 但「这些天花了多少钱」是用户真正想知道的。既然每次刷新都能看到一个余额，
 * 就把这些采样点攒起来，**用余额的下降幅度反推消费** —— 不精确，但比什么都没有强。
 *
 * ## 口径
 *
 * - 每次成功取到余额记一条 `(时刻, 金额)`
 * - 「今日」的基准 = 今天**最早**的那次采样；「近 N 天」的基准 = 7 天窗口内最早的
 *   那次采样。样本还没攒够 7 天时**如实退化成「近 N 天」**（N 是真实跨度），
 *   不硬说「近 7 天」
 * - 连续的**同金额采样在同一个自然日内合并**（保留最早的那条）：
 *   余额大多时候不变，不合并的话文件会被无效重复撑大
 */
object CampusCardBalanceLogic {

    /** 趋势窗口：优先看最近 7 天（Long 是为了直接喂给 `LocalDate.minusDays`）。 */
    const val WINDOW_DAYS = 7L

    /** 最多保留多少条采样（防止文件无限增长）。 */
    const val MAX_COUNT = 400

    /** 最多保留多少天（更早的采样对「近 7 天」没有意义）。 */
    const val MAX_DAYS = 120L

    /** 判定「没有变化」的阈值：两位小数显示下低于半分钱都算不变。 */
    private const val EPSILON = 0.005

    // ------------------------------------------------------------------ 存储编码

    /**
     * 编码成一行一条：`时刻|金额`。
     *
     * 用纯文本而不是 JSON：这个文件只有几十行、只给我们自己读，
     * 出问题时 `adb shell cat` 一眼就能看懂。
     */
    fun encode(samples: List<BalanceSample>): String =
        samples.joinToString("\n") { "${it.atMillis}|${it.amount}" }

    /** 解析；形状不对的行**直接丢掉**（不猜）。 */
    fun decode(raw: String?): List<BalanceSample> =
        raw?.lineSequence()
            .orEmpty()
            .mapNotNull { line ->
                val parts = line.trim().split('|')
                if (parts.size != 2) return@mapNotNull null
                val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                val amount = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                BalanceSample(at, amount)
            }
            .sortedBy { it.atMillis }
            .toList()

    // ------------------------------------------------------------------ 记录

    /**
     * 追加一条采样并做裁剪，返回新的采样列表。
     *
     * 合并规则：上一条与本次**金额相同、且在同一天**时，**不追加**
     * （保留上一条更早的时刻，这样「今天最早那次余额」仍然是今天第一次观察到的值）。
     */
    fun append(
        samples: List<BalanceSample>,
        amount: Double,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BalanceSample> {
        val last = samples.lastOrNull()
        val merged = if (last != null
            && kotlin.math.abs(last.amount - amount) < EPSILON
            && dateOf(last.atMillis, zone) == dateOf(atMillis, zone)
        ) {
            samples
        } else {
            samples + BalanceSample(atMillis, amount)
        }

        return prune(merged, atMillis, zone)
    }

    /** 丢太老的、再砍到 [MAX_COUNT]（**从最新往回留**）。 */
    private fun prune(
        samples: List<BalanceSample>,
        nowMillis: Long,
        zone: ZoneId,
    ): List<BalanceSample> {
        val oldestKept = dateOf(nowMillis, zone).minusDays(MAX_DAYS)
        return samples
            .filter { dateOf(it.atMillis, zone) >= oldestKept }
            .takeLast(MAX_COUNT)
    }

    // ------------------------------------------------------------------ 趋势

    /**
     * 算趋势。
     *
     * @param samples 历史采样（需按时间升序；[append] 与 [decode] 都保证这一点）
     * @param today 今天（显式传入便于单测）
     */
    fun trend(
        samples: List<BalanceSample>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BalanceTrend {
        if (samples.isEmpty()) return BalanceTrend()

        val latest = samples.last()
        val todaySamples = samples.filter { dateOf(it.atMillis, zone) == today }

        val todayDelta =
            if (todaySamples.size >= 2) latest.amount - todaySamples.first().amount else null

        // 7 天窗口内**最早**的那条采样。样本不足 7 天时它就是整体最早的那条 ——
        // 于是 spanDays 会如实变成 1~6，文案跟着写「近 N 天」
        val windowStart = today.minusDays(WINDOW_DAYS)
        val inWindow = samples.filter { dateOf(it.atMillis, zone) >= windowStart }

        // 窗口内一条采样都没有（超过 7 天没打开过 App）→ 只报今日，不硬凑「近 N 天」
        val base = inWindow.firstOrNull() ?: return BalanceTrend(todayDelta = todayDelta)

        val spanDays = java.time.temporal.ChronoUnit.DAYS
            .between(dateOf(base.atMillis, zone), today)
            .toInt()

        val spanDelta = if (spanDays >= 1) latest.amount - base.amount else null

        return BalanceTrend(todayDelta = todayDelta, spanDelta = spanDelta, spanDays = spanDays)
    }

    private fun dateOf(atMillis: Long, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()

    // ------------------------------------------------------------------ 文案

    /**
     * 趋势文案，如 `今日 -¥4.20 · 近 7 天 -¥23.50`；算不出来时返回 null。
     *
     * 正数是**充值**（余额变多），负数是**消费** —— 直接标正负号比写
     * 「支出/充值」更适合这种一行摘要。
     */
    fun trendText(trend: BalanceTrend): String? {
        val parts = buildList {
            trend.todayDelta?.let { add("今日 ${signedText(it)}") }
            if (trend.spanDelta != null && trend.spanDays >= 1) {
                add("近 ${trend.spanDays} 天 ${signedText(trend.spanDelta)}")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** `-¥4.20` / `+¥50.00` / `无变化`。 */
    fun signedText(delta: Double): String = when {
        delta > EPSILON -> "+¥%.2f".format(delta)
        delta < -EPSILON -> "-¥%.2f".format(-delta)
        else -> "无变化"
    }

    /**
     * 详情页那一行的文案 —— **算不出来时也要给一句话**。
     *
     * 刚装上 / 刚登录的用户一条历史都没有，[trendText] 返回 null。此时整行消失的话，
     * 用户看到的是「这里什么都没有」，只会得出「功能没做」的结论；
     * 明说「还在记录」才是诚实的（历史攒够 1 天就有了）。
     */
    fun trendRowText(trend: BalanceTrend): String =
        trendText(trend) ?: "记录中（攒够历史后显示变化）"
}
