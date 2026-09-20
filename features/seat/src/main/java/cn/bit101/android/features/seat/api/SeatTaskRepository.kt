package cn.bit101.android.features.seat.api

import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.deserializeTasks
import cn.bit101.android.features.seat.model.isTerminal
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
        if (restored.isEmpty()) {
            // 存储为空，但内存里可能已有先到的任务（add 早于 loadOnce）：
            // 标记载入完成后必须补一次落盘，否则这些任务只活在内存里。
            persist()
            return@withLock
        }
        // 合并而非覆盖：载入期间可能已有新任务被加入内存
        _tasks.update { current ->
            if (current.isEmpty()) restored
            else current + restored.filterNot { r -> current.any { it.id == r.id } }
        }
        // 合并结果回写，让「磁盘上的旧任务 + 内存里的新任务」成为新的持久化基线
        persist()
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

    /**
     * 记一次预约尝试：`attempts + 1`、`lastAttemptAt = 当前时刻`。
     *
     * 由 [cn.bit101.android.features.seat.SeatMonitorService] 在每轮轮询真正发出预约请求时调用。
     * 存在的意义是给 UI 一个「后台还活着」的证据 —— 监控任务可能在系统杀进程、
     * 退避等待、或网络断开时长时间没有结果，光看「运行中」三个字用户无法判断它是否还在干活。
     *
     * ⚠️ 终态任务不再累加（与 [updateStatus] / [cancel] 一致的防护）。
     */
    fun markAttempt(id: String) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.id == id && !task.status.isTerminal) {
                    task.copy(attempts = task.attempts + 1, lastAttemptAt = System.currentTimeMillis())
                } else task
            }
        }
        persist()
    }

    /** 清除所有终态（成功 / 失败 / 已取消）任务，返回清除条数。进行中的任务不受影响。 */
    fun clearFinished(): Int {
        val before = _tasks.value.size
        _tasks.update { tasks -> tasks.filterNot { it.status.isTerminal } }
        val removed = before - _tasks.value.size
        if (removed > 0) persist()
        return removed
    }

    /**
     * 删除单条任务（长按菜单用）。
     *
     * 刻意不做「只能删终态」的限制：用户如果就是想扔掉一个在跑的任务，
     * 直接删掉也应生效 —— 服务的收集器会发现该任务消失而终止对应协程。
     */
    fun remove(id: String) {
        _tasks.update { tasks -> tasks.filterNot { it.id == id } }
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

    /**
     * 把当前内存列表排入落盘队列。
     *
     * ⚠️ **未载入时直接跳过**：`loadOnce()` 之前内存里只有本次进程刚新增的任务，
     * 此时写盘会用「不完整的内存列表」把存储里已有的历史任务整片抹掉 ——
     * 之后 `loadOnce()` 读回来自然就只剩新任务了。
     *
     * 这是真实缺陷（不是测试洁癖）：`add()` 与 `loadOnce()` 可能来自不同协程
     * （ViewModel 建任务 / 服务恢复任务），谁先跑到并不确定。
     * 跳过不写盘是安全的 —— `loadOnce()` 完成后会由后续的 `persist()` 补齐，
     * 且载入本身不会丢内存里的任务（见 [loadOnce] 的合并逻辑）。
     */
    private fun persist() {
        if (!loaded) return
        pendingJson.value = serializeTasks(_tasks.value)
    }
}
