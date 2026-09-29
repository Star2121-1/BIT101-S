package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDateTime

/**
 * 到点发一条提醒。
 *
 * ⚠️ **必须二次校验**：排期到执行之间，用户可能删了课、把 DDL 标成已完成、
 * 或把 DDL 改期。所以这里重新读一次当前数据（[NotifyRepository.refreshed]），
 * 仍然成立才发。
 *
 * ⚠️ **还要校正标题**：三类提醒的标题是**相对时间**（「10 分钟后上课」），
 * 而排期到执行之间系统可能延后很久（**实机迟到 2 小时 23 分钟**）。
 * [NotifyRepository.refreshed] 会按**实际剩余时间**重算标题，课已开始则返回 null
 * —— 否则会出现「下课之后才弹『10 分钟后上课』」（2026-09-29 用户截图实证）。
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

        // 二次校验 + 标题校正；null = 不该发（课已开始 / 已签到 / 已提交 / 数据没了）
        val ready = runCatching { repository.refreshed(reminder) }.getOrNull()
            ?: return Result.success()

        // 先记录再发：宁可漏发一次，也不要因为发失败而反复重试造成重复提醒
        NotifySentStore.mark(ready.key)
        NotifyCenter.notify(applicationContext, ready)
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
