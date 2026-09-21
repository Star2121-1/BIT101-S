package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatNumberComparator
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.isActive
import cn.bit101.android.features.seat.model.seatNumberEquals
import cn.bit101.android.features.widget.WidgetLine
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 座位状态 → 组件行的纯逻辑。
 *
 * 与 [SeatMonitorService] 的选座规则**共用同一个** [pickTargetSeat]，
 * 避免「后台抢座」与「手动一键预约」两套逻辑慢慢走偏。
 */
object SeatStatusLogic {

    /** 座位页最多显示几行（组件可滚动，给足即可，防止异常数据刷屏） */
    const val MAX_ROWS = 6

    /** 服务端「有效未签到」的 status 值（官方前端也只对这个值给取消按钮） */
    const val STATUS_ACTIVE = "2"

    /**
     * 预约记录所处的阶段。
     *
     * ⚠️ **判定顺序与依据**：优先看服务端给的中文 [ReservationRecord.statusName]，
     * 匹配不到才退回 status 数字。
     *
     * 为什么不按数字映射文案：官方前端对预约记录**只对 `status=="2"` 做了特判**
     * （给「取消预约」按钮），其余状态一律直接显示服务端返回的 `statusname`
     * （证据：`.workbuddy/archive/server-contract/chunk_seat.js`）。
     * 自己按数字编文案，服务端一改语义就会静默显示错的状态。
     */
    enum class Phase {
        /** 正在使用座位 */
        IN_USE,

        /** 临时离开（座位保留中） */
        LEAVE,

        /** 已预约、尚未签到 */
        PENDING,

        /** 其他（已结束 / 已取消 / 未知） */
        OTHER,
    }

    /** 座位页的组装结果。 */
    data class SeatPageModel(
        val lines: List<WidgetLine>,
        /**
         * 有「可立即预约的目标」时给出任务 id，组件据此把底部按钮变成「一键预约」。
         *
         * 语义是**"别等了，现在就对它的目标座位下单"** —— 不是"检测到空位"。
         * 原因：监控任务一旦发现空位会**立刻自动下单**，等用户看到再点根本来不及；
         * 真正需要手动介入的是「任务在跑但一直没抢到 / 用户就是想立刻试一次」。
         */
        val quickReserveTaskId: String? = null,
    )

    // ------------------------------------------------------------------ 阶段判定

    /** 服务端状态名里出现这些词就认为处于对应阶段（顺序即优先级）。 */
    private val LEAVE_KEYWORDS = listOf("离开", "暂离")
    private val IN_USE_KEYWORDS = listOf("使用", "在座", "进行中")

    fun phaseOf(record: ReservationRecord): Phase {
        val name = record.statusName.orEmpty()
        return when {
            LEAVE_KEYWORDS.any { name.contains(it) } -> Phase.LEAVE
            IN_USE_KEYWORDS.any { name.contains(it) } -> Phase.IN_USE
            record.status == STATUS_ACTIVE -> Phase.PENDING
            else -> Phase.OTHER
        }
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 把「我的预约」与「进行中的任务」折算成组件行。
     *
     * 展示优先级（用户此刻最关心的排在前面）：
     * 1. **使用中**（人在图书馆，最关心"还能用多久"）
     * 2. **暂离**（要赶回去，最关心"还剩几分钟"）
     * 3. **已预约待签到**（最关心"还有多久刷卡"）
     * 4. **进行中的监控/优先任务**（"还在抢吗"）
     *
     * 终态失败/取消的任务不显示 —— 它们不需要用户行动，占位反而挤掉有用信息。
     */
    fun build(
        records: List<ReservationRecord>,
        tasks: List<ReservationTask>,
        now: LocalDateTime = LocalDateTime.now(),
    ): SeatPageModel {
        val ordered = records.sortedBy { phaseOrder(phaseOf(it)) }
        val lines = ArrayList<WidgetLine>()

        ordered.forEach { record ->
            if (lines.size < MAX_ROWS) lines.add(record.toLine(phaseOf(record), now))
        }

        // 自动预约成功后服务端就有记录了，正常情况下上面已经显示。
        // 只有「任务已成功但记录还没刷新回来」的空窗期才用任务兜底，避免同一座位出现两行。
        if (records.none { it.isLive() }) {
            tasks.filter { it.status == TaskStatus.SUCCESS }
                .take(MAX_ROWS - lines.size)
                .forEach { task ->
                    lines.add(
                        WidgetLine(
                            lead = "已预约",
                            main = task.seatLabel(),
                            trail = task.startTime,
                            highlight = null,
                        )
                    )
                }
        }

        val running = tasks.filter { it.status.isActive }
        running.take((MAX_ROWS - lines.size).coerceAtLeast(0)).forEach { task ->
            lines.add(task.toLine())
        }

        return SeatPageModel(
            lines = lines,
            quickReserveTaskId = running.firstOrNull { it.hasReserveTarget() }?.id,
        )
    }

    private fun phaseOrder(phase: Phase): Int = when (phase) {
        Phase.IN_USE -> 0
        Phase.LEAVE -> 1
        Phase.PENDING -> 2
        Phase.OTHER -> 3
    }

    /** 记录是否「还没结束」—— 用于判断要不要用任务兜底显示"已预约"。 */
    private fun ReservationRecord.isLive(): Boolean = phaseOf(this) != Phase.OTHER

    // ------------------------------------------------------------------ 单条记录

    private fun ReservationRecord.toLine(phase: Phase, now: LocalDateTime): WidgetLine = when (phase) {
        Phase.IN_USE -> WidgetLine(
            lead = "使用中",
            main = seatLabel(),
            time = timeRangeText(),
            trail = usedText(this, now),
        )

        Phase.LEAVE -> WidgetLine(
            lead = "暂离",
            main = seatLabel(),
            time = timeRangeText(),
            // 服务端不返回「什么时候离开的」，所以算不出还剩几分钟。
            // 这里给出**规则允许的保留时长**（比编一个倒计时诚实）。
            trail = "保留 ${leaveAllowanceMinutes(now.toLocalTime())} 分钟",
            urgent = true,
        )

        Phase.PENDING -> WidgetLine(
            lead = "已预约",
            main = seatLabel(),
            time = timeRangeText(),
            trail = signInCountdownText(this, now),
        )

        Phase.OTHER -> WidgetLine(
            lead = statusName ?: "已结束",
            main = seatLabel(),
            time = timeRangeText(),
        )
    }

    private fun ReservationRecord.seatLabel(): String =
        if (seatNo.isBlank()) areaName.ifBlank { "已预约座位" } else "座位 $seatNo"

    /** 预约时段 `14:00-22:30`；两端都拿不到时返回空串（组件会隐藏那一行）。 */
    private fun ReservationRecord.timeRangeText(): String {
        val b = beginTime.timePart()
        val e = endTime.timePart()
        return when {
            b.isEmpty() && e.isEmpty() -> ""
            e.isEmpty() -> b
            b.isEmpty() -> e
            else -> "$b-$e"
        }
    }

    /**
     * 签到倒计时文案，如 `剩 42 分钟刷卡`。
     *
     * ⚠️ 已超时必须明说「签到已超时」而不是给负数 —— 见 [ReservationRecord.signInDeadline]。
     */
    fun signInCountdownText(record: ReservationRecord, now: LocalDateTime): String {
        val deadline = record.signInDeadline(now) ?: return ""
        if (!deadline.isAfter(now)) return "签到已超时"
        return "剩 ${durationText(Duration.between(now, deadline))}刷卡"
    }

    /**
     * 已用时长：`已用 1 小时 20 分`。
     *
     * ⚠️ 用的是预约时段的**开始时间**，不是「实际刷卡时刻」—— 服务端不返回后者。
     * 提前入馆或迟到时会有偏差，文案上不做「已坐 N 分钟」这种更精确的断言。
     */
    fun usedText(record: ReservationRecord, now: LocalDateTime): String {
        val begin = record.beginLocalTime() ?: return ""
        if (begin.isAfter(now)) return ""      // 还没开始（例如次日预约）
        return "已用 ${durationText(Duration.between(begin, now))}"
    }

    /**
     * 临时离开的保留时长（分钟）。
     *
     * 规则（`/api/index/booking_rules`）：默认保留 **60 分钟**；
     * 用餐时段（11:00-12:00 / 16:00-17:00）**延长至 120 分钟**。
     */
    fun leaveAllowanceMinutes(now: LocalTime): Int {
        val mealWindow = now >= LocalTime.of(11, 0) && now < LocalTime.of(12, 0) ||
            now >= LocalTime.of(16, 0) && now < LocalTime.of(17, 0)
        return if (mealWindow) 120 else 60
    }

    /** 时长 → `1 小时 20 分` / `42 分钟`。 */
    fun durationText(d: Duration): String {
        val total = d.toMinutes().coerceAtLeast(0)
        if (total < 60) return "$total 分钟"
        val h = total / 60
        val m = total % 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
    }

    /** `"2026-09-21 14:03:27"` → `14:03`；拿不到返回空串。 */
    private fun String.timePart(): String {
        val t = trim()
        if (t.length < 16) return ""
        return t.substring(11, 16).takeIf { it[2] == ':' } ?: ""
    }

    // ------------------------------------------------------------------ 单条任务

    private fun ReservationTask.toLine(): WidgetLine = WidgetLine(
        lead = mode.label(),
        main = seatLabel(),
        trail = if (attempts > 0) "尝试 $attempts 次" else "等待中",
    )

    private fun TaskMode.label(): String = when (this) {
        TaskMode.SINGLE -> "单次"
        TaskMode.MONITOR -> "监控中"
        TaskMode.PREFER -> "优先中"
    }

    /** 任务是否带着「能立即下单的目标座位」。 */
    private fun ReservationTask.hasReserveTarget(): Boolean =
        seatNo.isNotBlank() || preferredSeats.isNotEmpty()

    /**
     * 座位描述。
     *
     * 优先模式下可能同时盯多个座位，**用「首个 +N」汇总而不是全部列出** ——
     * 组件上排一长串座位号既看不懂也放不下（用户明确要求不要都显示）。
     */
    fun ReservationTask.seatLabel(): String = when {
        seatNo.isNotBlank() -> "座位 $seatNo"
        preferredSeats.isNotEmpty() -> {
            val head = preferredSeats.first()
            if (preferredSeats.size == 1) "座位 $head" else "座位 $head +${preferredSeats.size - 1}"
        }
        else -> areaName.ifBlank { "不限座位" }
    }

    // ------------------------------------------------------------------ 选座（与后台抢座共用）

    /**
     * 从座位列表里挑出「本次该尝试预约的座位」。
     *
     * ⚠️ 这段规则原先写在 [SeatMonitorService] 里，抽出来是为了让
     * 「后台自动抢座」与「组件上手动一键预约」走**同一套**判断，不会各改一半。
     *
     * - 监控模式：只盯 `task.seatNo` 那一个（**不要求它当前为空闲** ——
     *   调用方需要知道"这座位是不是已被我自己约了"，所以任意状态都返回）
     * - 优先模式：只在**空闲**座位里按偏好顺序找；偏好清单为空 = 不限，取最早空出的那个
     *
     * 座位号做补零容错（服务端返回 `"001"` 而用户常填 `"1"`）。
     */
    fun pickTargetSeat(seats: List<Seat>, task: ReservationTask): Seat? {
        val available = seats
            .filter { it.status == SeatStatus.AVAILABLE }
            .sortedWith(compareBy(SeatNumberComparator) { it.no })

        return if (task.mode == TaskMode.PREFER) {
            val byPreference = task.preferredSeats.firstNotNullOfOrNull { wanted ->
                available.firstOrNull { seatNumberEquals(it.no, wanted) }
            }
            byPreference ?: if (task.preferredSeats.isEmpty()) {
                available.firstOrNull { seatNumberEquals(it.no, task.seatNo) }
                    ?: available.firstOrNull()
            } else {
                null
            }
        } else {
            seats.firstOrNull { seatNumberEquals(it.no, task.seatNo) }
        }
    }
}
