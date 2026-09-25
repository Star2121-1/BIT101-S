package cn.bit101.android.features.notify

import android.content.Context
import androidx.core.content.edit
import cn.bit101.android.data.school.CampusNetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 校园网**流量阈值**提醒：本月用量到 270 GB 提醒一次，到 300 GB（限速阈值）提醒一次，
 * 之后不再打扰。
 *
 * ⚠️ 与 [NetFeeChecker] 的两点不同：
 * 1. **一次性**，不是按天 —— 每个值在一个计费周期里只发一条（用户明确要求「之后就算了」）；
 * 2. 换周期要**自动复位** —— 接口 `[6]` 是按计费周期累计（官方门户显示为「本月」），
 *    见到用量比上次小即说明重置了，清掉标记，下个月才会再提醒。
 */
object NetFlowChecker {

    /** 限速阈值：本月 300 GB 后限速。套餐不变就不动，故硬编码（与官方门户同一 1024 进制口径）。 */
    const val LIMIT_BYTES = 300L * 1024 * 1024 * 1024

    /** 提前提醒线：270 GB（限速阈值的 90%）。 */
    const val WARN_BYTES = 270L * 1024 * 1024 * 1024

    private const val PREF_NAME = "netflow_alert"
    private const val KEY_WARNED_NEAR = "warned_near"
    private const val KEY_WARNED_LIMIT = "warned_limit"
    private const val KEY_LAST_BYTES = "last_bytes"

    /** 提醒类型。 */
    enum class Alert { Near, Exceeded }

    /**
     * 该发哪种提醒（纯逻辑，单测覆盖）。
     *
     * 已越过 300 GB 时**不再补发 270 GB 那条** —— 两条一起弹是打扰，且 300 那条更准确。
     */
    fun decide(usedBytes: Long, warnedNear: Boolean, warnedLimit: Boolean): Alert? = when {
        usedBytes >= LIMIT_BYTES && !warnedLimit -> Alert.Exceeded
        usedBytes >= WARN_BYTES && !warnedNear -> Alert.Near
        else -> null
    }

    /**
     * 拿数据判一次。
     *
     * ⚠️ 与余额提醒同源：只有「刚成功取到数据」时才判（那正是人在校内的确证）；
     * 每日周期任务的触发时刻固定，校外会永远落空。
     */
    suspend fun check(context: Context, info: CampusNetInfo?) {
        if (info == null) return
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val used = info.bytesTotal

            val last = prefs.getLong(KEY_LAST_BYTES, -1L)
            if (last >= 0 && used < last) {
                // 计费周期重置：清掉上个周期的「已提醒」，新周期从头再来
                prefs.edit(commit = true) {
                    remove(KEY_WARNED_NEAR)
                    remove(KEY_WARNED_LIMIT)
                    putLong(KEY_LAST_BYTES, used)
                }
                return@withContext
            }
            prefs.edit(commit = true) { putLong(KEY_LAST_BYTES, used) }

            val alert = decide(
                used,
                prefs.getBoolean(KEY_WARNED_NEAR, false),
                prefs.getBoolean(KEY_WARNED_LIMIT, false),
            ) ?: return@withContext

            NotifyCenter.notifyNetFlow(context, alert, used)
            // commit=true（同步落盘）：用 apply 的异步写会被 force-stop 丢掉，
            // 标记一丢就会重复提醒 —— 而「只提醒一次」正是这个功能的核心承诺。
            // 这里已经在 IO 线程上，同步写一条小 XML 无代价。
            prefs.edit(commit = true) {
                putBoolean(KEY_WARNED_NEAR, true)
                if (alert == Alert.Exceeded) putBoolean(KEY_WARNED_LIMIT, true)
            }
        }
    }
}
