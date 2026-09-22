package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDateTime

/**
 * 到点发一条提醒。
 *
 * ⚠️ **必须二次校验**：排期到执行之间，用户可能删了课、把 DDL 标成已完成、
 * 或把 DDL 改期。所以这里重新读一次当前数据（[NotifyRepository.stillValid]），
 * 仍然成立才发。
 *
 * 失败一律返回 [Result.success]：提醒这种「尽力而为」的事不值得重试
 * —— 重试只会在几分钟后再弹一次迟到的提醒。
 */
class NotifyFireWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_KEY) ?: return Result.success()
        val kind = inputData.getString(KEY_KIND)
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
            ?: return Result.success()

        val reminder = Reminder(
            key = key,
            kind = kind,
            at = inputData.getString(KEY_AT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?: LocalDateTime.now(),
            title = inputData.getString(KEY_TITLE).orEmpty(),
            text = inputData.getString(KEY_TEXT).orEmpty(),
            route = inputData.getString(KEY_ROUTE).orEmpty(),
        )

        val repository = NotifyRepositoryHolder.ensureRepository(applicationContext)
            ?: return Result.success()

        val valid = runCatching { repository.stillValid(reminder) }.getOrDefault(false)
        if (!valid) return Result.success()

        // 先记录再发：宁可漏发一次，也不要因为发失败而反复重试造成重复提醒
        NotifySentStore.mark(key)
        NotifyCenter.notify(applicationContext, reminder)
        return Result.success()
    }

    companion object {
        const val KEY_KEY = "notify_key"
        const val KEY_KIND = "notify_kind"
        const val KEY_TITLE = "notify_title"
        const val KEY_TEXT = "notify_text"
        const val KEY_ROUTE = "notify_route"
        const val KEY_AT = "notify_at"
    }
}
