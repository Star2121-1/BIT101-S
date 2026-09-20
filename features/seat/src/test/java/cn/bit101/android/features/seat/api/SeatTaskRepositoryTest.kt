package cn.bit101.android.features.seat.api

import cn.bit101.android.config.common.SettingItem
import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.serializeTasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务状态机的行为。
 *
 * 仓储是 ViewModel（主线程）与前台服务（Default）**共享**的状态持有者，
 * 这里固定几件容易退化的事：终态不被覆盖、批量终止只影响在跑任务、
 * 恢复时合并而非覆盖。
 */
class SeatTaskRepositoryTest {

    private class FakeSeatTaskStore(initial: String = "") : SeatTaskStore {
        private val state = MutableStateFlow(initial)
        override val tasks: SettingItem<String> = object : SettingItem<String> {
            override val flow: Flow<String> = state
            override suspend fun get(): String = state.value
            override suspend fun set(value: String) { state.value = value }
        }
    }

    private fun repository(initial: String = "") = SeatTaskRepository(FakeSeatTaskStore(initial))

    private fun task(id: String, status: TaskStatus = TaskStatus.RUNNING) = ReservationTask(
        id = id,
        mode = TaskMode.MONITOR,
        status = status,
        areaId = "area-1",
        seatNo = "A12"
    )

    private fun SeatTaskRepository.statusOf(id: String) = tasks.value.first { it.id == id }.status

    @Test
    fun `updateStatus advances a running task`() {
        val repo = repository()
        repo.add(task("1"))

        repo.updateStatus("1", TaskStatus.RUNNING, "监控中")

        assertEquals(TaskStatus.RUNNING, repo.statusOf("1"))
        assertEquals("监控中", repo.tasks.value.single().message)
    }

    @Test
    fun `updateStatus cannot resurrect a succeeded task`() {
        val repo = repository()
        repo.add(task("1"))
        repo.updateStatus("1", TaskStatus.SUCCESS, "预约成功")

        repo.updateStatus("1", TaskStatus.RUNNING, "回到运行中")

        assertEquals(TaskStatus.SUCCESS, repo.statusOf("1"))
        assertEquals("预约成功", repo.tasks.value.single().message)
    }

    @Test
    fun `updateStatus cannot overwrite a failed task`() {
        val repo = repository()
        repo.add(task("1"))
        repo.updateStatus("1", TaskStatus.FAILED, "已停止")

        repo.updateStatus("1", TaskStatus.RUNNING, "假装还活着")

        assertEquals(TaskStatus.FAILED, repo.statusOf("1"))
    }

    @Test
    fun `cancel is ignored for terminal tasks`() {
        val repo = repository()
        repo.add(task("1"))
        repo.updateStatus("1", TaskStatus.SUCCESS, "预约成功")

        repo.cancel("1")

        assertEquals(TaskStatus.SUCCESS, repo.statusOf("1"))
    }

    @Test
    fun `cancel marks a running task as cancelled`() {
        val repo = repository()
        repo.add(task("1"))

        repo.cancel("1")

        assertEquals(TaskStatus.CANCELLED, repo.statusOf("1"))
    }

    @Test
    fun `stopAll only affects active tasks`() {
        val repo = repository()
        repo.add(task("active"))
        repo.add(task("done", TaskStatus.SUCCESS))
        repo.add(task("stopped", TaskStatus.CANCELLED))

        repo.stopAll("登录已失效，请重新登录")

        assertEquals(TaskStatus.FAILED, repo.statusOf("active"))
        assertEquals(TaskStatus.SUCCESS, repo.statusOf("done"))
        assertEquals(TaskStatus.CANCELLED, repo.statusOf("stopped"))
        assertEquals("登录已失效，请重新登录", repo.tasks.value.first { it.id == "active" }.message)
    }

    @Test
    fun `activeTasks contains only idle and running`() {
        val repo = repository()
        repo.add(task("idle", TaskStatus.IDLE))
        repo.add(task("running", TaskStatus.RUNNING))
        repo.add(task("success", TaskStatus.SUCCESS))
        repo.add(task("failed", TaskStatus.FAILED))
        repo.add(task("cancelled", TaskStatus.CANCELLED))

        assertEquals(setOf("idle", "running"), repo.activeTasks.map { it.id }.toSet())
    }

    @Test
    fun `add appends without dropping existing tasks`() {
        val repo = repository()
        repo.add(task("1"))
        repo.add(task("2"))

        assertEquals(listOf("1", "2"), repo.tasks.value.map { it.id })
    }

    @Test
    fun `loadOnce merges stored tasks with ones already in memory`() = runBlocking {
        // 服务与 ViewModel 都会调用 loadOnce：若用存储内容整体覆盖内存，
        // 用户刚创建的任务会被抹掉
        val repo = repository(serializeTasks(listOf(task("stored"))))
        repo.add(task("just-created"))

        repo.loadOnce()

        assertEquals(setOf("stored", "just-created"), repo.tasks.value.map { it.id }.toSet())
    }

    @Test
    fun `loadOnce restores into an empty repository`() = runBlocking {
        val repo = repository(serializeTasks(listOf(task("stored"))))

        repo.loadOnce()

        assertEquals(listOf("stored"), repo.tasks.value.map { it.id })
    }

    @Test
    fun `loadOnce runs only once`() = runBlocking {
        val repo = repository(serializeTasks(listOf(task("stored"))))
        repo.loadOnce()
        repo.add(task("extra"))

        // 第二次调用必须直接返回，否则会把内存里的 extra 丢掉
        repo.loadOnce()

        assertEquals(2, repo.tasks.value.size)
    }

    @Test
    fun `loadOnce on corrupt storage keeps memory intact`() = runBlocking {
        val repo = repository("这不是 JSON")
        repo.add(task("mine"))

        repo.loadOnce()

        assertEquals(listOf("mine"), repo.tasks.value.map { it.id })
        assertTrue(repo.tasks.value.isNotEmpty())
    }

    @Test
    fun `unknown task id is a no-op`() {
        val repo = repository()
        repo.add(task("1"))

        repo.updateStatus("nope", TaskStatus.SUCCESS, "x")
        repo.cancel("nope")

        assertEquals(TaskStatus.RUNNING, repo.statusOf("1"))
    }

    // ── 存活信息（attempts / lastAttemptAt）────────────────────────────────

    @Test
    fun `markAttempt increments attempts and stamps the time`() {
        val repo = repository()
        repo.add(task("1"))

        repo.markAttempt("1")
        repo.markAttempt("1")

        val t = repo.tasks.value.single()
        assertEquals(2, t.attempts)
        assertTrue("lastAttemptAt 应被写入", t.lastAttemptAt > 0L)
    }

    @Test
    fun `markAttempt is ignored for terminal tasks`() {
        // 与 updateStatus / cancel 一致的防护：任务已经结束了就不该再涨计数，
        // 否则已结束卡片上会显示一个一直在跳的数字，误导用户以为还在跑
        val repo = repository()
        repo.add(task("1"))
        repo.updateStatus("1", TaskStatus.SUCCESS, "预约成功")

        repo.markAttempt("1")

        assertEquals(0, repo.tasks.value.single().attempts)
        assertEquals(0L, repo.tasks.value.single().lastAttemptAt)
    }

    @Test
    fun `markAttempt on unknown id is a no-op`() {
        val repo = repository()
        repo.add(task("1"))

        repo.markAttempt("nope")

        assertEquals(0, repo.tasks.value.single().attempts)
    }

    // ── 清理 ──────────────────────────────────────────────────────────────

    @Test
    fun `clearFinished removes only terminal tasks`() {
        val repo = repository()
        repo.add(task("running", TaskStatus.RUNNING))
        repo.add(task("idle", TaskStatus.IDLE))
        repo.add(task("success", TaskStatus.SUCCESS))
        repo.add(task("failed", TaskStatus.FAILED))
        repo.add(task("cancelled", TaskStatus.CANCELLED))

        val removed = repo.clearFinished()

        assertEquals(3, removed)
        assertEquals(setOf("running", "idle"), repo.tasks.value.map { it.id }.toSet())
    }

    @Test
    fun `clearFinished on a list with nothing to remove returns zero`() {
        val repo = repository()
        repo.add(task("running", TaskStatus.RUNNING))

        assertEquals(0, repo.clearFinished())
        assertEquals(1, repo.tasks.value.size)
    }

    @Test
    fun `remove deletes a single task regardless of status`() {
        // 刻意允许删进行中的任务：用户就是想扔掉它，服务的收集器会发现它消失而终止协程
        val repo = repository()
        repo.add(task("running", TaskStatus.RUNNING))
        repo.add(task("done", TaskStatus.SUCCESS))

        repo.remove("running")

        assertEquals(listOf("done"), repo.tasks.value.map { it.id })
    }

    @Test
    fun `remove on unknown id leaves the list untouched`() {
        val repo = repository()
        repo.add(task("1"))

        repo.remove("nope")

        assertEquals(listOf("1"), repo.tasks.value.map { it.id })
    }

    @Test
    fun `cleared tasks are persisted so they do not come back after restart`() = runBlocking {
        // 清除必须落盘，否则下次冷启动 loadOnce 又会把已删除的任务读回来
        val store = FakeSeatTaskStore(serializeTasks(listOf(task("done", TaskStatus.SUCCESS))))
        val repo = SeatTaskRepository(store)
        repo.loadOnce()
        assertEquals(1, repo.tasks.value.size)

        repo.clearFinished()
        // 落盘是异步的（pendingJson 收集器），让出一轮让写入完成
        repeat(20) { kotlinx.coroutines.delay(10) }

        val restored = SeatTaskRepository(store)
        restored.loadOnce()
        assertTrue("清除后重启不应恢复出已结束的任务", restored.tasks.value.isEmpty())
    }
}
