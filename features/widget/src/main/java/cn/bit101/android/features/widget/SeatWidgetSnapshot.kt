package cn.bit101.android.features.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * 座位模块 → 小组件的**单向数据桥**。
 *
 * 设计要点（为什么不做成双向依赖）：
 * - `features:widget` **不依赖** `features:seat` —— 否则座位模块的任何改动都会
 *   牵动组件，且组件进程拉起时会连带初始化座位模块的重依赖（OkHttp / Hilt 图）。
 * - 反过来让 `features:seat` 在状态变化时**写入这份快照**，组件只读。
 *   座位模块可选地调用，组件在没有座位模块时也能正常显示另外两页。
 *
 * 存储用普通 SharedPreferences 而非 DataStore：[WidgetViews] 的渲染发生在
 * `AppWidgetProvider` 的广播回调里，需要**同步**取数；SharedPreferences 读已有缓存、
 * 毫秒级返回，而 DataStore 是挂起的，会把渲染拖成一个异步流程。
 * 这里的数据也不含敏感信息 —— 只有座位号与时间。
 */
object SeatWidgetSnapshot {

    private const val PREFS = "seat_widget_snapshot"
    private const val KEY_LINES = "lines"
    private const val KEY_UPDATED = "updated_at"

    /** 行内字段分隔符（座位号/时间里不会出现） */
    private const val FIELD_SEP = "\u0001"

    /** 行分隔符 */
    private const val ROW_SEP = "\u0002"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 座位模块在状态变化时调用，把要显示的行写进来。 */
    fun write(context: Context, lines: List<WidgetLine>) {
        val encoded = lines.joinToString(ROW_SEP) { line ->
            listOf(line.lead, line.main, line.trail, if (line.urgent) "1" else "0")
                .joinToString(FIELD_SEP)
        }
        prefs(context).edit()
            .putString(KEY_LINES, encoded)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** 座位模块清空状态时调用（例如登出）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 小组件读取。数据损坏或格式不对时返回空表，**不抛异常** —— 组件崩了会显示空白卡片。 */
    fun read(context: Context): List<WidgetLine> {
        val raw = prefs(context).getString(KEY_LINES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        return raw.split(ROW_SEP).mapNotNull { row ->
            val parts = row.split(FIELD_SEP)
            // 字段数不对说明是老版本写入的，跳过该行而不是整份丢弃
            if (parts.size < 3) return@mapNotNull null
            WidgetLine(
                lead = parts[0],
                main = parts[1],
                trail = parts[2],
                urgent = parts.size > 3 && parts[3] == "1",
            )
        }
    }

    /** 快照的最后写入时间。用于判断数据是否陈旧（0 = 从未写入）。 */
    fun updatedAt(context: Context): Long =
        prefs(context).getLong(KEY_UPDATED, 0L)
}
