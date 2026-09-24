package cn.bit101.android.features.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * 网费不足检查（每日一次）：校园网账户余额 < [NetFeeChecker.THRESHOLD_YUAN] 元时提醒。
 *
 * 数据源是深澜自助接口（仅校园网环境可达）—— 取不到就**静默跳过**：
 * 不在校内是常态，不是故障；下一次周期到了自然会再查。
 * 去重键按天（`netfee_low_yyyy-MM-dd`）：同一天只提醒一次，别刷屏。
 */
class NetFeeCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(
            applicationContext,
            NetFeeEntryPoint::class.java,
        ).campusNetRepo()

        NetFeeChecker.checkAndNotify(applicationContext) { repo.fetchOnlineInfo() }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "netfee_daily_check"

        /** App 启动时挂上每日检查（KEEP：已存在不覆盖）。 */
        fun ensureScheduled(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<NetFeeCheckWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
}

/** 余额检查与通知（抽出纯逻辑便于测试阈值判断）。 */
object NetFeeChecker {

    /** 低于该值（元）提醒充值。 */
    const val THRESHOLD_YUAN = 10.0

    /**
     * 查一次余额，低于阈值发通知（按天去重）。
     * [fetch] 由调用方注入 —— worker 里是真的仓库，测试里可以是假数据。
     */
    suspend fun checkAndNotify(context: Context, fetch: suspend () -> cn.bit101.android.data.school.CampusNetInfo?) {
        val info = runCatching { fetch() }.getOrNull() ?: return

        val today = java.time.LocalDate.now().toString()
        val key = "netfee_low_$today"
        if (info.balanceYuan >= THRESHOLD_YUAN) return
        if (NotifySentStore.contains(key)) return

        NotifyCenter.notifyNetFee(context, info.balanceYuan)
        NotifySentStore.mark(key)
    }
}

/** Worker 取数据源的 Hilt 入口。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface NetFeeEntryPoint {
    fun campusNetRepo(): cn.bit101.android.data.repo.base.CampusNetRepo
}
