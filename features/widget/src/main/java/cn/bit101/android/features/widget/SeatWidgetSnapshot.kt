package cn.bit101.android.features.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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
 * 这里的数据也不含敏感信息 —— 只有座位号、时间与状态文案。
 *
 * ⚠️ 序列化用 JSON：行模型（[WidgetLine]）字段会随版本增加，用分隔符拼字符串
 * 很快就要处理「字段数对不对」的兼容分支，JSON 能直接读缺省值。
 * 旧版写的分隔符格式读不出来，会返回空快照 —— 可以接受，App 下次推快照就会覆盖。
 */
object SeatWidgetSnapshot {

    private const val PREFS = "seat_widget_snapshot"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_UPDATED = "updated_at"

    /** 「一键预约」结果提示的有效期：过了就不显示，避免「预约成功」永远挂在组件上。 */
    const val NOTICE_TTL_MS = 60_000L

    /**
     * 组件要显示的座位页内容。
     *
     * @param lines 已按优先级排好序的显示行
     * @param quickReserveTaskId 非 null 时，组件底部按钮变成「一键预约」并对该任务下单
     * @param notice 上一次「一键预约」的结果文案（成功/失败原因），[noticeAt] 起 [NOTICE_TTL_MS] 内有效
     */
    data class Snapshot(
        val lines: List<WidgetLine> = emptyList(),
        val quickReserveTaskId: String? = null,
        val notice: String? = null,
        val noticeAt: Long = 0L,
    ) {
        /** 仍在有效期内的提示；过期或没有则返回 null。 */
        fun activeNotice(now: Long = System.currentTimeMillis()): String? =
            notice?.takeIf { it.isNotBlank() && now - noticeAt < NOTICE_TTL_MS }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 座位模块在状态变化时调用，把要显示的内容写进来。 */
    fun write(context: Context, snapshot: Snapshot) {
        val payload = JSONObject().apply {
            put("lines", JSONArray().apply {
                snapshot.lines.forEach { line -> put(line.toJson()) }
            })
            snapshot.quickReserveTaskId?.let { put("quickReserveTaskId", it) }
            snapshot.notice?.let { put("notice", it) }
            put("noticeAt", snapshot.noticeAt)
        }
        prefs(context).edit()
            .putString(KEY_PAYLOAD, payload.toString())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** 只更新提示（不改动行），用于「一键预约」后回写结果。 */
    fun writeNotice(context: Context, notice: String?) {
        val current = read(context)
        write(
            context,
            current.copy(
                notice = notice,
                noticeAt = if (notice == null) 0L else System.currentTimeMillis(),
            ),
        )
    }

    /** 座位模块清空状态时调用（例如登出）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 小组件读取。数据损坏或格式不对时返回空快照，**不抛异常** —— 组件崩了会显示空白卡片。 */
    fun read(context: Context): Snapshot {
        val raw = prefs(context).getString(KEY_PAYLOAD, null) ?: return Snapshot()
        if (raw.isBlank()) return Snapshot()

        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.toWidgetLine()
            }
            Snapshot(
                lines = lines,
                quickReserveTaskId = json.optString("quickReserveTaskId").takeIf { it.isNotBlank() },
                notice = json.optString("notice").takeIf { it.isNotBlank() },
                noticeAt = json.optLong("noticeAt", 0L),
            )
        }.getOrElse { Snapshot() }
    }

    /** 快照的最后写入时间。用于判断数据是否陈旧（0 = 从未写入）。 */
    fun updatedAt(context: Context): Long =
        prefs(context).getLong(KEY_UPDATED, 0L)

    private fun WidgetLine.toJson() = JSONObject().apply {
        put("lead", lead)
        put("main", main)
        put("trail", trail)
        put("time", time)
        put("urgent", urgent)
        put("muted", muted)
        highlight?.let { put("highlight", it.name) }
    }

    private fun JSONObject.toWidgetLine() = WidgetLine(
        lead = optString("lead"),
        main = optString("main"),
        trail = optString("trail"),
        time = optString("time"),
        urgent = optBoolean("urgent", false),
        muted = optBoolean("muted", false),
        highlight = optString("highlight").takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { ClassState.valueOf(name) }.getOrNull() },
    )
}
