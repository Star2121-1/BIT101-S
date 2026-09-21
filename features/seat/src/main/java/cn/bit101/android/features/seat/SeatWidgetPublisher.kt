package cn.bit101.android.features.seat

import android.content.Context
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.api.SeatReservationRepository
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.widget.SeatWidgetBridgeHolder
import cn.bit101.android.features.widget.SeatWidgetSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * 代价是「行的组装逻辑」留在了座位侧（本文件 + [SeatStatusLogic]）。这是有意的 ——
 * 只有座位模块知道「签到时限」「暂离保留」「尝试次数」这些字段怎么解释。
 *
 * ## 显示什么（2026-09-21 依用户反馈重做）
 *
 * 旧版只显示**任务**（监控/优先 + 尝试次数）。新版以 [SeatStatusLogic] 组装：
 * 已预约待签到（含刷卡倒计时）/ 使用中（含时长）/ 暂离（含保留规则）→ 监控中。
 * 同时把「可一键预约的目标任务」一并写进快照，组件据此把按钮变成「一键预约」。
 */
@Singleton
class SeatWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SeatTaskRepository,
    private val reservationRepository: SeatReservationRepository,
) {

    /** 开始观察状态变化并推送到组件。幂等。 */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        // 任务或预约记录任一变化都要重算 —— 抢座成功（任务变）与刷卡签到（记录变）是两条路径
        combine(repository.tasks, reservationRepository.records) { tasks, records ->
            publish(tasks, records)
        }.launchIn(scope)

        // 周期刷新「我的预约」：组件不联网，座位状态（暂离超时、自动释放）只有靠这里才会变
        scope.launch(Dispatchers.IO) { periodicRefresh() }
    }

    /**
     * 后台节流刷新。
     *
     * 周期 [REFRESH_INTERVAL_MS]（10 分钟）：座位状态的变化粒度是分钟级
     * （暂离保留 60/120 分钟、签到 60 分钟），更密的轮询只是浪费请求；
     * 且**会话失效时这里会静默失败**，不打扰用户 —— 组件显示旧状态比反复弹登录好。
     */
    private suspend fun periodicRefresh() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            runCatching { reservationRepository.refreshIfStale(REFRESH_INTERVAL_MS) }
                .onFailure { SeatLog.w(TAG, "periodic refresh failed: ${it.message}") }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun publish(tasks: List<ReservationTask>, records: List<ReservationRecord>) {
        val model = SeatStatusLogic.build(records = records, tasks = tasks)
        SeatWidgetSnapshot.write(
            context,
            SeatWidgetSnapshot.Snapshot(
                lines = model.lines,
                quickReserveTaskId = model.quickReserveTaskId
                    // 没有会话就别给「一键预约」—— 点了也只会得到「请先登录」
                    ?.takeIf { SeatWidgetBridgeHolder.canReserveNow() },
            ),
        )
    }

    private companion object {
        /** 后台刷新「我的预约」的节流间隔 */
        const val REFRESH_INTERVAL_MS = 10 * 60_000L

        const val TAG = "SeatWidgetPublisher"

        @Volatile
        private var started = false
    }
}
