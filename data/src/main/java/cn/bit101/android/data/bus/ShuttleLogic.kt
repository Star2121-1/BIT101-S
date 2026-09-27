package cn.bit101.android.data.bus

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 良乡校区**摆渡车**的时刻表与「下一班」推算。
 *
 * ## 数据来源（⚠️ 改动前必读）
 *
 * 数据**逐条抄自站点站牌实拍照片**（2026-09-27 由用户提供），
 * 站牌上就是**两套表**：**工作日**（各 39 班）与**周末及节假日**（各 50 班）。
 *
 * ⚠️ 别再用网上那些转抄版：
 * - 学校官网 `liangxiang.bit.edu.cn/cyfw/bjsj/` 的图是 **2021/2022 年上传**的，
 *   写「每 20 分钟」——**已过期**（实际密集时段 16 分钟一班）
 * - 公众号整理的那份（2026-05-05）其实**只抄了「周末及节假日」那一列**，
 *   没有工作日数据
 * - 详细调研与两个数据源的对比见 `docs/campus-bus.md`
 *
 * ## 几件容易搞错的事
 *
 * 1. **只有两个站有固定发车时间**：地铁站（良乡大学城北）与徐特立图书馆。
 *    东校区南门是**中途停靠、没有时刻表**的，所以这里没有它。
 * 2. **两站的间隔不是固定值**：早晨/傍晚约 8 分钟，中午平峰约 10 分钟 ——
 *    所以「地铁站 = 图书馆 + 8」这种换算**不成立**，两套时间必须各自存。
 * 3. 班次在**午间和下午有明显空档**（工作日：9:22→11:30、13:10→15:30），
 *    这是站牌上的真实编排，不是漏抄。
 * 4. **法定节假日与调休无法自动判断**（暑假寒假也会调整）→
 *    [dayTypeOf] 只按星期给默认值，页面要给用户手动切换的余地。
 */
object ShuttleLogic {

    /** 有固定发车时刻的两个站点。 */
    enum class Stop(val label: String) {
        /** 良乡大学城北站（房山线地铁站），A 口公交牌处。 */
        METRO("良乡大学城北（地铁站）"),

        /** 北校区 徐特立图书馆（= 北校区门口）。 */
        LIBRARY("北校区 徐特立图书馆"),
    }

    /**
     * 一天属于哪套表。
     *
     * ⚠️ **只有这两类** —— 站牌就是这么印的（「工作日」/「周末及节假日」）。
     * 法定节假日与调休**判断不了**，交给调用方按 [dayTypeOf] 拿默认值再允许覆盖。
     */
    enum class DayType(val label: String) {
        WORKDAY("工作日"),
        WEEKEND_HOLIDAY("周末及节假日"),
    }

    /** 一班车。 */
    data class Departure(
        /** 发车时刻。 */
        val at: LocalTime,
        /** 距现在还有几分钟（[now] 早于首班时，是到首班的分钟数）。 */
        val inMinutes: Long,
        /** 今天总共还剩几班（含这一班）。 */
        val remaining: Int,
        /** 这是今天的第几班（从 1 开始）。 */
        val sequence: Int,
    )

    // ------------------------------------------------------------ 时刻表

    /**
     * 时刻表：`(站点 to 日类型) -> 发车时刻列表`（升序）。
     *
     * ⚠️ 用**字符串**而不是 `listOf(450, 466, …)`：数字列表没法对着站牌核对，
     * 而字符串与照片上的顺序一一对应，抄错一眼能看出来。
     */
    private val RAW: Map<Pair<Stop, DayType>, String> = mapOf(
        // ---- 北校区 徐特立图书馆 发 ----
        (Stop.LIBRARY to DayType.WORKDAY) to
                "7:30 7:46 8:02 8:18 8:34 8:50 9:06 9:22 11:30 11:50 12:10 12:30 12:50 13:10 " +
                "15:30 15:46 16:02 16:18 16:34 16:50 17:06 17:46 18:02 18:18 18:34 18:50 " +
                "19:06 19:22 19:38 19:54 20:10 20:26 20:42 20:58 21:14 21:30 21:50 22:10 22:30",

        (Stop.LIBRARY to DayType.WEEKEND_HOLIDAY) to
                "7:30 7:46 8:02 8:18 8:34 8:50 9:06 9:22 9:38 9:50 10:10 10:30 10:50 " +
                "11:30 11:50 12:10 12:30 12:50 13:10 13:30 13:50 14:10 14:30 14:50 15:10 " +
                "15:30 15:46 16:02 16:18 16:34 16:50 17:06 17:46 18:02 18:18 18:34 18:50 " +
                "19:06 19:22 19:38 19:54 20:10 20:26 20:42 20:58 21:14 21:30 21:50 22:10 22:30",

        // ---- 良乡大学城北站（地铁站）发 ----
        (Stop.METRO to DayType.WORKDAY) to
                "7:38 7:54 8:10 8:26 8:42 8:58 9:14 9:30 11:40 12:00 12:20 12:40 13:00 13:20 " +
                "15:38 15:54 16:10 16:26 16:42 16:58 17:14 17:54 18:10 18:26 18:42 18:58 " +
                "19:14 19:30 19:46 20:02 20:18 20:34 20:50 21:06 21:22 21:40 22:00 22:20 22:40",

        (Stop.METRO to DayType.WEEKEND_HOLIDAY) to
                "7:38 7:54 8:10 8:26 8:42 8:58 9:14 9:30 9:46 10:00 10:20 10:40 11:00 " +
                "11:40 12:00 12:20 12:40 13:00 13:20 13:40 14:00 14:20 14:40 15:00 15:20 " +
                "15:38 15:54 16:10 16:26 16:42 16:58 17:14 17:54 18:10 18:26 18:42 18:58 " +
                "19:14 19:30 19:46 20:02 20:18 20:34 20:50 21:06 21:22 21:40 22:00 22:20 22:40",
    )

    /** 解析后的时刻表（惰性、只算一次）。 */
    private val TIMES: Map<Pair<Stop, DayType>, List<LocalTime>> by lazy {
        RAW.mapValues { (_, raw) -> parseTimes(raw) }
    }

    /** 把 `"7:30 7:46 …"` 解析成时刻列表（不做排序，**保持站牌上的原始顺序**便于核对）。 */
    private fun parseTimes(raw: String): List<LocalTime> = raw.trim().split(Regex("\\s+")).map { token ->
        val (h, m) = token.split(':')
        LocalTime.of(h.toInt(), m.toInt())
    }

    /** 某站点、某类日子的完整时刻表。 */
    fun timetable(stop: Stop, dayType: DayType): List<LocalTime> = TIMES.getValue(stop to dayType)

    // ------------------------------------------------------------ 推算

    /**
     * 推算**下一班**。
     *
     * @param now 当前时刻（只用到 `HH:mm`，秒会被忽略 —— 站牌精度就是分钟）
     * @return 下一班；**今天已经发完最后一班**时返回 `null`（调用方据此说"今天的车发完了"）。
     *   早于首班时返回首班（`inMinutes` 就是到首班的分钟数）。
     */
    fun nextDeparture(stop: Stop, dayType: DayType, now: LocalTime): Departure? {
        val list = timetable(stop, dayType)
        // 用「当天第几分钟」比较，避免 LocalTime 跨零点比较的坑（这里不会跨零点，但统一口径更稳）
        val nowMin = now.hour * 60 + now.minute
        val idx = list.indexOfFirst { it.hour * 60 + it.minute >= nowMin }
        if (idx < 0) return null
        val next = list[idx]
        return Departure(
            at = next,
            inMinutes = ((next.hour * 60 + next.minute) - nowMin).toLong(),
            remaining = list.size - idx,
            sequence = idx + 1,
        )
    }

    /** 从 [now] 起今天还剩的所有班次（含恰好等于 [now] 的那一班）；已发完则为空。 */
    fun remainingToday(stop: Stop, dayType: DayType, now: LocalTime): List<LocalTime> {
        val nowMin = now.hour * 60 + now.minute
        return timetable(stop, dayType).filter { it.hour * 60 + it.minute >= nowMin }
    }

    /**
     * 按**星期**给一个默认的日类型。
     *
     * ⚠️ **只管周六周日** —— 法定节假日、调休补班、寒暑假调整都判断不了
     * （站牌上也写着「长假期发车时间可能调整，请关注 i北理 信息」）。
     * 所以页面必须允许用户**手动覆盖**这个默认值。
     */
    fun dayTypeOf(date: LocalDate): DayType =
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            DayType.WEEKEND_HOLIDAY
        } else {
            DayType.WORKDAY
        }

    /** `还有 N 分钟` 的人话说法（`Departure.inMinutes` 的展示用，纯函数便于单测）。 */
    fun countdownText(inMinutes: Long): String = when {
        inMinutes <= 0L -> "即将发车"
        inMinutes < 60L -> "$inMinutes 分钟后"
        else -> "${inMinutes / 60} 小时 ${inMinutes % 60} 分钟后"
    }
}
