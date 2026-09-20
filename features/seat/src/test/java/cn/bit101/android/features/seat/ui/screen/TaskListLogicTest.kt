package cn.bit101.android.features.seat.ui.screen

import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务列表的纯逻辑：排序、分组、时间文案。
 *
 * 这些规则直接决定「用户能不能第一眼看到真正在跑的那个任务」，
 * 以及「后台是否还活着」这个判断是否可信 —— 都是容易在后续改动中被悄悄改坏的。
 */
class TaskListLogicTest {

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.RUNNING,
        createdAt: Long = 1_000L,
        attempts: Int = 0,
        lastAttemptAt: Long = 0L,
        mode: TaskMode = TaskMode.MONITOR,
        preferredSeats: List<String> = emptyList(),
        seatNo: String = "",
        startTime: String = "08:00",
        endTime: String = "22:30",
    ) = ReservationTask(
        id = id,
        mode = mode,
        status = status,
        seatNo = seatNo,
        preferredSeats = preferredSeats,
        startTime = startTime,
        endTime = endTime,
        createdAt = createdAt,
        attempts = attempts,
        lastAttemptAt = lastAttemptAt,
    )

    // ── 排序 ──────────────────────────────────────────────────────────────

    @Test
    fun `active tasks always precede finished ones`() {
        // 核心不变式：一个三天前建立、仍在跑的监控任务，不能被今天新建的、
        // 已经失败的终态任务压到下面
        val oldButRunning = task("running-old", TaskStatus.RUNNING, createdAt = 100L)
        val newButFailed = task("failed-new", TaskStatus.FAILED, createdAt = 9_999L)

        val sorted = TaskListLogic.sort(listOf(newButFailed, oldButRunning))

        assertEquals(listOf("running-old", "failed-new"), sorted.map { it.id })
    }

    @Test
    fun `idle counts as active`() {
        val sorted = TaskListLogic.sort(
            listOf(
                task("success", TaskStatus.SUCCESS, createdAt = 9_999L),
                task("idle", TaskStatus.IDLE, createdAt = 1L),
            )
        )

        assertEquals("idle", sorted.first().id)
    }

    @Test
    fun `newer active task comes first`() {
        val sorted = TaskListLogic.sort(
            listOf(
                task("older", createdAt = 100L),
                task("newer", createdAt = 500L),
            )
        )

        assertEquals(listOf("newer", "older"), sorted.map { it.id })
    }

    @Test
    fun `newer finished task comes first`() {
        val sorted = TaskListLogic.sort(
            listOf(
                task("old-done", TaskStatus.SUCCESS, createdAt = 100L),
                task("new-done", TaskStatus.SUCCESS, createdAt = 500L),
            )
        )

        assertEquals(listOf("new-done", "old-done"), sorted.map { it.id })
    }

    @Test
    fun `tasks without createdAt sink to the bottom of their group`() {
        // 老数据 createdAt=0：不能因为「0 最小」而在倒序里被顶上来
        val sorted = TaskListLogic.sort(
            listOf(
                task("legacy", createdAt = 0L),
                task("dated", createdAt = 500L),
            )
        )

        assertEquals(listOf("dated", "legacy"), sorted.map { it.id })
    }

    @Test
    fun `identical createdAt keeps a stable order by id`() {
        val sorted = TaskListLogic.sort(
            listOf(
                task("bbb", createdAt = 500L),
                task("aaa", createdAt = 500L),
            )
        )

        // 稳定：同刻创建的两个任务不会在每次重组时互换位置
        assertEquals(listOf("aaa", "bbb"), sorted.map { it.id })
    }

    @Test
    fun `active and finished split correctly`() {
        val all = listOf(
            task("r", TaskStatus.RUNNING),
            task("i", TaskStatus.IDLE),
            task("s", TaskStatus.SUCCESS),
            task("f", TaskStatus.FAILED),
            task("c", TaskStatus.CANCELLED),
        )

        assertEquals(setOf("r", "i"), TaskListLogic.active(all).map { it.id }.toSet())
        assertEquals(setOf("s", "f", "c"), TaskListLogic.finished(all).map { it.id }.toSet())
    }

    // ── 已等待时长 ────────────────────────────────────────────────────────

    @Test
    fun `waitedText returns null for tasks without a creation time`() {
        assertNull(TaskListLogic.waitedText(createdAt = 0L, now = 10_000_000L))
    }

    @Test
    fun `waitedText handles minutes and hours`() {
        val base = 1_756_000_000_000L
        assertEquals("刚刚开始", TaskListLogic.waitedText(base, base + 10_000L))
        assertEquals("已等待 5 分钟", TaskListLogic.waitedText(base, base + 5 * 60_000L))
        assertEquals("已等待 1 小时 5 分钟", TaskListLogic.waitedText(base, base + 65 * 60_000L))
        assertEquals("已等待 2 小时 0 分钟", TaskListLogic.waitedText(base, base + 120 * 60_000L))
    }

    @Test
    fun `waitedText never goes negative when the clock jumps back`() {
        // 系统时间被回拨时不能显示「已等待 -3 分钟」
        val base = 1_756_000_000_000L
        assertEquals("刚刚开始", TaskListLogic.waitedText(base, base - 60_000L))
    }

    // ── 最近尝试 ──────────────────────────────────────────────────────────

    @Test
    fun `lastAttemptText returns null when never attempted`() {
        // null = 从未尝试。UI 侧据此显示「尚未尝试」——这本身就是异常信号，不能隐藏
        assertNull(TaskListLogic.lastAttemptText(lastAttemptAt = 0L, now = 10_000_000L))
    }

    @Test
    fun `lastAttemptText scales from seconds to hours`() {
        val base = 1_756_000_000_000L
        assertEquals("最近尝试：30 秒前", TaskListLogic.lastAttemptText(base, base + 30_000L))
        assertEquals("最近尝试：3 分钟前", TaskListLogic.lastAttemptText(base, base + 3 * 60_000L))
        assertEquals("最近尝试：2 小时前", TaskListLogic.lastAttemptText(base, base + 2 * 3_600_000L))
    }

    // ── 存活信息行（进行中任务卡上唯一的「后台还活着」证据）────────────────

    @Test
    fun `livenessText combines waiting, attempts and last attempt`() {
        val base = 1_756_000_000_000L
        val text = TaskListLogic.livenessText(
            createdAt = base,
            attempts = 23,
            lastAttemptAt = base + 12 * 60_000L,
            now = base + 12 * 60_000L + 40_000L,
        )
        assertEquals("已等待 12 分钟 · 尝试 23 次 · 最近尝试：40 秒前", text)
    }

    @Test
    fun `livenessText says not-yet-attempted instead of omitting it`() {
        // 「一直没尝试过」本身就是异常信号（服务可能没起来），必须让用户看见
        val base = 1_756_000_000_000L
        val text = TaskListLogic.livenessText(base, attempts = 0, lastAttemptAt = 0L, now = base + 60_000L)
        assertEquals("已等待 1 分钟 · 尝试 0 次 · 尚未尝试", text)
    }

    @Test
    fun `livenessText keeps attempts visible for legacy tasks without createdAt`() {
        // 老数据缺 createdAt 不能连累尝试次数 —— 次数是用户唯一能看到的进展信号
        val base = 1_756_000_000_000L
        val text = TaskListLogic.livenessText(createdAt = 0L, attempts = 5, lastAttemptAt = base, now = base + 120_000L)
        assertEquals("尝试 5 次 · 最近尝试：2 分钟前", text)
    }

    @Test
    fun `livenessText never renders a bogus wait from the epoch`() {
        // 若把 createdAt=0 当成 1970 纪元，会算出「已等待 56 年」这种鬼东西
        val text = TaskListLogic.livenessText(createdAt = 0L, attempts = 0, lastAttemptAt = 0L)
        assertTrue(text == null || !text.contains("年"))
    }

    // ── 签到倒计时 ────────────────────────────────────────────────────────

    @Test
    fun `signInCountdown reports remaining time and overdue separately`() {
        val now = 1_756_000_000_000L
        assertEquals("还剩 45 分钟" to false, TaskListLogic.signInCountdown(now + 45 * 60_000L, now))
        assertEquals("还剩不到 1 分钟" to false, TaskListLogic.signInCountdown(now + 30_000L, now))
        assertEquals("还剩 2 小时 0 分钟" to false, TaskListLogic.signInCountdown(now + 120 * 60_000L, now))
    }

    @Test
    fun `signInCountdown flags overdue at and after the deadline`() {
        val now = 1_756_000_000_000L
        // 恰好到点算超时（不能显示「还剩 0 分钟」——两者对用户的行动指引完全不同）
        assertEquals("已超时" to true, TaskListLogic.signInCountdown(now, now))
        assertEquals("已超时" to true, TaskListLogic.signInCountdown(now - 60_000L, now))
    }

    // ── 更新时间 ──────────────────────────────────────────────────────────

    @Test
    fun `updatedAgoText returns null before the first refresh`() {
        assertNull(TaskListLogic.updatedAgoText(lastUpdatedAt = 0L, now = 10_000_000L))
    }

    @Test
    fun `updatedAgoText distinguishes just-now from stale`() {
        val base = 1_756_000_000_000L
        assertEquals("刚刚更新", TaskListLogic.updatedAgoText(base, base + 3_000L))
        assertEquals("30 秒前更新", TaskListLogic.updatedAgoText(base, base + 30_000L))
        assertEquals("5 分钟前更新", TaskListLogic.updatedAgoText(base, base + 5 * 60_000L))
        assertEquals("3 小时前更新", TaskListLogic.updatedAgoText(base, base + 3 * 3_600_000L))
    }

    // ── 标签 ──────────────────────────────────────────────────────────────

    @Test
    fun `modeLabel distinguishes preference with and without a seat list`() {
        assertEquals("单次", TaskListLogic.modeLabel(task("a", mode = TaskMode.SINGLE)))
        assertEquals("监控", TaskListLogic.modeLabel(task("b", mode = TaskMode.MONITOR)))
        assertEquals("优先·不限", TaskListLogic.modeLabel(task("c", mode = TaskMode.PREFER)))
        assertEquals(
            "优先·指定",
            TaskListLogic.modeLabel(task("d", mode = TaskMode.PREFER, preferredSeats = listOf("018")))
        )
    }

    @Test
    fun `seatLabel prefers the preference list and falls back to a single seat`() {
        assertEquals(
            "018,020",
            TaskListLogic.seatLabel(task("a", mode = TaskMode.PREFER, preferredSeats = listOf("018", "020")))
        )
        assertEquals("A12", TaskListLogic.seatLabel(task("b", seatNo = "A12")))
        assertNull(TaskListLogic.seatLabel(task("c")))
    }

    @Test
    fun `timeRangeLabel renders both ends and degrades gracefully`() {
        assertEquals("08:00 - 12:00", TaskListLogic.timeRangeLabel(task("a", startTime = "08:00", endTime = "12:00")))
        assertEquals("08:00", TaskListLogic.timeRangeLabel(task("b", startTime = "08:00", endTime = "")))
        assertEquals("12:00", TaskListLogic.timeRangeLabel(task("c", startTime = "", endTime = "12:00")))
        assertEquals("-", TaskListLogic.timeRangeLabel(task("d", startTime = "", endTime = "")))
    }

    @Test
    fun `statusBadge covers every status`() {
        val keys = TaskStatus.entries.map { TaskListLogic.statusBadge(it).second }
        assertEquals(TaskStatus.entries.size, keys.distinct().size)
        assertTrue(keys.none { it.isBlank() })
    }
}
