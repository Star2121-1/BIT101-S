package cn.bit101.android.features.seat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.seat.api.SeatApi
import cn.bit101.android.features.seat.api.SeatHttp
import cn.bit101.android.features.seat.api.SeatSession
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    @ApplicationContext private val appContext: Context,
    private val loginStatus: LoginStatus,
    private val repository: SeatTaskRepository,
    private val seatHttp: SeatHttp,
    private val seatApi: SeatApi,
    private val seatSession: SeatSession,
) : ViewModel() {

    companion object {
        private const val TAG = "SeatViewModel"
    }

    private val _seatlibReady = MutableStateFlow(false)
    val seatlibReady: StateFlow<Boolean> = _seatlibReady.asStateFlow()

    /**
     * 座位功能是否可用 —— 判据是 **seatlib 会话（JWT）是否存在**，而不是 BIT101 的登录状态。
     *
     * 两者是独立会话：BIT101 已登录不代表 seatlib 已授权。早期用「BIT101 已登录」直接置
     * true，会出现 UI 显示已登录、但 token 为空、所有接口实际无认证的假象。
     */
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /** BIT101 学校账号是否已登录。用于区分「需要登录学校账号」与「需要授权座位系统」。 */
    private val _bit101LoggedIn = MutableStateFlow(false)
    val bit101LoggedIn: StateFlow<Boolean> = _bit101LoggedIn.asStateFlow()

    /** 会话失效提示。null 表示无提示。 */
    private val _authNotice = MutableStateFlow<String?>(null)
    val authNotice: StateFlow<String?> = _authNotice.asStateFlow()

    fun clearAuthNotice() { _authNotice.value = null }

    private fun onAuthSuccess() {
        _isLoggedIn.value = true
        _authNotice.value = null
    }

    init {
        viewModelScope.launch {
            // 1. 先恢复持久化的 token，冷启动即可免登录（持久化由 SeatApi 统一负责）
            val saved = seatApi.restoreToken()
            if (saved.isNotEmpty()) {
                SeatLog.d(TAG) { "restored persisted token=${SeatLog.mask(saved)}" }
            }
            _isLoggedIn.value = saved.isNotEmpty()
            _bit101LoggedIn.value = loginStatus.status.get()

            // 2. 尝试静默认证刷新 token。失败不影响已恢复的 token：
            //    若它其实已过期，首次 401 会走 handleApiError 自愈。
            seatSession.authenticateSeatlib()
                .onSuccess {
                    seatApi.token = it.token
                    onAuthSuccess()
                    SeatLog.d(TAG) { "silent auth ok, token=${SeatLog.mask(it.token)}" }
                }
                .onFailure { SeatLog.d(TAG) { "silent auth failed: ${it.message}" } }
            _seatlibReady.value = true

            // 3. 上次若还有未完成的任务，拉起前台服务续跑（进程被杀后也能自动接上）
            repository.loadOnce()
            val pending = repository.activeTasks
            if (pending.isNotEmpty()) {
                SeatLog.d(TAG) { "resuming ${pending.size} task(s)" }
                SeatMonitorService.start(appContext)
            }
        }

        // BIT101 登录状态变化时同步并尝试换取 seatlib 会话
        viewModelScope.launch {
            loginStatus.status.flow.collect { loggedIn ->
                _bit101LoggedIn.value = loggedIn
                when {
                    !loggedIn -> _isLoggedIn.value = seatApi.token.isNotEmpty()
                    seatApi.token.isEmpty() -> seatSession.authenticateSeatlib()
                        .onSuccess {
                            seatApi.token = it.token
                            onAuthSuccess()
                            SeatLog.d(TAG) { "re-auth ok, token=${SeatLog.mask(it.token)}" }
                        }
                }
            }
        }

        // token 被清空时同步 UI —— 前台服务侧遇到 401 也会清空 token，
        // 若不订阅这条流，服务已停止所有任务而界面仍显示「已登录」。
        viewModelScope.launch {
            var hadToken = false
            seatApi.tokenFlow.collect { value ->
                if (value.isNotEmpty()) {
                    hadToken = true
                    _authNotice.value = null
                    return@collect
                }
                // 只在「曾经有 token → 变空」时判定为失效，避免启动初期误报
                if (hadToken) {
                    hadToken = false
                    _isLoggedIn.value = false
                    _authNotice.value = "登录已失效，请重新登录"
                }
            }
        }
    }

    /**
     * 任务列表来自仓储（应用级单例），与前台服务共享同一份状态。
     * ViewModel 只负责「加任务 / 取消任务」，实际轮询由 [SeatMonitorService] 执行。
     */
    val tasks: StateFlow<List<ReservationTask>> = repository.tasks

    private val _seatTree = MutableStateFlow<List<SeatTreeNode>>(emptyList())
    val seatTree: StateFlow<List<SeatTreeNode>> = _seatTree.asStateFlow()

    private val _seatTreeError = MutableStateFlow<String?>(null)
    val seatTreeError: StateFlow<String?> = _seatTreeError.asStateFlow()

    private val _seatDates = MutableStateFlow<List<SeatDate>>(emptyList())
    val seatDates: StateFlow<List<SeatDate>> = _seatDates.asStateFlow()

    /** 可预约日期（来自 `/api/Seat/date`，去重升序）。供日期选择使用。 */
    val availableDays: StateFlow<List<String>> = _seatDates
        .map { dates -> dates.map { it.day }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** 打开 CAS 登录 WebView。 */
    fun openCasLoginScreen() {
        _casLoginFlow.value = true
    }

    /** 检查 seatlib 会话；必要时打开 WebView 登录。返回 true 表示会话可用。 */
    suspend fun ensureSeatlibSession(): Boolean {
        if (seatApi.token.isNotEmpty()) return true
        val result = seatSession.authenticateSeatlib()
        if (result.isSuccess) {
            seatApi.token = result.getOrThrow().token
            onAuthSuccess()
            return true
        }
        openCasLoginScreen()
        return false
    }

    /**
     * 账号密码直登（纯 HTTP CAS，不经 WebView）。
     *
     * 学校 SSO 登录页在 WebView 里不渲染表单（真机/模拟器均复现），
     * 而纯 HTTP 模拟 CAS 流程已在 JAVA 侧真机验证可用。成功返回 null，失败返回错误信息。
     */
    fun loginWithCredentials(username: String, password: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            seatSession.loginWithCredentials(username.trim(), password)
                .onSuccess {
                    seatApi.token = it.token
                    onAuthSuccess()
                    _casLoginFlow.value = false
                    onDone(null)
                }
                .onFailure { onDone(it.message ?: "登录失败") }
        }
    }

    /** CAS 登录页的统一回调：同步 cookie → 静默认证 → 用 ticket 换取。 */
    suspend fun syncAndExchange(ticket: String? = null) {
        SeatLog.d(TAG) {
            "syncAndExchange: ticket=${ticket?.let { SeatLog.mask(it) }}, tokenLen=${seatApi.token.length}"
        }
        // 1. 把 WebView 里的 cookie 同步到 OkHttp 使用的共享 store
        seatHttp.syncWebViewCookies()
        // 2. 已有会话则结束
        if (seatApi.token.isNotEmpty()) return
        // 3. 先试静默认证（phpCAS 会话已建立时无需 ticket）
        val silent = seatSession.authenticateSeatlib()
        if (silent.isSuccess) {
            seatApi.token = silent.getOrThrow().token
            onAuthSuccess()
            _casLoginFlow.value = false
            return
        }
        // 4. 有 ticket 就用它换取
        if (!ticket.isNullOrEmpty()) {
            val exchanged = seatSession.exchangeTicket(ticket)
            if (exchanged.isSuccess) {
                seatApi.token = exchanged.getOrThrow().token
                onAuthSuccess()
                _casLoginFlow.value = false
                return
            }
            SeatLog.d(TAG) { "ticket exchange failed: ${exchanged.exceptionOrNull()?.message}" }
        }
        SeatLog.d(TAG) { "still no token, keep login screen open" }
    }

    fun loadSeatTree(date: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))) {
        viewModelScope.launch {
            val treeResult = seatApi.getSeatTree(date)
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
                _seatMapState.value = _seatMapState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "加载失败",
                    query = query
                )
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
                _seatMapState.value = _seatMapState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "加载失败",
                    query = query
                )
            }
        }
    }

    private data class SegmentParams(val segmentId: String, val startTime: String, val endTime: String)

    /**
     * 解析指定日期的时段参数。
     *
     * ⚠️ 必须按区域 id（build_id=区域）拉取时段：服务端的 times[].id 只在
     * 传入区域 id 时才非空（2026-09-18 实测：区域 4 → id=355533；不带则恒 null）。
     * 早期版本「缓存优先」，而缓存来自 loadSeatTree 的无 build_id 调用（id 全空），
     * 于是永远命中空 id → 回落 "1" → confirm 直接 HTTP 500。
     * 现在始终按区域拉新，并把结果合并进缓存供 UI 使用。
     */
    private suspend fun resolveSegmentParams(areaId: String, day: String): SegmentParams {
        var seg: SeatDate? = null
        val r = seatApi.getSeatDates(areaId)
        if (r.isSuccess) {
            val fresh = r.getOrThrow()
            seg = fresh.firstOrNull { it.day == day }
            val freshDays = fresh.map { it.day }.toSet()
            _seatDates.value = _seatDates.value.filter { it.day !in freshDays } + fresh
        }
        if (seg == null) {
            seg = _seatDates.value.firstOrNull { it.day == day }
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
        if (result.isSuccess && result.getOrNull() == true) return null
        val e = result.exceptionOrNull()
        // 会话失效时清空 token 并终止任务，UI 回到登录门禁（否则下次仍拿着死 token 请求）
        handleApiError(e)
        return if (e?.message == SeatApi.TOKEN_EXPIRED || e?.cause?.message == SeatApi.TOKEN_EXPIRED)
            "登录已失效，请重新授权座位系统"
        else e?.message ?: "预约失败，未知错误"
    }

    fun addTask(mode: TaskMode, campusName: String, floorName: String, areaName: String, areaId: String, seatNo: String, reserveDate: String) {
        // 单次预约不走任务流：它由 SeatMapScreen.reserveSeat() 直接完成，可以在座位图上精确挑座
        if (mode == TaskMode.SINGLE) return
        repository.add(
            ReservationTask(
                mode = mode, status = TaskStatus.RUNNING, areaId = areaId, seatNo = seatNo,
                reserveDate = reserveDate, campusName = campusName, floorName = floorName, areaName = areaName
            )
        )
        // 把执行交给前台服务，退到后台 / 锁屏也能继续轮询
        SeatMonitorService.start(appContext)
    }

    fun cancelTask(taskId: String) {
        // 只改状态：服务的任务流收集器会发现该任务不再活跃并终止对应协程
        repository.cancel(taskId)
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

    /**
     * 统一的认证失效处理。
     *
     * 清空 token 会通过 [SeatApi.tokenFlow] 传导到 UI（见 init 中的收集器），
     * 同时终止所有在跑的任务 —— 认证已失效时继续轮询只会白等并持续打请求。
     */
    private fun handleApiError(e: Throwable?) {
        if (e?.message != SeatApi.TOKEN_EXPIRED && e?.cause?.message != SeatApi.TOKEN_EXPIRED) return
        SeatLog.w(TAG, "session expired, clearing token and stopping tasks")
        seatApi.token = ""
        repository.stopAll("登录已失效，请重新登录")
    }
}
