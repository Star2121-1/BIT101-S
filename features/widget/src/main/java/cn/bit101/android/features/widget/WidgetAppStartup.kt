package cn.bit101.android.features.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 从 Hilt 图里取 [WidgetRepository] 的入口点。
 *
 * 为什么需要它：`GlanceAppWidget` 由**系统**实例化（`AppWidgetProvider` 机制），
 * 不走 Hilt 的 `@AndroidEntryPoint`，所以组件内部不能直接字段注入。
 * `@EntryPoint` 是官方为这种「非注入组件取依赖」场景提供的标准解法。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetRepository(): WidgetRepository
}

/**
 * 初始化组件依赖。**必须在 App 启动时调用一次**（见 `App.kt`）。
 *
 * 幂等：重复调用只是覆盖同一个实例，无副作用。
 */
object WidgetAppStartup {

    fun init(context: Context) {
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
            WidgetRepositoryHolder.install(entryPoint.widgetRepository())
            WidgetRefreshWorker.schedule(context.applicationContext)
        }
        // 组件依赖初始化失败不应影响 App 主流程 —— 桌面组件显示空态即可。
        // 这里刻意吞掉异常（例如 Hilt 图尚未就绪时被系统提前拉起组件进程）。
    }
}
