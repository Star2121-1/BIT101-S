package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 提醒的排期器。
 *
 * 每条提醒 = 一个**一次性** WorkManager 任务（唯一名 = 去重键）。
 * 另有 [PERIODIC_WORK] 周期性重排，保证「用户长期不开 App」时提醒仍会续上。
 */
internal object NotifyScheduler {

    /** 一次性提醒任务的唯一名前缀。 */
    private const val UNIQUE_PREFIX = "notify:"

    /** 周期性重排任务名。 */
    private const val PERIODIC_WORK = "notify:refresh"

    /** 所有提醒任务的 tag，便于整体取消。 */
    const val TAG = "bit101_notify"

    /**
     * 提前 [SCHEDULE_LEAD_SECONDS] 秒排期。
     *
     * WorkManager 不保证准点（Doze / 省电策略会延后），提前一点排能让它
     * 更接近目标时刻。通知文案里写的是**绝对时间**，所以早到一点也无害。
     */
    private const val SCHEDULE_LEAD_SECONDS = 60L

    /** 排一批提醒（已存在的同键任务会被替换）。 */
    fun schedule(context: Context, reminders: List<Reminder>, now: LocalDateTime = LocalDateTime.now()) {
        if (reminders.isEmpty()) return
        val manager = WorkManager.getInstance(context)

        reminders.forEach { reminder ->
            val delay = Duration.between(now, reminder.at).seconds - SCHEDULE_LEAD_SECONDS
            val request = OneTimeWorkRequestBuilder<NotifyFireWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.SECONDS)
                .setInputData(reminder.toWorkData())
                .addTag(TAG)
                .build()
            manager.enqueueUniqueWork(
                "$UNIQUE_PREFIX${reminder.key}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /**
     * 清掉所有已排提醒。
     *
     * 用户关掉提醒总开关、或数据源变化（课表重同步）时调用，
     * 否则旧任务到点仍会弹出来。
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    /**
     * 周期性重排（默认 1 天一次）。
     *
     * ⚠️ 为什么需要它：排期只覆盖未来 7 天，用户若一周不开 App，
     * 第 8 天起就没有提醒了。周期任务在后台补上。
     * 用 [Constraints] 要求有网没必要 —— 数据都在本地，纯读 Room。
     */
    fun ensurePeriodicReschedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotifyRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.NONE)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 把 [Reminder] 装进 worker 的输入（WorkManager 的 Data 只支持基本类型）。 */
    private fun Reminder.toWorkData() = workDataOf(
        NotifyFireWorker.KEY_KEY to key,
        NotifyFireWorker.KEY_KIND to kind.name,
        NotifyFireWorker.KEY_TITLE to title,
        NotifyFireWorker.KEY_TEXT to text,
        NotifyFireWorker.KEY_ROUTE to route,
        NotifyFireWorker.KEY_AT to at.toString(),
    )
}
