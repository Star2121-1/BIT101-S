package cn.bit101.android.features.notify

import android.content.Context

/**
 * 出分提醒的编排：**拉成绩 → 差分 → 发通知**。
 *
 * ## 为什么不是一条「排期提醒」
 *
 * 上课/DDL/签到都有确定的未来时刻，可以精确排期；出分没有 ——
 * 它是「某天服务端突然多了几行」的事件。所以不走 WorkManager 一次性任务，
 * 而是在两个时机**主动检查**：
 * - App 启动（[NotifyAppStartup.init]）
 * - 每日周期任务（[NotifyRefreshWorker]）
 *
 * 检查本身很轻：一次 `/scores` 请求 + 本地文件对比，没有新成绩就什么都不发生。
 *
 * ## 隐私边界（用户明确要求）
 *
 * 通知里**只有课名、没有分数**。文案由 `ScoreLogic.summaryText` 生成
 * （那里有单测锁住这条），这里不做任何拼接。
 */
object ScoreNotifyChecker {

    /**
     * 检查一次并按需发通知。
     *
     * @return 本次通知了几门课（0 = 没有新课或被开关/错误拦下）
     */
    suspend fun checkAndNotify(context: Context, force: Boolean = false): Int {
        val repository = NotifyRepositoryHolder.ensureRepository(context) ?: return 0
        if (!runCatching { repository.scoreNotifyEnabled() }.getOrDefault(false)) return 0

        val scoreRepo = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotifyEntryPoint::class.java,
            ).scoreRepo()
        }.getOrNull() ?: return 0

        val newScores = runCatching { scoreRepo.syncAndDiff(force = force) }.getOrDefault(emptyList())
        if (newScores.isEmpty()) return 0

        val text = cn.bit101.android.data.score.ScoreLogic.summaryText(newScores)
        if (text.isBlank()) return 0

        NotifyCenter.notifyScores(context, text)
        return newScores.size
    }
}
