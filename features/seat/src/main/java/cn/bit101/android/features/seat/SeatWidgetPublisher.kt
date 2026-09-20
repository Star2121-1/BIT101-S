package cn.bit101.android.features.seat

import android.content.Context
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.isActive
import cn.bit101.android.features.widget.SeatWidgetSnapshot
import cn.bit101.android.features.widget.WidgetLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 座位模块 → 桌面小组件的单向数据推送。
 *
 * **设计取舍**：小组件不依赖座位模块，而是由座位模块主动把「要显示的行」
 * 写进 [SeatWidgetSnapshot]。这样：
 * - 组件在没装座位功能 / 座位模块未初始化时也能正常显示课程与 DDL 两页
 * - 座位模块的改动不会牵动组件（反之亦然）
 *
 * 代价是「行的组装逻辑」留在了座位侧（本文件）。这是有意的 ——
 * 只有座位模块知道「签到时限」「尝试次数」这些字段怎么解释。
 */
@Singleton
class SeatWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SeatTaskRepository,
) {

    /**
     * 开始观察任务变化并推送到组件。
     *
     * 调用时机：App 启动（见 `App.kt`）。重复调用是幂等的 ——
     * 这里用 [started] 标志挡住，避免多个 scope 同时写同一份快照。
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        repository.tasks
            .onEach { tasks -> publish(tasks) }
            .launchIn(scope)
    }

    /**
     * 把任务列表折算成组件行。
     *
     * 展示优先级（组件只有 3 行，必须挑最有信息量的）：
     * 1. **进行中的任务**（用户最关心「还在抢吗」）—— 显示模式 + 座位 + 尝试次数
     * 2. **最近成功的任务**（抢到了什么）
     *
     * 终态失败/取消的**不显示** —— 它们不需要用户行动，占位反而挤掉有用的信息。
     */
    private fun publish(tasks: List<cn.bit101.android.features.seat.model.ReservationTask>) {
        val active = tasks.filter { it.status.isActive }
        val succeeded = tasks.filter { it.status == TaskStatus.SUCCESS }

        val lines = buildList {
            active.take(MAX_ROWS).forEach { task ->
                add(
                    WidgetLine(
                        lead = task.mode.label(),
                        main = task.seatLabel(),
                        trail = if (task.attempts > 0) "尝试 ${task.attempts} 次" else "等待中",
                    )
                )
            }
            // 进行中的没占满才补成功记录，避免把「正在跑」的信息挤掉
            if (size < MAX_ROWS) {
                val room = MAX_ROWS - size
                succeeded.take(room).forEach { task ->
                    add(
                        WidgetLine(
                            lead = "已预约",
                            main = task.seatLabel(),
                            trail = task.startTime,
                        )
                    )
                }
            }
        }

        SeatWidgetSnapshot.write(context, lines)
    }

    private fun cn.bit101.android.features.seat.model.TaskMode.label(): String = when (this) {
        cn.bit101.android.features.seat.model.TaskMode.SINGLE -> "单次"
        cn.bit101.android.features.seat.model.TaskMode.MONITOR -> "监控"
        cn.bit101.android.features.seat.model.TaskMode.PREFER -> "优先"
    }

    /** 座位描述：优先模式可能多选，用「首个 +N」表达，避免组件上被截断成一串数字。 */
    private fun cn.bit101.android.features.seat.model.ReservationTask.seatLabel(): String = when {
        seatNo.isNotBlank() -> "座位 $seatNo"
        preferredSeats.isNotEmpty() -> {
            val head = preferredSeats.first()
            if (preferredSeats.size == 1) "座位 $head" else "座位 $head +${preferredSeats.size - 1}"
        }
        else -> areaName.ifBlank { "不限座位" }
    }

    private companion object {
        /** 组件一页最多 3 行（1 主 + 2 次），与 WidgetPage.MAX_SECONDARY 保持一致 */
        const val MAX_ROWS = 3

        @Volatile
        private var started = false
    }
}
