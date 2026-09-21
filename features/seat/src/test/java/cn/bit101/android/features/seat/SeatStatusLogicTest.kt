package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.widget.SeatWidgetSnapshot
import cn.bit101.android.features.widget.WidgetLine
import cn.bit101.android.features.widget.WidgetLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SeatStatusLogic] 的纯逻辑单测。
 *
 * 状态判定**以服务端 `statusName` 为准**（官方前端也只用它显示），本测试锁住：
 * - 阶段判定优先级与关键词匹配
 * - 倒计时 / 已用时长 / 暂离保留时长的计算与措辞
 * - 展示排序（使用中 > 暂离 > 已预约 > 监控中）
 * - 选座规则与 `SeatMonitorService` 完全一致（同一函数）
 */
class SeatStatusLogicTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 21) // 周一
    private val now: LocalDateTime = today.atTime(14, 12)

    private fun record(
        seatNo: String = "018",
        status: String = "2",
        statusName: String? = null,
        beginTime: String = "2026-09-21 14:00:00",
        endTime: String = "2026-09-21 22:30:00",
    ) = ReservationRecord(
        id = "r-$seatNo-$status",
        seatId = "900",
        seatNo = seatNo,
        areaName = "徐特立馆-三层",
        beginTime = beginTime,
        endTime = endTime,
        status = status,
        statusName = statusName,
    )

    private fun task(
        mode: TaskMode = TaskMode.MONITOR,
        status: TaskStatus = TaskStatus.RUNNING,
        seatNo: String = "018",
        preferredSeats: List<String> = emptyList(),
    ) = ReservationTask(
        id = "t-$mode-$status-$seatNo-${preferredSeats.joinToString()}",
        mode = mode,
        status = status,
        areaId = "3",
        segment = "1",
        seatNo = seatNo,
        preferredSeats = preferredSeats,
        reserveDate = "2026-09-22",
        startTime = "08:00",
        endTime = "22:30",
    )

    private fun seat(no: String, status: SeatStatus) = Seat(
        id = "s$no", no = no, status = status,
        pointX = 0f, pointY = 0f, width = 0f, height = 0f,
    )

    // ------------------------------------------------------------ 阶段判定

    @Test
    fun `状态名优先于数字，含使用判定为使用中`() {
        assertEquals(SeatStatusLogic.Phase.IN_USE, SeatStatusLogic.phaseOf(record(status = "6", statusName = "使用中")))
        assertEquals(SeatStatusLogic.Phase.IN_USE, SeatStatusLogic.phaseOf(record(status = "2", statusName = "在座使用中")))
    }

    @Test
    fun `状态名含离开判定为暂离`() {
        assertEquals(SeatStatusLogic.Phase.LEAVE, SeatStatusLogic.phaseOf(record(status = "7", statusName = "临时离开")))
        assertEquals(SeatStatusLogic.Phase.LEAVE, SeatStatusLogic.phaseOf(record(statusName = "暂离")))
    }

    @Test
    fun `状态名缺失时按数字兜底为待签到`() {
        assertEquals(SeatStatusLogic.Phase.PENDING, SeatStatusLogic.phaseOf(record(status = "2")))
    }

    @Test
    fun `未识别的一律归为其他，不编造状态`() {
        assertEquals(SeatStatusLogic.Phase.OTHER, SeatStatusLogic.phaseOf(record(status = "3", statusName = "已完成")))
        assertEquals(SeatStatusLogic.Phase.OTHER, SeatStatusLogic.phaseOf(record(status = "9")))
    }

    // ------------------------------------------------------------ 已预约（待签到）

    @Test
    fun `待签到显示刷卡倒计时`() {
        // 14:12 预约从 14:00 开始 → 截止 15:00，剩 48 分钟
        val line = SeatStatusLogic.build(listOf(record()), emptyList(), now).lines.single()
        assertEquals("已预约", line.lead)
        assertEquals("座位 018", line.main)
        assertEquals("14:00-22:30", line.time)
        assertEquals("剩 48 分钟刷卡", line.trail)
    }

    @Test
    fun `签到超时明确说超时而不是给负数`() {
        val late = today.atTime(16, 0)
        val line = SeatStatusLogic.build(listOf(record()), emptyList(), late).lines.single()
        assertEquals("签到已超时", line.trail)
    }

    @Test
    fun `次日预约按次日 9 点前刷卡提示`() {
        val tomorrow = today.plusDays(1)
        val rec = record(beginTime = "2026-09-22 08:00:00", endTime = "2026-09-22 22:30:00")
        val line = SeatStatusLogic.build(listOf(rec), emptyList(), today.atTime(21, 0)).lines.single()
        // 预约开始 08:00 + 60 分钟 = 09:00，与「次日 9:00 前」一致
        assertEquals("剩 12 小时刷卡", line.trail)
    }

    // ------------------------------------------------------------ 使用中 / 暂离

    @Test
    fun `使用中显示已用时长`() {
        // 14:12 - 14:00 = 12 分钟
        val line = SeatStatusLogic.build(
            listOf(record(status = "6", statusName = "使用中")), emptyList(), now,
        ).lines.single()
        assertEquals("使用中", line.lead)
        assertEquals("已用 12 分钟", line.trail)
    }

    @Test
    fun `使用中的时长跨小时显示小时和分钟`() {
        val line = SeatStatusLogic.build(
            listOf(record(status = "6", statusName = "使用中")), emptyList(),
            today.atTime(15, 32),
        ).lines.single()
        assertEquals("已用 1 小时 32 分", line.trail)
    }

    @Test
    fun `暂离显示规则允许的保留时长`() {
        val line = SeatStatusLogic.build(
            listOf(record(status = "7", statusName = "临时离开")), emptyList(), now,
        ).lines.single()
        assertEquals("暂离", line.lead)
        assertEquals("保留 60 分钟", line.trail)
        assertTrue("暂离需要提醒（黄色/红色），必须标记紧急", line.urgent)
    }

    @Test
    fun `用餐时段暂离保留延长到 120 分钟`() {
        assertEquals(60, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(10, 0)))
        assertEquals(120, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(11, 30)))
        assertEquals(60, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(14, 0)))
        assertEquals(120, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(16, 30)))
        // 边界：12:00 与 17:00 已出用餐时段
        assertEquals(60, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(12, 0)))
        assertEquals(60, SeatStatusLogic.leaveAllowanceMinutes(LocalTime.of(17, 0)))
    }

    // ------------------------------------------------------------ 排序与兜底

    @Test
    fun `展示顺序为 使用中-暂离-已预约`() {
        val records = listOf(
            record(seatNo = "018", status = "2"),                        // 已预约
            record(seatNo = "020", status = "7", statusName = "临时离开"), // 暂离
            record(seatNo = "022", status = "6", statusName = "使用中"),   // 使用中
        )
        val lines = SeatStatusLogic.build(records, emptyList(), now).lines
        assertEquals(listOf("使用中", "暂离", "已预约"), lines.map { it.lead })
    }

    /** 自动预约成功 → 服务端会有记录；记录缺失的空窗期才用 SUCCESS 任务兜底，避免重复。 */
    @Test
    fun `无预约记录时用成功的任务兜底显示已预约`() {
        val lines = SeatStatusLogic.build(emptyList(), listOf(task(status = TaskStatus.SUCCESS)), now).lines
        assertEquals(1, lines.size)
        assertEquals("已预约", lines.first().lead)
    }

    @Test
    fun `有预约记录时不再用成功任务兜底，避免同一座位两行`() {
        val lines = SeatStatusLogic.build(
            records = listOf(record()),
            tasks = listOf(task(status = TaskStatus.SUCCESS)),
            now = now,
        ).lines
        assertEquals(1, lines.size)
        assertEquals("已预约", lines.first().lead)
    }

    @Test
    fun `终态失败与取消的任务不显示`() {
        val lines = SeatStatusLogic.build(
            emptyList(),
            listOf(
                task(status = TaskStatus.FAILED, seatNo = "011"),
                task(status = TaskStatus.CANCELLED, seatNo = "012"),
            ),
            now,
        ).lines
        assertTrue(lines.isEmpty())
    }

    // ------------------------------------------------------------ 一键预约目标

    @Test
    fun `带座位目标的运行中任务才作为一键预约目标`() {
        assertEquals(
            "t-1",
            SeatStatusLogic.build(
                emptyList(),
                listOf(task(seatNo = "018", status = TaskStatus.RUNNING).copy(id = "t-1")),
                now,
            ).quickReserveTaskId,
        )
        // 优先模式多座位也算（目标是偏好清单）
        assertEquals(
            "t-2",
            SeatStatusLogic.build(
                emptyList(),
                listOf(task(mode = TaskMode.PREFER, seatNo = "", preferredSeats = listOf("018", "020")).copy(id = "t-2")),
                now,
            ).quickReserveTaskId,
        )
        // 无目标（不限座位）→ 没有「一键预约」，按钮回落为打开 App
        assertNull(
            SeatStatusLogic.build(
                emptyList(),
                listOf(task(mode = TaskMode.PREFER, seatNo = "", preferredSeats = emptyList())),
                now,
            ).quickReserveTaskId,
        )
        // 成功/失败/取消的任务不作为目标
        assertNull(
            SeatStatusLogic.build(
                emptyList(),
                listOf(task(status = TaskStatus.SUCCESS)),
                now,
            ).quickReserveTaskId,
        )
    }

    // ------------------------------------------------------------ 选座（与后台抢座共用）

    @Test
    fun `监控模式只盯指定座位，补零容错`() {
        val seats = listOf(seat("018", SeatStatus.RESERVED), seat("001", SeatStatus.AVAILABLE))
        val target = SeatStatusLogic.pickTargetSeat(seats, task(seatNo = "18"))  // 用户填 18，服务端是 018
        assertEquals("018", target?.no)
    }

    @Test
    fun `优先模式按偏好顺序挑空闲座位`() {
        val seats = listOf(
            seat("018", SeatStatus.RESERVED),  // 偏好第一，但不可约
            seat("020", SeatStatus.AVAILABLE), // 偏好第二，空闲
            seat("001", SeatStatus.AVAILABLE),
        )
        val target = SeatStatusLogic.pickTargetSeat(
            seats,
            task(mode = TaskMode.PREFER, seatNo = "", preferredSeats = listOf("018", "020")),
        )
        assertEquals("020", target?.no)
    }

    @Test
    fun `优先模式偏好全不可约时不降级到其他座位`() {
        val seats = listOf(seat("018", SeatStatus.RESERVED), seat("001", SeatStatus.AVAILABLE))
        val target = SeatStatusLogic.pickTargetSeat(
            seats,
            task(mode = TaskMode.PREFER, seatNo = "", preferredSeats = listOf("018")),
        )
        assertNull(target)
    }

    @Test
    fun `优先模式不限座位时取数值最小的空闲座位`() {
        val seats = listOf(seat("010", SeatStatus.AVAILABLE), seat("9", SeatStatus.AVAILABLE))
        val target = SeatStatusLogic.pickTargetSeat(
            seats,
            task(mode = TaskMode.PREFER, seatNo = "", preferredSeats = emptyList()),
        )
        // 按数值排序：9 < 10（字符串排序会得到 "10" < "9"，那是错的）
        assertEquals("9", target?.no)
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `时长文案按量级切换`() {
        assertEquals("0 分钟", SeatStatusLogic.durationText(java.time.Duration.ZERO))
        assertEquals("42 分钟", SeatStatusLogic.durationText(java.time.Duration.ofMinutes(42)))
        assertEquals("1 小时", SeatStatusLogic.durationText(java.time.Duration.ofMinutes(60)))
        assertEquals("1 小时 5 分", SeatStatusLogic.durationText(java.time.Duration.ofMinutes(65)))
    }

    @Test
    fun `提示行放在座位页最前面`() {
        val snapshot = SeatWidgetSnapshot.Snapshot(
            lines = listOf(WidgetLine(lead = "已预约", main = "座位 018")),
            notice = "预约失败：座位已被占用",
            // ⚠️ noticeAt 默认 0 = 「从未设置」，会被判为已过期 —— 必须给当前时间
            noticeAt = System.currentTimeMillis(),
        )
        val page = WidgetLogic.seatPage(snapshot)
        assertEquals("提示", page.items.first().lead)
        assertEquals("预约失败：座位已被占用", page.items.first().main)
    }
}
