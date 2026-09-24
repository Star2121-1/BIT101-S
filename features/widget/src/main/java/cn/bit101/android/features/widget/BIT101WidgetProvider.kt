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
 * 组件显示四页（**课程 / DDL / 座位 / 动态**），点击顶部页签直接切换，
 * 右侧「刷新」键重读本地数据。
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
 *
 * ## ⚠️ 安全边界：接收器是 `exported="false"`
 *
 * 自定义 action（切页签 / 刷新 / 一键预约 / 勾选 DDL）都是**写操作**，
 * 所以特意确认过暴露面 —— `AndroidManifest.xml` 里这个 receiver 没有任何
 * `intent-filter` 之外的开放理由，声明为 `exported="false"`：
 *
 * - 第三方 App / `adb shell am broadcast` **发不进来**（2026-09-23 实测：
 *   广播只到 `ActivityManager: Enqueued broadcast`，接收器完全没被调用）
 * - 系统与桌面仍可投递（系统 uid 不受 exported 限制）
 * - 我们自己的点击由 `PendingIntent` 代发，发送方身份就是我们这个 uid
 *
 * 也就是说**不能**把它改成 `exported="true"` —— 那样任何人都能用一条
 * `am broadcast -a …QUICK_RESERVE` 触发一次真实预约。
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
        if (action != ACTION_SET_PAGE && action != ACTION_REFRESH &&
            action != ACTION_QUICK_RESERVE
        ) {
            return
        }

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
                when (action) {
                    ACTION_SET_PAGE -> {
                        // 页名优先。老版本创建的 PendingIntent 还在系统里缓存着时，
                        // 可能递来**旧格式的 int 页号**（升级瞬间的一次陈旧点击）——
                        // 按旧顺序还原，别让它静默变成「点了没反应」。
                        val kind = intent.getStringExtra(EXTRA_PAGE)
                            ?.let { name -> PageKind.entries.firstOrNull { it.name == name } }
                            ?: intent.getIntExtra(EXTRA_PAGE, -1)
                                .takeIf { it >= 0 }
                                ?.let { WidgetPageStore.legacyKindOf(it) }

                        if (kind != null) WidgetPageStore.write(context, appWidgetId, kind)
                    }

                    ACTION_QUICK_RESERVE -> quickReserve(context, appWidgetId, intent)

                    ACTION_REFRESH -> {
                        // 「刷新」键的语义是「真的去拿一遍最新数据」，所以两件事都要做：
                        // 1) 拉一次「我的预约」—— 座位状态（已预约/使用中/暂离）
                        //    只有拉了数据才会变（此前这里只装了 bridge、没真的拉，
                        //    注释说的和代码做的不一致）
                        SeatWidgetBridgeHolder.ensureBridge(context)
                        SeatWidgetBridgeHolder.refreshReservations()
                    }
                }
                // 只有显式刷新才强制重拉延河课堂动态（页签切换只是重画）
                render(context.applicationContext, appWidgetId, forceEclass = action == ACTION_REFRESH)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 「一键预约」：在后台对目标座位直接下单，结果写进快照的提示里（60 秒内可见）。
     *
     * ⚠️ 先写「正在预约…」再发请求：网络慢的时候用户点完没有任何反馈，
     * 会以为是按钮没反应而连点（confirmSeat 幂等，连点无害，但体验差）。
     */
    private suspend fun quickReserve(context: Context, appWidgetId: Int, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val appContext = context.applicationContext

        SeatWidgetSnapshot.writeNotice(appContext, "正在预约…")
        render(appContext, appWidgetId)

        val bridge = SeatWidgetBridgeHolder.ensureBridge(appContext)
        val result = if (bridge == null) {
            "请先打开 App 登录座位系统"
        } else {
            SeatWidgetBridgeHolder.invoke(taskId)
        }
        SeatWidgetSnapshot.writeNotice(
            appContext,
            result?.let { "预约失败：$it" } ?: "预约成功，请在时限内刷卡签到",
        )
    }

    companion object {

        const val ACTION_SET_PAGE = "cn.bit101.android.features.widget.SET_PAGE"
        const val ACTION_REFRESH = "cn.bit101.android.features.widget.REFRESH"
        const val ACTION_QUICK_RESERVE = "cn.bit101.android.features.widget.QUICK_RESERVE"
        const val EXTRA_PAGE = "bit101_page"
        const val EXTRA_TASK_ID = "bit101_task_id"

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
         * 同步渲染所有实例，**返回时已全部下发完毕**（Worker 内必须同步渲染，
         * 否则 `doWork` 返回后协程被收走、渲染半途而废）。
         *
         * @param forceEclass 周期刷新是「兜底拿最新」的场景，默认强制重拉动态。
         */
        suspend fun renderAllNow(context: Context, forceEclass: Boolean = true) {
            val appContext = context.applicationContext
            appWidgetIds(appContext).forEach { render(appContext, it, forceEclass) }
        }

        private suspend fun render(
            context: Context,
            appWidgetId: Int,
            forceEclass: Boolean = false,
        ) {
            val data = WidgetRepositoryHolder.load(context, forceEclass = forceEclass)
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId, data))
            // ⚠️ 必须显式通知列表数据变了：只 updateAppWidget 的话，ListView 会沿用
            // 缓存的旧条目，表现为「页签切了但内容还是上一页的」。
            manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }

        private fun appWidgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, BIT101WidgetProvider::class.java)
            )
    }
}
