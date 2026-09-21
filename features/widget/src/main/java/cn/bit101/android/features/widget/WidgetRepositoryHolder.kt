package cn.bit101.android.features.widget

import android.content.Context
import dagger.hilt.android.EntryPointAccessors

/**
 * 让 Provider / Worker 能拿到 [WidgetRepository]。
 *
 * `AppWidgetProvider` 由**系统**实例化，不经过 Hilt 注入，所以：
 * - App 启动时由 [WidgetAppStartup] 把实例塞进来（快路径）
 * - 取不到时用 [WidgetEntryPoint] 现取一次（兜底，保证不依赖"App 是否被打开过"）
 *
 * 两者都失败时返回 null，由调用方走空态，**绝不抛异常**。
 */
internal object WidgetRepositoryHolder {

    @Volatile
    private var repository: WidgetRepository? = null

    fun install(repo: WidgetRepository) {
        repository = repo
    }

    fun repositoryOrNull(): WidgetRepository? = repository

    fun ensureRepository(context: Context): WidgetRepository? {
        repository?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            ).widgetRepository()
        }.getOrNull()?.also { install(it) }
    }

    /**
     * 取数据。取不到时返回「加载中…」的空数据 —— 组件显示空态即可，
     * **绝不抛异常**（异常会让组件渲染失败、显示成一片空白，且用户看不到任何提示）。
     */
    suspend fun load(context: Context): WidgetData =
        runCatching { ensureRepository(context)?.load() }.getOrNull() ?: loadingWidgetData()

    private fun loadingWidgetData() = WidgetData(
        pages = PageKind.entries.map { kind ->
            WidgetPage(kind = kind, title = kind.label, primary = null, emptyText = "加载中…")
        }
    )
}
