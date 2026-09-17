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
}
