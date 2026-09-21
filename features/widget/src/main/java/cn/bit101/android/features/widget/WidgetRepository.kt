package cn.bit101.android.features.widget

import android.content.Context
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.DDLScheduleRepo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件的数据入口。
 *
 * 只做两件事：从各仓库取数据 → 交给 [WidgetLogic] 聚合。
 * 不含任何展示逻辑（那是 Glance 层的事），也不含业务判断（那是 [WidgetLogic] 的事），
 * 所以本身很薄、不需要单测 —— 真正需要测的纯逻辑都在 [WidgetLogic] 里。
 */
@Singleton
class WidgetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coursesRepo: CoursesRepo,
    private val ddlRepo: DDLScheduleRepo,
    private val courseScheduleSettings: CourseScheduleSettings,
) {

    /**
     * 组装组件数据。
     *
     * 各数据源**并行且容错**：任何一个失败都不应让整个组件空白 ——
     * 课表拉不到就显示空课程页，但 DDL 页仍应正常。故每项单独 try/catch。
     */
    suspend fun load(now: LocalDateTime = LocalDateTime.now()): WidgetData {
        val today = now.toLocalDate()

        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val week = WidgetLogic.weekOf(firstDay, today)

        val courses = runCatching {
            coursesRepo.getCoursesFromLocal().first()
        }.getOrDefault(emptyList())

        val ddls = runCatching {
            ddlRepo.getFutureDDL(now.minusDays(LOOKBACK_DAYS)).first()
        }.getOrDefault(emptyList())

        val seatLines = runCatching { SeatWidgetSnapshot.read(context) }.getOrDefault(emptyList())

        return WidgetLogic.build(
            courses = courses,
            ddls = ddls,
            seatLines = seatLines,
            today = today,
            now = now,
            week = week,
            weekday = today.dayOfWeek.value,
        )
    }

    /**
     * 供 App 内部（课表同步、DDL 同步后）调用的刷新触发器。
     *
     * 组件不在前台，只能主动通知系统重绘；数据由 Provider 重新读一遍。
     */
    fun refresh() {
        runCatching { BIT101WidgetProvider.requestRenderAll(context) }
    }

    private companion object {
        /**
         * DDL 往回看的天数。
         *
         * 已过期的未完成项**也要显示**（它们才真正要紧），但如果无限往回取，
         * 一个学期前忘掉的作业会永久占据组件首行。取 7 天是个折中：
         * 上周的漏项还能看到，更早的视为已放弃。
         */
        const val LOOKBACK_DAYS = 7L
    }
}

/** 便捷扩展：把 `LocalDate` 转成午夜时刻，用于需要 `LocalDateTime` 的场景。 */
internal fun LocalDate.atStartOfDay(): LocalDateTime = atTime(0, 0)
