package cn.bit101.android.features.seat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.seat.base.SeatTaskStore
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.config.user.base.SeatLoginStatus
import cn.bit101.android.features.seat.api.SeatApi
import cn.bit101.android.features.seat.api.SeatCasLogin
import cn.bit101.android.features.seat.api.SeatSession
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.deserializeTasks
import cn.bit101.android.features.seat.model.serializeTasks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

/** 座位图当前的查询参数，用于取消预约后按同样条件重新加载（避免日期/时段被回落值覆盖）。 */
data class SeatQuery(
    val areaId: String,
    val day: String,
    val segmentId: String,
    val startTime: String,
    val endTime: String
)

data class SeatMapState(
    val seats: List<Seat> = emptyList(),
    val areaName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val query: SeatQuery? = null
)

@HiltViewModel
class SeatViewModel @Inject constructor(
    private val loginStatus: LoginStatus,
    private val seatLoginStatus: SeatLoginStatus,
    private val seatTaskStore: SeatTaskStore,
) : ViewModel() {

    companion object {
        private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"

        /** 单个任务的最长运行时长：超过后自动停止，避免忘记取消导致无限轮询。 */
        private const val MAX_TASK_DURATION_MS = 2 * 60 * 60 * 1000L
        /** 轮询退避上限（5 分钟）。 */
        private const val MAX_POLL_INTERVAL_MS = 5 * 60 * 1000L
        /** 监控预约的基准轮询间隔。 */
        private const val MONITOR_INTERVAL_MS = 10_000L
        /** 优先预约的基准轮询间隔。 */
        private const val PREFER_INTERVAL_MS = 5_000L
    }

    private val seatSession = SeatSession(loginStatus)
    private val seatCasLogin = SeatCasLogin(loginStatus)
    val seatApi = SeatApi(loginStatus)

    private val _seatlibReady = MutableStateFlow(false)
    val seatlibReady: StateFlow<Boolean> = _seatlibReady.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private fun updateLoginState() {
        _isLoggedIn.value = seatApi.token.isNotEmpty()
    }

    /** 写入内存中的 seatlib token 并持久化，统一入口避免各处遗漏。 */
    private fun setSeatToken(value: String) {
        seatApi.token = value
        viewModelScope.launch { seatLoginStatus.token.set(value) }
    }

    init {
        viewModelScope.launch {
            // 1. 先恢复上次持久化的 token，冷启动即可免登录
            val saved = seatLoginStatus.token.get()
            if (saved.isNotEmpty()) {
                seatApi.token = saved
                Log.d("SeatViewModel", "restored persisted token=${saved.take(8)}...")
            }

            // 2. BIT101 登录状态立即决定 UI，避免闪现登录按钮
            val bit101LoggedIn = loginStatus.status.get()
            _isLoggedIn.value = bit101LoggedIn || seatApi.token.isNotEmpty()

            // 3. 尝试静默认证刷新 token；失败则沿用已恢复的 token（过期时由 401 兜底清理）
            val result = seatSession.authenticateSeatlib()
            if (result.isSuccess) {
                setSeatToken(result.getOrThrow().token)
                _isLoggedIn.value = true
                Log.d("SeatViewModel", "authenticateSeatlib success: token=${seatApi.token.take(8)}...")
            } else {
                Log.d("SeatViewModel", "authenticateSeatlib failed: ${result.exceptionOrNull()?.message}")
            }
            _seatlibReady.value = true
        }
        // Re-check when BIT101 login status changes
        viewModelScope.launch {
            loginStatus.status.flow.collect { loggedIn ->
                Log.d("SeatViewModel", "loginStatus changed: $loggedIn, token=${seatApi.token.take(8)}...")
                _isLoggedIn.value = loggedIn || seatApi.token.isNotEmpty()
                if (loggedIn && seatApi.token.isEmpty()) {
                    val result = seatSession.authenticateSeatlib()
                    if (result.isSuccess) {
                        setSeatToken(result.getOrThrow().token)
                        _isLoggedIn.value = true
                        Log.d("SeatViewModel", "re-auth success: token=${seatApi.token.take(8)}...")
                    }
                }
            }
        }
    }


    private val _tasks = MutableStateFlow<List<ReservationTask>>(emptyList())
    val tasks: StateFlow<List<ReservationTask>> = _tasks.asStateFlow()

    /**
     * 待落盘的任务列表 JSON。
     *
     * 用单一收集者串行写入 DataStore —— 若每次变更各自 `launch` 一个协程，多个挂起写入不保证 FIFO，
     * 崩溃恢复时可能读到比实际更旧的状态。
     */
    private val pendingTasksJson = MutableStateFlow<String?>(null)

    // 注意：必须放在 _tasks 声明之后。Kotlin 按声明顺序初始化，写在类顶部会读到未初始化的属性。
    init {
        viewModelScope.launch {
            val saved = seatTaskStore.tasks.get()
            val restored = deserializeTasks(saved)
            if (restored.isNotEmpty()) {
                // 上次「运行中 / 等待中」的任务已随进程结束，不能继续显示为运行中
                _tasks.value = restored.map { task ->
                    if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.IDLE) {
                        task.copy(status = TaskStatus.FAILED, message = "应用已重启，任务未继续运行")
                    } else task
                }
                persistTasks()
                Log.d("SeatViewModel", "restored ${restored.size} task(s)")
            }
        }
        // 单写入者：串行落盘保证顺序，distinctUntilChanged 避免重复写入
        viewModelScope.launch {
            pendingTasksJson.filterNotNull().distinctUntilChanged().collect { json ->
                seatTaskStore.tasks.set(json)
            }
        }
    }

    /** 请求把当前任务列表落盘（实际写入由上面的单写入者串行执行）。 */
    private fun persistTasks() {
        pendingTasksJson.value = serializeTasks(_tasks.value)
    }

    private val taskJobs = mutableMapOf<String, Job>()

    private val _seatTree = MutableStateFlow<List<SeatTreeNode>>(emptyList())
    val seatTree: StateFlow<List<SeatTreeNode>> = _seatTree.asStateFlow()

    private val _seatTreeError = MutableStateFlow<String?>(null)
    val seatTreeError: StateFlow<String?> = _seatTreeError.asStateFlow()

    private val _seatDates = MutableStateFlow<List<SeatDate>>(emptyList())
    val seatDates: StateFlow<List<SeatDate>> = _seatDates.asStateFlow()

    private val _seatMapState = MutableStateFlow(SeatMapState())
    val seatMapState: StateFlow<SeatMapState> = _seatMapState.asStateFlow()

    private val _navigateToSeatMap = MutableStateFlow<Pair<String, String>?>(null)
    val navigateToSeatMap: StateFlow<Pair<String, String>?> = _navigateToSeatMap.asStateFlow()

    fun clearNavigation() { _navigateToSeatMap.value = null }
    fun clearSeatTreeError() { _seatTreeError.value = null }

    fun navigateToSeatMap(areaId: String, day: String) {
        _navigateToSeatMap.value = areaId to day
    }

    private val _casLoginFlow = MutableStateFlow(false)
    val casLoginFlow: StateFlow<Boolean> = _casLoginFlow.asStateFlow()
    internal fun setCasLoginFlow(value: Boolean) { _casLoginFlow.value = value }

    /** Opens CAS login WebView. */
    fun openCasLoginScreen() {
        _casLoginFlow.value = true
    }

    /** Checks seatlib session; opens WebView login if needed. Returns true if session is active. */
    suspend fun ensureSeatlibSession(): Boolean {
        if (seatApi.token.isNotEmpty()) return true
        // 直接尝试静默认证，无需单独探测会话
        val token = seatCasLogin.trySilentAuth()
        if (token.isNotEmpty()) {
            setSeatToken(token)
            updateLoginState()
            return true
        }
        openCasLoginScreen()
        return false
    }

    /** Single entry point from CasLoginScreen: sync cookies → silent auth → optional ticket exchange. */
    suspend fun syncAndExchange(ticket: String? = null) {
        Log.d("SeatViewModel", "syncAndExchange: ticket=${ticket?.take(8)}..., currentTokenLen=${seatApi.token.length}")
        // 1. Sync WebView cookies to OkHttp CookieManager
        seatCasLogin.syncWebViewCookies()
        // 2. If already have token, done
        if (seatApi.token.isNotEmpty()) return
        // 3. Try silent auth first (may succeed if phpCAS session established)
        val silentToken = seatCasLogin.trySilentAuth()
        if (silentToken.isNotEmpty()) {
            setSeatToken(silentToken)
            updateLoginState()
            _casLoginFlow.value = false
            Log.d("SeatViewModel", "syncAndExchange success (silent): token=${silentToken.take(8)}...")
            return
        }
        // 4. If we have a ticket, exchange it
        if (!ticket.isNullOrEmpty()) {
            Log.d("SeatViewModel", "syncAndExchange: exchanging ticket=${ticket.take(8)}...")
            try {
                val res = OkHttpClient.Builder()
                    .cookieJar(seatCasLogin.createCookieJar())
                    .followRedirects(true).followSslRedirects(true).build()
                    .newCall(
                        Request.Builder()
                            .url("$SEATLIB_BASE/api/cas/user")
                            .header("Content-Type", "application/json")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .post(JSONObject().put("cas", ticket).toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                val body = res.body?.string() ?: "{}"
                res.close()
                val json = JSONObject(body)
                val member = json.optJSONObject("member")
                if (member != null && !member.isNull("token")) {
                    val t = member.optString("token", "")
                    if (t.isNotEmpty()) {
                        setSeatToken(t)
                        updateLoginState()
                        _casLoginFlow.value = false
                        Log.d("SeatViewModel", "syncAndExchange success (ticket): token=${t.take(8)}...")
                        return
                    }
                }
                Log.d("SeatViewModel", "syncAndExchange: ticket failed: $body")
            } catch (e: Exception) {
                Log.d("SeatViewModel", "syncAndExchange error: ${e.message}")
            }
        }
        Log.d("SeatViewModel", "syncAndExchange: still no token, keep login open")
    }
    fun loadSeatTree(date: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))) {
        viewModelScope.launch {
            Log.d("SeatViewModel", "loadSeatTree: date=$date, token=${seatApi.token}")
            val treeResult = seatApi.getSeatTree(date)
            Log.d("SeatViewModel", "getSeatTree result: ${treeResult.isSuccess}, error=${treeResult.exceptionOrNull()?.message}, size=${treeResult.getOrNull()?.size}")
            if (treeResult.isSuccess) {
                _seatTree.value = treeResult.getOrThrow()
                _seatTreeError.value = null
            } else {
                _seatTreeError.value = treeResult.exceptionOrNull()?.message
                handleApiError(treeResult.exceptionOrNull())
                return@launch
            }
            val datesResult = seatApi.getSeatDates()
            if (datesResult.isSuccess) {
                _seatDates.value = datesResult.getOrThrow()
            } else {
                handleApiError(datesResult.exceptionOrNull())
            }
        }
    }

    fun loadSeatsForMap(areaId: String, day: String, segmentId: String = "1", startTime: String = "08:00", endTime: String = "22:30") {
        val query = SeatQuery(areaId, day, segmentId, startTime, endTime)
        viewModelScope.launch {
            _seatMapState.value = _seatMapState.value.copy(isLoading = true, error = null, query = query)
            val areaName = _seatTree.value.find { it.id == areaId }?.name ?: ""
            val result = seatApi.getSeats(areaId, segmentId, day, startTime, endTime)
            if (result.isSuccess) {
                _seatMapState.value = SeatMapState(seats = result.getOrThrow(), areaName = areaName, isLoading = false, query = query)
            } else {
                handleApiError(result.exceptionOrNull())
                _seatMapState.value = _seatMapState.value.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "加载失败", query = query)
            }
        }
    }

    /**
     * 打开座位图。先确保时段数据就绪，再按解析出的时段参数加载座位。
     * 把时段解析放在这里而不是 UI 侧，可以避免 seatDates 尚未加载时用回落值先行请求、导致时段错发。
     */
    fun openSeatMap(areaId: String, day: String) {
        viewModelScope.launch {
            _seatMapState.value = _seatMapState.value.copy(isLoading = true, error = null)
            val params = resolveSegmentParams(areaId, day)
            val query = SeatQuery(areaId, day, params.segmentId, params.startTime, params.endTime)
            _seatMapState.value = _seatMapState.value.copy(query = query)
            val areaName = _seatTree.value.find { it.id == areaId }?.name ?: ""
            val result = seatApi.getSeats(areaId, params.segmentId, day, params.startTime, params.endTime)
            if (result.isSuccess) {
                _seatMapState.value = SeatMapState(seats = result.getOrThrow(), areaName = areaName, isLoading = false, query = query)
            } else {
                handleApiError(result.exceptionOrNull())
                _seatMapState.value = _seatMapState.value.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "加载失败", query = query)
            }
        }
    }

    private data class SegmentParams(val segmentId: String, val startTime: String, val endTime: String)

    /** 解析指定日期的时段参数；若时段数据未加载则先拉取，仍缺失时回落到默认时段。 */
    private suspend fun resolveSegmentParams(areaId: String, day: String): SegmentParams {
        var seg = _seatDates.value.firstOrNull { it.day == day }
        if (seg == null) {
            val r = seatApi.getSeatDates(areaId)
            if (r.isSuccess) {
                _seatDates.value = r.getOrThrow()
                seg = _seatDates.value.firstOrNull { it.day == day }
            }
        }
        fun String?.usable() = this?.takeIf { it.isNotBlank() && it != "null" }
        return SegmentParams(
            segmentId = seg?.segmentId.usable() ?: "1",
            startTime = seg?.start.usable() ?: "08:00",
            endTime = seg?.end.usable() ?: "22:30"
        )
    }

    suspend fun reserveSeat(seatId: String, segment: String): String? {
        val result = seatApi.confirmSeat(seatId, segment)
        return if (result.isSuccess && result.getOrNull() == true) null
        else result.exceptionOrNull()?.message ?: "预约失败，未知错误"
    }

    fun addTask(mode: TaskMode, campusName: String, floorName: String, areaName: String, areaId: String, seatNo: String, reserveDate: String) {
        // 单次预约不走任务流：它由 SeatMapScreen.reserveSeat() 直接完成，可以在座位图上精确挑座
        if (mode == TaskMode.SINGLE) return
        val task = ReservationTask(
            mode = mode, status = TaskStatus.RUNNING, areaId = areaId, seatNo = seatNo,
            reserveDate = reserveDate, campusName = campusName, floorName = floorName, areaName = areaName
        )
        _tasks.value = _tasks.value + task
        persistTasks()
        val job = viewModelScope.launch {
            when (mode) {
                TaskMode.MONITOR -> executeMonitor(task)
                TaskMode.PREFER -> executePreferReserve(task)
                // 已在函数入口拦截，不会走到这里
                TaskMode.SINGLE -> Unit
            }
        }
        taskJobs[task.id] = job
    }

    fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
        taskJobs.remove(taskId)
        _tasks.value = _tasks.value.map { task ->
            val terminal = task.status == TaskStatus.SUCCESS || task.status == TaskStatus.FAILED
            if (task.id == taskId && !terminal) task.copy(status = TaskStatus.CANCELLED, message = "已手动取消") else task
        }
        persistTasks()
    }

    /** 取消预约。返回 null 表示成功，否则返回错误信息。成功后按当前查看的日期与时段重新加载座位图。 */
    suspend fun cancelReservation(seatId: String): String? {
        val result = seatApi.cancelSeat(seatId)
        if (result.isFailure || result.getOrNull() != true) {
            return result.exceptionOrNull()?.message ?: "取消失败，未知错误"
        }
        val q = _seatMapState.value.query ?: return null
        loadSeatsForMap(
            areaId = q.areaId,
            day = q.day,
            segmentId = q.segmentId,
            startTime = q.startTime,
            endTime = q.endTime
        )
        return null
    }

    private fun handleApiError(e: Throwable?) {
        if (e?.message != "TOKEN_EXPIRED" && e?.cause?.message != "TOKEN_EXPIRED") return
        setSeatToken("")
        seatSession.jwtToken = ""
        updateLoginState()
        // 认证已失效：继续轮询只会白等并持续打请求，直接终止所有在跑的任务
        stopRunningTasks("登录已失效，请重新登录")
    }

    /** 终止所有在跑的任务并置为失败。用于认证失效这类无法继续的场景。 */
    private fun stopRunningTasks(reason: String) {
        taskJobs.values.forEach { it.cancel() }
        taskJobs.clear()
        _tasks.value = _tasks.value.map { task ->
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.IDLE) {
                task.copy(status = TaskStatus.FAILED, message = reason)
            } else task
        }
        persistTasks()
    }

    /** 解析任务对应日期的时段参数；拉取失败返回 null，由调用方决定如何处理。 */
    private suspend fun resolveTaskSegment(areaId: String, day: String): SegmentParams? {
        val r = seatApi.getSeatDates(buildId = areaId)
        if (r.isFailure) { handleApiError(r.exceptionOrNull()); return null }
        val dates = r.getOrThrow()
        val seg = dates.firstOrNull { it.day == day } ?: dates.firstOrNull() ?: return null
        fun String.usable() = takeIf { it.isNotBlank() && it != "null" }
        return SegmentParams(
            segmentId = seg.segmentId.usable() ?: "1",
            startTime = seg.start.usable() ?: "08:00",
            endTime = seg.end.usable() ?: "22:30"
        )
    }

    /** 指数退避：连续失败时每次翻倍，封顶 MAX_POLL_INTERVAL_MS。 */
    private fun backoff(current: Long): Long = minOf(current * 2, MAX_POLL_INTERVAL_MS)

    /** 监控预约：轮询指定座位，空出后立即预约。 */
    private suspend fun executeMonitor(task: ReservationTask) {
        val params = resolveTaskSegment(task.areaId, task.reserveDate)
            ?: run { updateTaskStatus(task.id, TaskStatus.FAILED, "获取时段失败"); return }

        val deadline = System.currentTimeMillis() + MAX_TASK_DURATION_MS
        var interval = MONITOR_INTERVAL_MS

        while (System.currentTimeMillis() < deadline) {
            val seatsResult = seatApi.getSeats(task.areaId, params.segmentId, task.reserveDate, params.startTime, params.endTime)
            if (seatsResult.isFailure) {
                handleApiError(seatsResult.exceptionOrNull())
                interval = backoff(interval)
                updateTaskStatus(task.id, TaskStatus.RUNNING, "查询失败，${interval / 1000}s 后重试")
                delay(interval)
                continue
            }
            interval = MONITOR_INTERVAL_MS

            val targetSeat = seatsResult.getOrNull()?.find { it.no == task.seatNo }
            if (targetSeat == null) {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "未找到座位，继续监控…")
                delay(interval)
                continue
            }

            if (targetSeat.status == SeatStatus.AVAILABLE) {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "座位可用！正在预约…")
                val result = seatApi.confirmSeat(targetSeat.id, params.segmentId)
                if (result.isSuccess && result.getOrNull() == true) {
                    updateTaskStatus(task.id, TaskStatus.SUCCESS, "预约成功")
                    return
                }
                handleApiError(result.exceptionOrNull())
                updateTaskStatus(task.id, TaskStatus.RUNNING, "预约失败，继续监控")
            } else {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "座位被占，继续监控…")
            }
            delay(interval)
        }
        updateTaskStatus(task.id, TaskStatus.FAILED, "已超过最长监控时长（2 小时），任务自动停止")
    }

    /** 优先预约：轮询区域内所有空闲座位，优先取指定座位号，否则取最早可用的。 */
    private suspend fun executePreferReserve(task: ReservationTask) {
        val params = resolveTaskSegment(task.areaId, task.reserveDate)
            ?: run { updateTaskStatus(task.id, TaskStatus.FAILED, "获取时段失败"); return }

        val deadline = System.currentTimeMillis() + MAX_TASK_DURATION_MS
        var interval = PREFER_INTERVAL_MS

        while (System.currentTimeMillis() < deadline) {
            val seatsResult = seatApi.getSeats(task.areaId, params.segmentId, task.reserveDate, params.startTime, params.endTime)
            if (seatsResult.isFailure) {
                handleApiError(seatsResult.exceptionOrNull())
                interval = backoff(interval)
                updateTaskStatus(task.id, TaskStatus.RUNNING, "查询失败，${interval / 1000}s 后重试")
                delay(interval)
                continue
            }
            interval = PREFER_INTERVAL_MS

            val availableSeats = seatsResult.getOrNull()?.filter { it.status == SeatStatus.AVAILABLE }?.sortedBy { it.no }
            if (!availableSeats.isNullOrEmpty()) {
                val targetSeat = availableSeats.find { it.no == task.seatNo } ?: availableSeats.first()
                updateTaskStatus(task.id, TaskStatus.RUNNING, "发现空闲座位 ${targetSeat.no}，正在预约…")
                val result = seatApi.confirmSeat(targetSeat.id, params.segmentId)
                if (result.isSuccess && result.getOrNull() == true) {
                    updateTaskStatus(task.id, TaskStatus.SUCCESS, "预约成功，座位 ${targetSeat.no}")
                    return
                }
                handleApiError(result.exceptionOrNull())
                updateTaskStatus(task.id, TaskStatus.RUNNING, "预约失败，继续尝试")
            } else {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "暂无空闲座位，等待中…")
            }
            delay(interval)
        }
        updateTaskStatus(task.id, TaskStatus.FAILED, "已超过最长优先预约时长（2 小时），任务自动停止")
    }

    /**
     * 更新任务状态。已是终态（成功/失败/已取消）的任务不再被覆盖 ——
     * 否则认证失效等场景下 stopRunningTasks 置的 FAILED 会被紧接着的状态更新改回 RUNNING。
     */
    private fun updateTaskStatus(taskId: String, status: TaskStatus, message: String) {
        _tasks.value = _tasks.value.map { task ->
            val terminal = task.status == TaskStatus.CANCELLED ||
                task.status == TaskStatus.FAILED ||
                task.status == TaskStatus.SUCCESS
            if (task.id == taskId && !terminal) task.copy(status = status, message = message) else task
        }
        persistTasks()
    }
}
