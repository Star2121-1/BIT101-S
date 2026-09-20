package cn.bit101.android.features.seat.ui.screen

import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.isTerminal
import kotlin.math.max

/**
 * 任务列表的**纯逻辑**：排序、分组、时间文案。
 *
 * 刻意抽离成不依赖 Compose 的函数 —— 这样排序规则与时间格式化能进纯 JVM 单测，
 * 不必把 compose-ui 拖进 test classpath（与 `SeatMapCanvas` 的 viewport 几何同一思路）。
 */
internal object TaskListLogic {

    /**
     * 列表排序：**进行中（运行中/等待中）浮到最前**，终态沉底。
     *
     * 组内排序：
     * - 进行中：按创建时间**倒序**（越新越靠前，符合「刚建的任务最关心」直觉）
     * - 终态：按创建时间**倒序**（最近结束的更有参考价值）
     *
     * ⚠️ 为什么不能只按 createdAt 排：一个三天前建立的监控任务若仍在跑，
     * 会被今天新建的、已经失败的终态任务压到下面 —— 用户最需要看的反而看不到。
     * 所以「是否进行中」必须是一级排序键。
     *
     * `createdAt == 0`（老数据）视为最旧，排在各自分组末尾；用 `id` 做稳定兜底，
     * 避免同秒创建的任务在每次重组时顺序抖动。
     */
    fun sort(tasks: List<ReservationTask>): List<ReservationTask> =
        tasks.sortedWith(
            compareByDescending<ReservationTask> { !it.status.isTerminal }
                .thenByDescending { it.createdAt }
                .thenBy { it.id }
        )

    /** 进行中的任务。 */
    fun active(tasks: List<ReservationTask>): List<ReservationTask> =
        sort(tasks).filterNot { it.status.isTerminal }

    /** 已结束的任务。 */
    fun finished(tasks: List<ReservationTask>): List<ReservationTask> =
        sort(tasks).filter { it.status.isTerminal }

    /**
     * 「已等待 N」文案。用于进行中任务卡片。
     *
     * 返回 null 表示无法计算（`createdAt == 0`，老数据），UI 侧应隐藏该行 ——
     * 绝不能在界面上出现「已等待 56 年」这种由 1970 纪元算出来的鬼东西。
     */
    fun waitedText(createdAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (createdAt <= 0L) return null
        val minutes = max(0L, (now - createdAt) / 60_000L)
        return when {
            minutes < 1L -> "刚刚开始"
            minutes < 60L -> "已等待 $minutes 分钟"
            else -> "已等待 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

    /**
     * 「最近尝试 N 分钟前」文案。用于进行中任务卡片。
     *
     * 返回 null 表示还没尝试过（`lastAttemptAt == 0`），UI 侧应显示「尚未尝试」而不是隐藏 ——
     * 「一直没尝试过」本身就是异常信号（服务可能没起来），必须让用户看见。
     */
    fun lastAttemptText(lastAttemptAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (lastAttemptAt <= 0L) return null
        val seconds = max(0L, (now - lastAttemptAt) / 1_000L)
        return when {
            seconds < 60L -> "最近尝试：${seconds} 秒前"
            seconds < 3600L -> "最近尝试：${seconds / 60} 分钟前"
            else -> "最近尝试：${seconds / 3600} 小时前"
        }
    }

    /**
     * 预约签到的**倒计时**文案。
     *
     * 签到超时要记违约（累计 5 次停用 7 天），所以这里要给出「还剩多久」的紧迫感，
     * 而不是干巴巴一个截止时刻。返回 `(文案, 是否已超时)`。
     *
     * 已超时不算「还剩 0 分钟」，而要明确说「已超时」—— 两者对用户的行动指引完全不同。
     */
    fun signInCountdown(deadlineEpochMs: Long, now: Long = System.currentTimeMillis()): Pair<String, Boolean> {
        val remain = deadlineEpochMs - now
        if (remain <= 0L) return "已超时" to true
        val minutes = remain / 60_000L
        val text = when {
            minutes < 1L -> "还剩不到 1 分钟"
            minutes < 60L -> "还剩 $minutes 分钟"
            else -> "还剩 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
        return text to false
    }

    /**
     * 「最后更新：N 前」文案。返回 null 表示从未成功刷新过。
     *
     * 让用户知道列表数据的新鲜度 —— 否则一个静止的列表既可能是「确实没变」，
     * 也可能是「刷新早就失败了」，用户无从区分。
     */
    fun updatedAgoText(lastUpdatedAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (lastUpdatedAt <= 0L) return null
        val seconds = max(0L, (now - lastUpdatedAt) / 1_000L)
        return when {
            seconds < 10L -> "刚刚更新"
            seconds < 60L -> "${seconds} 秒前更新"
            seconds < 3600L -> "${seconds / 60} 分钟前更新"
            else -> "${seconds / 3600} 小时前更新"
        }
    }

    /**
     * 任务卡上的模式标签。
     *
     * 优先模式要区分「指定座位」与「不限」—— 两者的行为差异很大（前者盯着几个具体座位，
     * 后者只要区域内最早空出就抢），但模型里都只是 `preferredSeats` 空/非空。
     */
    fun modeLabel(task: ReservationTask): String = when (task.mode) {
        cn.bit101.android.features.seat.model.TaskMode.SINGLE -> "单次"
        cn.bit101.android.features.seat.model.TaskMode.MONITOR -> "监控"
        cn.bit101.android.features.seat.model.TaskMode.PREFER ->
            if (task.preferredSeats.isEmpty()) "优先·不限" else "优先·指定"
    }

    /**
     * 任务卡上的座位标签。优先模式展示偏好清单（按优先级），监控模式展示盯的单个座位。
     * 都为空时返回 null，UI 侧隐藏该行。
     */
    fun seatLabel(task: ReservationTask): String? =
        task.preferredSeats.takeIf { it.isNotEmpty() }?.joinToString(",")
            ?: task.seatNo.takeIf { it.isNotBlank() }

    /**
     * 任务卡上的时间段文案。模型里 `startTime`/`endTime` 一直存在，
     * 但此前 UI 只显示了日期 —— 同一件事在预约卡上显示了、任务卡上却没有，观感割裂。
     */
    fun timeRangeLabel(task: ReservationTask): String {
        val s = task.startTime.trim()
        val e = task.endTime.trim()
        return when {
            s.isBlank() && e.isBlank() -> "-"
            s.isBlank() -> e
            e.isBlank() -> s
            else -> "$s - $e"
        }
    }

    /**
     * 任务卡「存活信息行」的完整文案：已等待多久 · 尝试几次 · 最近尝试何时。
     *
     * 这是进行中任务卡上**唯一的「后台还活着」证据** —— 监控任务可能在系统杀进程、
     * 退避等待或断网时长时间没有结果，光看徽章上的「运行中」用户无从判断它是否还在干活。
     *
     * 三条信息的呈现规则（各自独立，缺谁补谁）：
     * - `createdAt <= 0`（老数据）→ 不显示「已等待」，但**尝试次数照常显示**，
     *   因为次数是用户唯一能看到的进展信号，不该被一个缺失的时间戳连累。
     * - `attempts` 永远显示（哪怕是 0）—— 「尝试 0 次」本身就是异常信号。
     * - `lastAttemptAt <= 0` → 追加「尚未尝试」，而不是静默省略。
     *
     * 返回 null 表示三条都无从计算（老数据且从未尝试），UI 侧应隐藏整行。
     */
    fun livenessText(
        createdAt: Long,
        attempts: Int,
        lastAttemptAt: Long,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val waited = waitedText(createdAt, now)
        val last = lastAttemptText(lastAttemptAt, now)
        val parts = buildList {
            waited?.let { add(it) }
            add("尝试 $attempts 次")
            last?.let { add(it) } ?: add("尚未尝试")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 状态徽章文案与配色键。配色键由 UI 映射到具体颜色（保持本文件无 Compose 依赖）。 */
    fun statusBadge(status: TaskStatus): Pair<String, String> = when (status) {
        TaskStatus.IDLE -> "等待中" to "idle"
        TaskStatus.RUNNING -> "运行中" to "running"
        TaskStatus.SUCCESS -> "成功" to "success"
        TaskStatus.FAILED -> "失败" to "failed"
        TaskStatus.CANCELLED -> "已取消" to "cancelled"
    }
}
