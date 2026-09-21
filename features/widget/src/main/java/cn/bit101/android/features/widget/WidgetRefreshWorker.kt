package cn.bit101.android.features.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 小组件的兜底刷新。
 *
 * **为什么需要它**：组件只在两种情况下会重绘 ——
 * 1. App 内数据变更时主动调 [WidgetUpdater.refresh]（最快，但要求用户打开过 App）
 * 2. 用户点组件上的「刷新」键（只在人看着屏幕时有效）
 *
 * 两者都不足以覆盖「用户几天没打开 App，但桌面还想看到今天有什么课」这个场景。
 * 于是用 WorkManager 补一个周期任务，主要目的是**跨零点换天**。
 *
 * ⚠️ `updatePeriodMillis` 被设为 **0**（关闭系统自带的周期更新）：它的下限也是 30 分钟
 * 且不精确，交给 WorkManager 更可控，也避免两套周期机制同时唤醒设备。
 *
 * ⚠️ WorkManager 最小周期是 **15 分钟**，实际执行时间由系统调度。
 * 所以这里**不做准点刷新**，只保证一天之内总会更新一次。
 *
 * ⚠️ 刻意**不用 `@HiltWorker`**：那要求 Application 实现 `Configuration.Provider`
 * 并改用 `HiltWorkerFactory`，会侵入 App 启动流程；而这里只需要一个仓库实例，
 * 从 [WidgetRepositoryHolder] 取即可（App 启动时已装好）。
 * 少一处全局改动，也少一个「忘了改配置就静默失效」的坑。
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 失败不重试：组件数据陈旧不是错误，下次周期到了自然会刷新。
        // 返回 retry 会让 WorkManager 在退避期内反复唤醒设备，得不偿失。
        //
        // 用 renderAllNow（而不是异步的 requestRenderAll）：
        // 在 Worker 自己的协程里同步渲染完再返回，避免 doWork 返回后协程被收走、
        // 渲染半途夭折。单次渲染是「读一次 Room + 构建布局 + 下发」，百毫秒级。
        runCatching { BIT101WidgetProvider.renderAllNow(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "bit101_widget_refresh"

        /**
         * 排入周期任务。重复调用安全（[ExistingPeriodicWorkPolicy.KEEP] ——
         * 已有任务则不重建，避免每次 App 启动都重置调度周期）。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                // 30 分钟：比最低的 15 分钟略宽松，够换天用又不过度唤醒
                30, TimeUnit.MINUTES,
            )
                .setConstraints(
                    // 组件只读本地 Room，**不设网络约束** ——
                    // 否则飞行模式下整天不刷新，连「今天有没有课」都看不到
                    Constraints.Builder().build()
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /** 取消周期任务（组件被移除或用户关闭该功能时）。 */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
        }
    }
}

/**
 * App 内部触发组件刷新的入口。
 *
 * 调用时机：课表同步完成、DDL 同步完成、座位预约状态变化。
 * 都由业务侧**显式调用**，不做全局数据库监听 ——
 * 监听需要在每次写入后重绘组件，而重绘要跨进程通信，开销明显大于收益。
 */
object WidgetUpdater {

    /**
     * 刷新所有组件实例。
     *
     * 走 [BIT101WidgetProvider.requestRenderAll]：读一次本地数据 → 构建 RemoteViews →
     * `AppWidgetManager.updateAppWidget()`，同步下发。这里不等待完成，调用方不必是协程。
     */
    fun refresh(context: Context) {
        runCatching { BIT101WidgetProvider.requestRenderAll(context) }
    }
}
