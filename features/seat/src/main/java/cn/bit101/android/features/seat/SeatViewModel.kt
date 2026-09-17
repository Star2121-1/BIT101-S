package cn.bit101.android.features.seat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.user.base.LoginStatus
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
) : ViewModel() {

    companion object {
        private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"
    }

    private val seatSession = SeatSession(loginStatus)
    private val seatCasLogin = SeatCasLogin(loginStatus)
    val seatApi = SeatApi(loginStatus)

    private var _seatlibReady = MutableStateFlow(false)
    val seatlibReady: StateFlow<Boolean> = _seatlibReady.asStateFlow()

    private var _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private fun updateLoginState() {
        _isLoggedIn.value = seatApi.token.isNotEmpty()
    }

    init {
        viewModelScope.launch {
            // Use BIT101 login status immediately — no flash of login button
            val bit101LoggedIn = loginStatus.status.get()
            _isLoggedIn.value = bit101LoggedIn || seatApi.token.isNotEmpty()

            // Try silent auth (may work if user previously logged into seatlib via browser)
            val result = seatSession.authenticateSeatlib()
            if (result.isSuccess) {
                seatApi.token = result.getOrThrow().token
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
                        seatApi.token = result.getOrThrow().token
                        _isLoggedIn.value = true
                        Log.d("SeatViewModel", "re-auth success: token=${seatApi.token.take(8)}...")
                    }
                }
            }
        }
    }


    private val _tasks = MutableStateFlow<List<ReservationTask>>(emptyList())
    val tasks: StateFlow<List<ReservationTask>> = _tasks.asStateFlow()

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
            seatApi.token = token
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
            seatApi.token = silentToken
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
                        seatApi.token = t
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
        val task = ReservationTask(
            mode = mode, status = TaskStatus.RUNNING, areaId = areaId, seatNo = seatNo,
            reserveDate = reserveDate, campusName = campusName, floorName = floorName, areaName = areaName
        )
        _tasks.value = _tasks.value + task
        val job = viewModelScope.launch {
            when (mode) {
                TaskMode.SINGLE -> executeSingleReserve(task)
                TaskMode.MONITOR -> executeMonitor(task)
                TaskMode.PREFER -> executePreferReserve(task)
            }
        }
        taskJobs[task.id] = job
    }

    fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
        taskJobs.remove(taskId)
        _tasks.value = _tasks.value.map { if (it.id == taskId) it.copy(status = TaskStatus.CANCELLED, message = "已手动取消") else it }
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
        if (e?.message == "TOKEN_EXPIRED" || e?.cause?.message == "TOKEN_EXPIRED") {
            viewModelScope.launch {
                seatApi.token = ""
                seatSession.jwtToken = ""
                updateLoginState()
            }
        }
    }

    private suspend fun executeSingleReserve(task: ReservationTask) {
        updateTaskStatus(task.id, TaskStatus.IDLE, "正在查询座位树…")
        val treeResult = seatApi.getSeatTree(task.reserveDate)
        if (treeResult.isFailure) { handleApiError(treeResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.FAILED, "查询失败"); return }

        val datesResult = seatApi.getSeatDates(buildId = task.areaId)
        if (datesResult.isFailure) { handleApiError(datesResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.FAILED, "获取时段失败"); return }

        val dates = datesResult.getOrThrow()
        val todayDates = dates.filter { it.day == task.reserveDate }
        val segment = if (todayDates.isNotEmpty()) todayDates.first() else dates.first()
        val segId = segment.segmentId.takeIf { it.isNotBlank() } ?: "1"
        val startTime = segment.start.takeIf { it.isNotBlank() } ?: "08:00"
        val endTime = segment.end.takeIf { it.isNotBlank() } ?: "22:30"

        updateTaskStatus(task.id, TaskStatus.RUNNING, "正在查询座位 ${task.seatNo}…")
        val seatsResult = seatApi.getSeats(task.areaId, segId, task.reserveDate, startTime, endTime)
        if (seatsResult.isFailure) { handleApiError(seatsResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.FAILED, "查询座位失败"); return }

        val targetSeat = seatsResult.getOrThrow().find { it.no == task.seatNo }
        if (targetSeat == null) { updateTaskStatus(task.id, TaskStatus.FAILED, "未找到座位号 ${task.seatNo}"); return }

        updateTaskStatus(task.id, TaskStatus.RUNNING, "正在预约座位 ${task.seatNo}…")
        val result = seatApi.confirmSeat(targetSeat.id, segId)
        if (result.isSuccess && result.getOrNull() == true) {
            updateTaskStatus(task.id, TaskStatus.SUCCESS, "预约成功")
        } else {
            handleApiError(result.exceptionOrNull())
            updateTaskStatus(task.id, TaskStatus.FAILED, result.exceptionOrNull()?.message ?: "预约失败")
        }
    }

    private suspend fun executeMonitor(task: ReservationTask) {
        val datesResult = seatApi.getSeatDates(buildId = task.areaId)
        if (datesResult.isFailure) { handleApiError(datesResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.FAILED, "获取时段失败"); return }
        val dates = datesResult.getOrThrow()
        val todayDates = dates.filter { it.day == task.reserveDate }
        val segment = if (todayDates.isNotEmpty()) todayDates.first() else dates.first()
        val segId = segment.segmentId.takeIf { it.isNotBlank() } ?: "1"
        val startTime = segment.start.takeIf { it.isNotBlank() } ?: "08:00"
        val endTime = segment.end.takeIf { it.isNotBlank() } ?: "22:30"

        while (true) {
            val seatsResult = seatApi.getSeats(task.areaId, segId, task.reserveDate, startTime, endTime)
            if (seatsResult.isFailure) { handleApiError(seatsResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.RUNNING, "查询失败，重试中"); delay(10000); continue }

            val targetSeat = seatsResult.getOrNull()?.find { it.no == task.seatNo }
            if (targetSeat == null) { updateTaskStatus(task.id, TaskStatus.RUNNING, "未找到座位，继续监控…"); delay(10000); continue }

            if (targetSeat.status == SeatStatus.AVAILABLE) {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "座位可用！正在预约…")
                val result = seatApi.confirmSeat(targetSeat.id, segId)
                if (result.isSuccess && result.getOrNull() == true) { updateTaskStatus(task.id, TaskStatus.SUCCESS, "预约成功"); return }
                else { handleApiError(result.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.RUNNING, "预约失败，继续监控") }
            } else {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "座位被占，继续监控…")
            }
            delay(10000)
        }
    }

    private suspend fun executePreferReserve(task: ReservationTask) {
        val datesResult = seatApi.getSeatDates(buildId = task.areaId)
        if (datesResult.isFailure) { handleApiError(datesResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.FAILED, "获取时段失败"); return }
        val dates = datesResult.getOrThrow()
        val todayDates = dates.filter { it.day == task.reserveDate }
        val segment = if (todayDates.isNotEmpty()) todayDates.first() else dates.first()
        val segId = segment.segmentId.takeIf { it.isNotBlank() } ?: "1"
        val startTime = segment.start.takeIf { it.isNotBlank() } ?: "08:00"
        val endTime = segment.end.takeIf { it.isNotBlank() } ?: "22:30"

        while (true) {
            val seatsResult = seatApi.getSeats(task.areaId, segId, task.reserveDate, startTime, endTime)
            if (seatsResult.isFailure) { handleApiError(seatsResult.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.RUNNING, "查询失败，重试中"); delay(5000); continue }

            val availableSeats = seatsResult.getOrNull()?.filter { it.status == SeatStatus.AVAILABLE }?.sortedBy { it.no }
            if (!availableSeats.isNullOrEmpty()) {
                val targetSeat = availableSeats.find { it.no == task.seatNo } ?: availableSeats.first()
                updateTaskStatus(task.id, TaskStatus.RUNNING, "发现空闲座位 ${targetSeat.no}，正在预约…")
                val result = seatApi.confirmSeat(targetSeat.id, segId)
                if (result.isSuccess && result.getOrNull() == true) { updateTaskStatus(task.id, TaskStatus.SUCCESS, "预约成功，座位 ${targetSeat.no}"); return }
                else { handleApiError(result.exceptionOrNull()); updateTaskStatus(task.id, TaskStatus.RUNNING, "预约失败，继续尝试") }
            } else {
                updateTaskStatus(task.id, TaskStatus.RUNNING, "暂无空闲座位，等待中…")
            }
            delay(5000)
        }
    }

    private fun updateTaskStatus(taskId: String, status: TaskStatus, message: String) {
        _tasks.value = _tasks.value.map { if (it.id == taskId && it.status != TaskStatus.CANCELLED) it.copy(status = status, message = message) else it }
    }
}
