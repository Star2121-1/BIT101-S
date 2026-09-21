package cn.bit101.android.features.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BIT101 桌面小组件的宿主。
 *
 * 组件显示三页（课程 / DDL / 座位），点击顶部页签直接切换，右侧「刷新」键重读本地数据。
 *
 * ## 渲染方式
 *
 * 在回调里读数据 → 构建 [android.widget.RemoteViews] →
 * `AppWidgetManager.updateAppWidget()`。**这是同步 API，调用即生效**。
 *
 * 之所以不用 Glance：实测它的 `update()` 只发一个事件给异步的 Session 事件循环，
 * 对已存在的 Session 不保证重新执行 `provideGlance`，导致「点了没反应」。
 * 详见 [WidgetViews] 的类注释。
 *
 * ## 点击为什么必须走 broadcast 而不是直接改 view
 *
 * RemoteViews 是跨进程的只读快照，宿主（桌面）只执行我们下发的操作。
 * 所以点击只能通过 `setOnClickPendingIntent` → 广播回本进程 → 重新构建整张 RemoteViews。
 */
class BIT101WidgetProvider : AppWidgetProvider() {

    /** 新增组件、尺寸变化、系统请求更新。 */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // onUpdate 是同步回调，拿不到 goAsync 的 PendingResult，
        // 所以用一个与 App 同生命周期的作用域渲染。
        // 单次渲染只是「读一次 Room + 构建布局 + 下发」，耗时在百毫秒级，来得及。
        appWidgetIds.forEach { requestRender(context, it) }
    }

    /** 用户拖动改变了组件尺寸 —— 宽高变了要重新排版。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        requestRender(context, appWidgetId)
    }

    /** 组件被移除 —— 顺手清掉它那份页号，避免残留数据无限增长。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPageStore.clear(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (action != ACTION_SET_PAGE && action != ACTION_REFRESH) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // ⚠️ 用 goAsync 把广播的存活窗口握在手里：
        // 直接在 onReceive 里同步跑会阻塞主线程，而 spawn 一个不管的协程又可能在
        // 广播结束后被系统连同进程优先级一起收走（这正是 Glance 那版失败的原因之一）。
        val pendingResult = goAsync()
        appScope.launch {
            try {
                if (action == ACTION_SET_PAGE) {
                    WidgetPageStore.write(
                        context,
                        appWidgetId,
                        intent.getIntExtra(EXTRA_PAGE, 0),
                    )
                }
                render(context.applicationContext, appWidgetId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {

        const val ACTION_SET_PAGE = "cn.bit101.android.features.widget.SET_PAGE"
        const val ACTION_REFRESH = "cn.bit101.android.features.widget.REFRESH"
        const val EXTRA_PAGE = "bit101_page"

        /**
         * 渲染单个实例。
         *
         * 与 App 同生命周期 —— 不取消：这里的工作都是幂等的单次渲染，
         * 中途取消只会让组件停在旧内容上。
         */
        private val appScope by lazy {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        fun requestRender(context: Context, appWidgetId: Int) {
            val appContext = context.applicationContext
            appScope.launch { render(appContext, appWidgetId) }
        }

        /** 渲染所有实例（App 内数据变更后调用，不等待完成）。 */
        fun requestRenderAll(context: Context) {
            val appContext = context.applicationContext
            appScope.launch {
                appWidgetIds(appContext).forEach { render(appContext, it) }
            }
        }

        /**
         * 同步渲染所有实例，**返回时已全部下发完毕**。
         *
         * 供 [WidgetRefreshWorker] 在自己的协程里调用 ——
         * Worker 用异步版的话，`doWork` 一返回协程就可能被收走，渲染半途而废。
         */
        suspend fun renderAllNow(context: Context) {
            val appContext = context.applicationContext
            appWidgetIds(appContext).forEach { render(appContext, it) }
        }

        private suspend fun render(context: Context, appWidgetId: Int) {
            // 行数随组件高度变：数据与布局用同一个 limit，避免「取 3 行画 2 行」
            val limit = WidgetViews.rowsForHeight(context, appWidgetId)
            val data = WidgetRepositoryHolder.load(context, limit)
            val remoteViews = WidgetViews.build(context, appWidgetId, data, rowLimit = limit)
            AppWidgetManager.getInstance(context)
                .updateAppWidget(appWidgetId, remoteViews)
        }

        private fun appWidgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, BIT101WidgetProvider::class.java)
            )
    }
}
