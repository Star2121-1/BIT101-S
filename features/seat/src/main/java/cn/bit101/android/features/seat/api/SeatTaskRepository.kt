package cn.bit101.android.features.seat.api

import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.deserializeTasks
import cn.bit101.android.features.seat.model.serializeTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val TaskStatus.isTerminal: Boolean
    get() = this == TaskStatus.SUCCESS || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED

/**
 * 座位预约任务的唯一状态持有者。
 *
 * 任务状态需要被 ViewModel（展示）与前台服务（执行）**共同读写**，
 * 各自持有一份内存列表会导致状态分叉，因此集中在这里管理并负责落盘。
 * 对外只暴露只读的 [tasks] 与若干变更方法。
 *
 * 应用级单例，生命周期与进程一致，因此自带一个长驻 scope 做异步落盘。
 */
@Singleton
class SeatTaskRepository @Inject constructor(
    private val store: SeatTaskStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<ReservationTask>>(emptyList())
    val tasks: StateFlow<List<ReservationTask>> = _tasks.asStateFlow()

    @Volatile
    private var loaded = false

    private val loadMutex = Mutex()

    /** 待落盘 JSON。用单写入者串行写，避免并发写乱序导致恢复时读到更旧状态。 */
    private val pendingJson = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            pendingJson.filterNotNull().distinctUntilChanged().collect { store.tasks.set(it) }
        }
    }

    /** 仍在推进中的任务（等待中 / 运行中）。 */
    val activeTasks: List<ReservationTask>
        get() = _tasks.value.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.IDLE }

    /**
     * 从持久化存储载入一次。已载入过则直接返回。
     *
     * 必须真正「一次」且**并发安全**：ViewModel 与服务都会调用 ——
     * 若重复读取，后一次会用存储里的旧列表覆盖内存中刚新增的任务；
     * 若并发调用不加锁，第二个调用方会在数据尚未载入时就看到空列表。
     */
    suspend fun loadOnce() = loadMutex.withLock {
        if (loaded) return@withLock
        val restored = deserializeTasks(store.tasks.get())
        loaded = true
        if (restored.isEmpty()) return@withLock
        // 合并而非覆盖：载入期间可能已有新任务被加入内存
        _tasks.update { current ->
            if (current.isEmpty()) restored
            else current + restored.filterNot { r -> current.any { it.id == r.id } }
        }
    }

    fun add(task: ReservationTask) {
        _tasks.update { it + task }
        persist()
    }

    /** 更新状态。已是终态（成功 / 失败 / 已取消）的任务不再被覆盖。 */
    fun updateStatus(id: String, status: TaskStatus, message: String) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.id == id && !task.status.isTerminal) task.copy(status = status, message = message) else task
            }
        }
        persist()
    }

    fun cancel(id: String) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.id == id && !task.status.isTerminal) task.copy(status = TaskStatus.CANCELLED, message = "已手动取消") else task
            }
        }
        persist()
    }

    /** 终止所有在跑的任务并置为失败。用于认证失效这类无法继续的场景。 */
    fun stopAll(reason: String) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.IDLE) {
                    task.copy(status = TaskStatus.FAILED, message = reason)
                } else task
            }
        }
        persist()
    }

    private fun persist() {
        pendingJson.value = serializeTasks(_tasks.value)
    }
}
