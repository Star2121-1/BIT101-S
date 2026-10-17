package cn.bit101.android.features.notify

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 让 Worker 能拿到 [NotifyRepository]。
 *
 * WorkManager 自己实例化 Worker（不经过 Hilt 注入），所以：
 * - App 启动时由 [NotifyAppStartup] 把实例塞进来（快路径）
 * - 取不到时用 [NotifyEntryPoint] 现取一次（兜底，保证「App 没被打开过」也能工作）
 *
 * 两者都失败时返回 null，调用方静默跳过 —— **绝不抛异常**。
 */
internal object NotifyRepositoryHolder {

    @Volatile
    private var repository: NotifyRepository? = null

    fun install(repo: NotifyRepository) {
        repository = repo
    }

    fun repositoryOrNull(): NotifyRepository? = repository

    fun ensureRepository(context: Context): NotifyRepository? {
        repository?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotifyEntryPoint::class.java,
            ).notifyRepository()
        }.getOrNull()?.also { install(it) }
    }
}

/**
 * 提醒总开关的即时判断（fail-closed）。
 *
 * 供**不走排期**的即时提醒（网费 / 流量 / 借阅）使用 —— 它们由「前台刚取到数据」
 * 直接触发（见 `CampusServiceViewModel`），不经过 `NotifyRepository.policyOrNull()`
 * 所在的总开关闸门，必须在发送前自己判一次（与 [ScoreNotifyChecker] 同一模式）。
 * 拿不到仓库 / 读取失败一律视为「关」：宁可漏一条，也不要违背用户的关闭意图。
 */
internal suspend fun notificationsEnabled(context: Context): Boolean =
    NotifyRepositoryHolder.ensureRepository(context)?.enabled() ?: false

/** Hilt 入口点：取提醒仓库（非注入场景的标准解法）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyEntryPoint {
    fun notifyRepository(): NotifyRepository

    /** 出分检查用的成绩仓库（绑定在 data 模块，见 `RepoModule.bindScoreRepo`）。 */
    fun scoreRepo(): cn.bit101.android.data.repo.base.ScoreRepo
}
