package cn.bit101.android.features.widget

import android.content.Context

/**
 * 「现在去哪儿自习」→ 小组件的**单向数据桥**。
 *
 * ## 为什么需要它
 *
 * ⚠️ **组件重绘不打网络**（这是铁律），而自习建议要逐栋拉空教室 —— 重绘时去拉就违规了。
 * 所以真正取数只在「周期任务 / 用户点刷新」时做，结果**写进这里**，重绘时只读。
 *
 * 与 [SeatWidgetSnapshot] 同样的取舍：用 SharedPreferences 而不是 DataStore，
 * 因为 [WidgetViews] 的渲染发生在广播回调里，需要**同步**取数。
 *
 * ⚠️ 只存**一句已经粗化好的文案**（不含数字）—— 数字是几小时前的，写上去就是在骗人，
 * 所以粗化在写入前做完，这里不存结构化数据。
 *
 * ⚠️ **只存「哪栋楼有空教室」这一半**（需要网络的那半）。
 *    「正在上什么」在本地就能算，由 `WidgetLogic.studyCourseLine` 每次重绘现算 ——
 *    存快照会让它变成几小时后的一句假话。存的内容**语义**变了就要 +1 [SCHEMA]。
 */
object StudySpotSnapshot {

    private const val PREFS = "study_spot_snapshot"
    private const val KEY_LINE = "line"
    private const val KEY_AT = "written_at"
    private const val KEY_SCHEMA = "schema"

    /**
     * 快照**内容语义**的版本。
     *
     * ⚠️ **改「这里存的是什么意思」时必须 +1**，否则老版本写下的内容会被新版本
     * 当成另一种意思用。真机上栽过：v1.10.0 之前 `coarseTitle` 在上课时存的是
     * 「正在上操作系统（文萃楼I404）」，之后改成「哪栋楼有空教室」，而快照没换 →
     * 新版把旧内容当空教室那句话显示，**自习页同一句话出现两遍**。
     * 版本对不上就当作「没有」，等周期任务重写（≤30 分钟）。
     *
     * 1 = 存 `coarseTitle`（可能含「正在上 X」）
     * 2 = **只存「哪栋楼有空教室」**（课程那句改为组件每次现算）
     */
    internal const val SCHEMA = 2

    /** 快照能用吗：**格式对得上 + 还没过期**。纯函数，便于单测。 */
    fun isUsable(storedSchema: Int, writtenAtMillis: Long, nowMillis: Long): Boolean =
        storedSchema == SCHEMA && isFresh(writtenAtMillis, nowMillis)

    /**
     * 快照多久之后就**不再采信**。
     *
     * 组件真刷数据靠 30 分钟的周期任务，这里给 45 分钟（1.5 倍）留出 WorkManager 的抖动。
     * 超过就当作「不知道」—— 宁可这一页空着，也不说一句可能是几小时前的
     * 「XX现在有空教室」（Doze 会把周期任务拖很久）。
     */
    const val FRESH_MS = 45 * 60_000L

    /** 这份快照还新鲜吗（纯函数，便于单测）。 */
    fun isFresh(writtenAtMillis: Long, nowMillis: Long): Boolean =
        writtenAtMillis > 0 &&
            nowMillis >= writtenAtMillis &&
            nowMillis - writtenAtMillis <= FRESH_MS

    /** 写入一句建议文案（null = 取不到，清掉上一句）。 */
    fun write(context: Context, line: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (line.isNullOrBlank()) {
                    remove(KEY_LINE)
                    remove(KEY_AT)
                    remove(KEY_SCHEMA)
                } else {
                    putString(KEY_LINE, line)
                    putLong(KEY_AT, System.currentTimeMillis())
                    putInt(KEY_SCHEMA, SCHEMA)
                }
            }
            .apply()
    }

    /**
     * 同步读（渲染时调用）。
     *
     * ⚠️ 三种情况都返回 `null`（当作「没有」）：
     * 1. **过期的** —— 「现在有空教室」是**有时效的断言**，几小时前的快照说「现在」就是在骗人
     * 2. **格式对不上的**（旧版本写的）—— 见 [SCHEMA]
     * 3. 从来没写过
     *
     * @param nowMillis 便于单测注入；正常调用不用传。
     */
    fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): String? =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val schema = prefs.getInt(KEY_SCHEMA, 0)
            if (!isUsable(schema, writtenAt(context), nowMillis)) return@runCatching null
            prefs.getString(KEY_LINE, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 上次写入的时刻，供排障与「数据多久前的」判断。 */
    fun writtenAt(context: Context): Long =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        }.getOrDefault(0L)
}
