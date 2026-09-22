package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 周期性重排（见 [NotifyScheduler.ensurePeriodicReschedule]）。
 *
 * 存在的意义只有一个：排期只覆盖未来 7 天，用户一周不开 App 时靠它把
 * 后面的提醒续上。
 *
 * 取不到数据源时**不重试**：下一次周期到了自然会再来。
 */
class NotifyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NotifySentStore.init(applicationContext)
        NotifySentStore.prune()
        NotifyCenter.ensureChannels(applicationContext)

        val repository = NotifyRepositoryHolder.ensureRepository(applicationContext)
            ?: return Result.success()

        runCatching {
            if (!repository.enabled()) {
                NotifyScheduler.cancelAll(applicationContext)
                return@runCatching
            }
            val reminders = repository.plan()
            NotifyScheduler.schedule(applicationContext, reminders)
        }
        return Result.success()
    }
}
