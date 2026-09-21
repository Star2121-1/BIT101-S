package cn.bit101.android.features.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 可滚动列表的数据源。
 *
 * ## 为什么需要它
 *
 * RemoteViews **不支持** ScrollView —— 想在桌面组件里滚动，只能走
 * 「collection 组件（ListView / StackView …）+ RemoteViewsService」这套机制：
 * 组件里放一个 `ListView`，系统跨进程向本服务索取条目视图。
 *
 * ## 两个必须知道的点
 *
 * 1. **服务跑在我们自己的进程里**（不是桌面进程），所以能直接用
 *    [WidgetRepositoryHolder] 读库 —— 不需要把数据序列化后塞进 Intent。
 * 2. **`onDataSetChanged()` 在 binder 线程执行**，可以安全地做阻塞 IO。
 *    这里用 `runBlocking` 等协程取数：数据本来就是 suspend 的（Room），
 *    而 factory 的接口是同步的，只能在边界上等一次。
 *
 * ## 刷新链路
 *
 * `BIT101WidgetProvider.render()` 下发 RemoteViews 后还会调
 * `AppWidgetManager.notifyAppWidgetViewDataChanged()` —— 否则 ListView 会
 * 继续用缓存的旧条目，出现「页签切了但内容没变」。
 */
class WidgetListService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent)

    private class Factory(
        private val context: Context,
        intent: Intent,
    ) : RemoteViewsService.RemoteViewsFactory {

        private val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )

        private var items: List<WidgetLine> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            items = runCatching {
                runBlocking(Dispatchers.IO) {
                    val data = WidgetRepositoryHolder.load(context)
                    val index = WidgetPageStore.read(context, appWidgetId)
                        .coerceIn(0, (data.pages.size - 1).coerceAtLeast(0))
                    data.pages.getOrNull(index)?.items.orEmpty()
                }
            }.getOrDefault(emptyList())
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews =
            WidgetViews.buildItem(
                context,
                items.getOrNull(position) ?: WidgetLine(lead = "", main = ""),
            )

        /**
         * 加载中的占位。返回 null 表示用条目的默认布局（见 widget_item.xml）——
         * 我们的加载只有几十毫秒，专门做一个骨架图反而会让屏幕闪一下。
         */
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = true
    }
}
