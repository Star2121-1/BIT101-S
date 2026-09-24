package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 提醒模块的启动接线（App 启动时调一次，见 `App.kt`）。
 *
 * 做的事：
 * 1. 初始化已发记录存储（同步读，排期要用）
 * 2. 建通知渠道（幂等）
 * 3. 清掉过期记录
 * 4. **重排未来 7 天的提醒** —— 课表/DDL 可能在 App 没开的时候变过
 * 5. 挂上周期性重排（兜底）
 *
 * 起不来只影响提醒，**不影响 App 主流程** —— 因此刻意吞掉异常（沿用座位模块的做法）。
 */
object NotifyAppStartup {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        runCatching {
            NotifySentStore.init(context)
            NotifyCenter.ensureChannels(context)
            NotifySentStore.prune()
            NetFeeCheckWorker.ensureScheduled(context)
        }

        runCatching {
            val repository = EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotifyEntryPoint::class.java,
            ).notifyRepository()
            NotifyRepositoryHolder.install(repository)

            appScope.launch {
                runCatching {
                    if (!repository.enabled()) {
                        NotifyScheduler.cancelAll(context)
                    } else {
                        NotifyScheduler.schedule(context, repository.plan())
                    }
                    NotifyScheduler.ensurePeriodicReschedule(context)
                    // 出分提醒不走排期（没有确定的未来时刻），启动时主动查一次。
                    // 首次运行只建基线、不发通知（见 ScoreLogic.diff）
                    ScoreNotifyChecker.checkAndNotify(context)
                }
            }
        }
    }

    /**
     * 数据变化后重排（课表同步完成、DDL 增删改后调用）。
     *
     * 与 [init] 分开是为了让调用方能在**任意线程**触发一次重排，
     * 不必关心它内部用哪个 scope。
     */
    fun reschedule(context: Context) {
        val repository = NotifyRepositoryHolder.ensureRepository(context)
        appScope.launch {
            runCatching {
                NotifyScheduler.cancelAll(context)
                if (repository?.enabled() == true) {
                    NotifyScheduler.schedule(context, repository.plan())
                }
                NotifyScheduler.ensurePeriodicReschedule(context)
            }
        }
    }

    /** 供测试/调试：清空已发记录并重排。 */
    fun resetAndReschedule(context: Context) {
        NotifySentStore.clear()
        reschedule(context)
    }

    /** 关掉提醒时清掉已排任务（设置页调用）。 */
    fun cancelAll(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(NotifyScheduler.TAG) }
    }
}
