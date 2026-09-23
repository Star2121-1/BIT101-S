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
     *
     * ⚠️ 这个方法会被**两条路径**调用：
     * 1. `BIT101WidgetProvider.render()` —— 构建根视图（页签 / 底部说明 / 动作键 / 滚动位置）
     * 2. `WidgetListService` 的 `RemoteViewsFactory.onDataSetChanged()` —— 构建列表条目
     *
     * 两条路径各自读一遍数据，属于可接受的小额重复：把结果塞进 adapter 的 Intent
     * 传过去虽然省一次查询，但要让 [WidgetLine] 可序列化，且组件被系统持久化恢复时
     * 类名变更会导致反序列化失败。宁可多查一次。
     * （网络那一项已在 [WidgetRepository] 内部按 TTL 缓存，所以这里的重复不会重复请求。）
     *
     * @param forceEclass 无视缓存重新拉延河课堂动态；只有显式刷新路径才传 true。
     */
    suspend fun load(context: Context, forceEclass: Boolean = false): WidgetData =
        runCatching { ensureRepository(context)?.load(forceEclass = forceEclass) }.getOrNull()
            ?: loadingWidgetData()

    private fun loadingWidgetData() = WidgetData(
        pages = PageKind.entries.map { kind ->
            WidgetPage(kind = kind, title = kind.label, items = emptyList(), emptyText = "加载中…")
        }
    )
}
