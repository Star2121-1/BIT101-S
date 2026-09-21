package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatStatusLogic
import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.widget.SeatWidgetBridge
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SeatWidgetBridge] 的实现：组件「一键预约」真正干活的地方。
 *
 * ## 与后台抢座的关系
 *
 * 选座规则与 `SeatMonitorService` **共用** [SeatStatusLogic.pickTargetSeat]；
 * 「查时段参数」这一小段与 Service 的 `resolveSegment` 各有一份 ——
 * 那段逻辑稳定且只有 8 行，复制比把 Service 的私有结构抽出来再接进来更稳。
 * ⚠️ 若学校改了时段接口，**两处都要改**（搜索 `resolveSegment`）。
 *
 * ## 失败语义
 *
 * 返回值是**给用户看的文案**（组件会把「预约失败：$it」直接显示出来），
 * 所以这里必须把内部异常翻成中文 —— 复用 [seatErrorText]。
 */
@Singleton
class DefaultSeatWidgetBridge @Inject constructor(
    private val seatApi: SeatApi,
    private val taskRepository: SeatTaskRepository,
    private val reservationRepository: SeatReservationRepository,
) : SeatWidgetBridge {

    /** 有会话才允许下单 —— 没登录就点只会得到一句「请重新登录」。 */
    override fun canReserveNow(): Boolean = seatApi.token.isNotBlank()

    /** 交给 [SeatReservationRepository]：失败它自己记日志，不打扰用户。 */
    override suspend fun refreshReservations() {
        reservationRepository.refresh()
    }

    override suspend fun reserveNow(taskId: String): String? {
        val task = taskRepository.tasks.value.firstOrNull { it.id == taskId }
            ?: return "任务不存在或已结束"

        if (!canReserveNow()) return "座位系统未登录，请先打开 App 授权"

        val params = resolveSegment(task.areaId, task.reserveDate)
            ?: return "获取座位时段失败，请稍后重试"

        val seats = seatApi
            .getSeats(task.areaId, params.segmentId, task.reserveDate, params.startTime, params.endTime)
            .getOrElse { return seatErrorText(it, "查询座位失败") }

        // 与后台抢座同一套选座规则；挑不到就是「目标都不空闲」
        val target = SeatStatusLogic.pickTargetSeat(seats, task)
            ?: return if (task.mode == cn.bit101.android.features.seat.model.TaskMode.PREFER) {
                "暂无可预约的空闲座位"
            } else {
                "座位 ${task.seatNo} 当前不可约"
            }
        if (target.status != SeatStatus.AVAILABLE) return "座位 ${target.no} 已被占用"

        val confirm = seatApi.confirmSeat(target.id, params.segmentId)
        return if (confirm.isSuccess && confirm.getOrNull() == true) {
            taskRepository.updateStatus(taskId, cn.bit101.android.features.seat.model.TaskStatus.SUCCESS, "预约成功，座位 ${target.no}")
            null
        } else {
            SeatLog.w(TAG, "quick reserve failed: ${confirm.exceptionOrNull()?.message}")
            seatErrorText(confirm.exceptionOrNull(), "预约失败")
        }
    }

    /** 与 `SeatMonitorService.resolveSegment` 相同的取值逻辑（见类注释的同步提醒）。 */
    private suspend fun resolveSegment(areaId: String, day: String): SegmentParams? {
        val dates = seatApi.getSeatDates(buildId = areaId).getOrNull() ?: return null
        val seg = dates.firstOrNull { it.day == day }
            ?: dates.firstOrNull()
            ?: return null
        fun String.usable() = takeIf { it.isNotBlank() && it != "null" }
        return SegmentParams(
            segmentId = seg.segmentId.usable() ?: "1",
            startTime = seg.start.usable() ?: "08:00",
            endTime = seg.end.usable() ?: "22:30",
        )
    }

    private data class SegmentParams(
        val segmentId: String,
        val startTime: String,
        val endTime: String,
    )

    private companion object {
        const val TAG = "SeatWidgetBridge"
    }
}
