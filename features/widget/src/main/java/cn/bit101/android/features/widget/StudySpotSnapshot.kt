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
 */
object StudySpotSnapshot {

    private const val PREFS = "study_spot_snapshot"
    private const val KEY_LINE = "line"
    private const val KEY_AT = "written_at"

    /** 写入一句建议文案（null = 取不到，清掉上一句）。 */
    fun write(context: Context, line: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (line.isNullOrBlank()) {
                    remove(KEY_LINE)
                    remove(KEY_AT)
                } else {
                    putString(KEY_LINE, line)
                    putLong(KEY_AT, System.currentTimeMillis())
                }
            }
            .apply()
    }

    /** 同步读（渲染时调用）。取不到返回 null。 */
    fun read(context: Context): String? =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LINE, null)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 上次写入的时刻，供排障与「数据多久前的」判断。 */
    fun writtenAt(context: Context): Long =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        }.getOrDefault(0L)
}
