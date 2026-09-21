package cn.bit101.android.features.widget

import android.content.Context

/**
 * 组件当前显示第几页。
 *
 * 用普通 SharedPreferences 而不是 Glance state：RemoteViews 方案下没有 Glance，
 * 而且这个存储本来就是"宿主进程读、App 进程写"的简单键值。
 *
 * 每个组件实例各存一份 —— 桌面上可以同时放多个 BIT101 组件。
 */
internal object WidgetPageStore {

    private const val PREFS = "bit101_widget_page"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(key(appWidgetId), 0)

    fun write(context: Context, appWidgetId: Int, page: Int) {
        // apply()：内存值立刻可见，磁盘异步写，不阻塞广播回调
        prefs(context).edit().putInt(key(appWidgetId), page).apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "page_$appWidgetId"
}
