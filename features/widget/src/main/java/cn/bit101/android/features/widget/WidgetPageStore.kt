package cn.bit101.android.features.widget

import android.content.Context

/**
 * 组件当前显示哪一页。
 *
 * ## ⚠️ 为什么存「页名」而不是「第几页」（2026-09-23 改）
 *
 * 原来存的是**索引**（int）。这在页序被调整时会**静默错页**：用户原本停在「座位」页
 * （旧索引 2），重排成 课程/DDL/动态/座位 之后，索引 2 变成了「动态」——
 * 没有任何报错，用户只是发现「组件自己跳页了」。
 *
 * 现在存 `PageKind.name`（如 `"SEAT"`），页序怎么调都不会串；
 * 读到**旧格式的 int** 时按 [LEGACY_ORDER]（当时的枚举顺序）还原语义、
 * 并顺手写回新格式 —— **幂等**，重复读不会越迁越偏。
 *
 * 每个组件实例各存一份 —— 桌面上可以同时放多个 BIT101 组件。
 */
internal object WidgetPageStore {

    private const val PREFS = "bit101_widget_page"

    /**
     * 旧版本（v1.7.5 及以前）的页序：索引就是这张表的下标。
     *
     * ⚠️ **这张表不能再改** —— 它是历史页号的含义，改了就会把老用户迁到别的页。
     * 新页序由 `PageKind` 枚举本身决定，与这里无关。
     */
    private val LEGACY_ORDER = listOf(
        PageKind.COURSE,
        PageKind.DDL,
        PageKind.SEAT,
        PageKind.ACTIVITY,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 老格式（int 页号）→ 页；越界返回 null。 */
    fun legacyKindOf(index: Int): PageKind? = LEGACY_ORDER.getOrNull(index)

    /** 读当前页；没有记录、认不出都落到第一页（课程页）。 */
    fun read(context: Context, appWidgetId: Int): PageKind {
        val prefs = prefs(context)
        val raw = prefs.all[key(appWidgetId)]

        val kind = when (raw) {
            // 新格式：页名
            is String -> PageKind.entries.firstOrNull { it.name == raw }
            // 老格式：索引（升级时走这里一次）
            is Int -> legacyKindOf(raw)
            else -> null
        } ?: PageKind.COURSE

        // 老格式顺手写回新格式：迁移是幂等的，下次就读到 String 了
        if (raw !is String) {
            prefs.edit().putString(key(appWidgetId), kind.name).apply()
        }
        return kind
    }

    fun write(context: Context, appWidgetId: Int, kind: PageKind) {
        // apply()：内存值立刻可见，磁盘异步写，不阻塞广播回调
        prefs(context).edit().putString(key(appWidgetId), kind.name).apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "page_$appWidgetId"
}
