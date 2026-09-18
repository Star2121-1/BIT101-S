package cn.bit101.android.features.seat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.seat.api.SeatApi
import cn.bit101.android.features.seat.api.SeatHttp
import cn.bit101.android.features.seat.api.SeatSession
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatMapImages
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    val query: SeatQuery? = null,
    /** 区域底图（按状态分色的五张图），为空时 UI 回落格子视图 */
    val images: SeatMapImages? = null
)

/**
 * 座位图的进入用途。
 *
 * - [RESERVE]：单次预约——只能点空闲座位，底部是「立即预约」
 * - [MONITOR]：监控预约——**单选**，占用座位也可点（那正是要等的目标）
 * - [PREFER]：优先预约——**多选**，按点选顺序排队抢
 */
enum class SeatPickMode { RESERVE, MONITOR, PREFER }

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

        /** 规则规定每天可取消 2 次（`/api/index/booking_rules`），服务端未提供计数接口。 */
        const val CANCELS_PER_DAY = 2
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

    // ── 选座（监控 / 优先模式的座位选择，替代手输座位号）────────────────────

    /** 当前座位图的用途；[SeatPickMode.RESERVE] 表示直接预约。 */
    private val _pickMode = MutableStateFlow(SeatPickMode.RESERVE)
    val pickMode: StateFlow<SeatPickMode> = _pickMode.asStateFlow()

    /** 已选座位（优先模式按点选顺序 = 优先级）。 */
    private val _pickedSeats = MutableStateFlow<List<Seat>>(emptyList())
    val pickedSeats: StateFlow<List<Seat>> = _pickedSeats.asStateFlow()

    /** 打开座位图，指定用途。监控/优先由 NewTaskScreen 调用。 */
    fun openSeatMapForPick(mode: SeatPickMode, areaId: String, day: String) {
        _pickMode.value = mode
        _pickedSeats.value = emptyList()
        openSeatMap(areaId, day)
    }

    /**
     * 进入座位图前先声明用途（导航由 SeatScreen 负责，数据加载由座位图页自己触发）。
     *
     * 与 [openSeatMapForPick] 的区别：这里**不发请求**，避免导航前多打一次座位接口。
     */
    fun preparePick(mode: SeatPickMode) {
        _pickMode.value = mode
        _pickedSeats.value = emptyList()
    }

    /** 单次预约路径：重置为预约语义。 */
    fun openSeatMapForReserve(areaId: String, day: String) {
        _pickMode.value = SeatPickMode.RESERVE
        openSeatMap(areaId, day)
    }

    /**
     * 点选/取消点选一个座位。
     *
     * 预约语义下由 SeatMapScreen 单独处理（选中即待预约），不走这里。
     */
    fun togglePick(seat: Seat) {
        val current = _pickedSeats.value
        _pickedSeats.value = when (_pickMode.value) {
            SeatPickMode.MONITOR -> if (current.any { it.id == seat.id }) emptyList() else listOf(seat)
            SeatPickMode.PREFER -> if (current.any { it.id == seat.id }) {
                current.filterNot { it.id == seat.id }
            } else {
                current + seat
            }
            SeatPickMode.RESERVE -> current
        }
    }

    fun clearPickedSeats() { _pickedSeats.value = emptyList() }

    /** 离开座位图时复位，避免下次进来还带着上次的选择。 */
    fun resetPick() {
        _pickedSeats.value = emptyList()
        _pickMode.value = SeatPickMode.RESERVE
    }

    // ── 「我的预约」（签到时限提醒 + 取消）────────────────────────────────

    private val _myReservations = MutableStateFlow<List<ReservationRecord>>(emptyList())
    val myReservations: StateFlow<List<ReservationRecord>> = _myReservations.asStateFlow()

    fun refreshMyReservations() {
        viewModelScope.launch {
            seatApi.getMyReservations()
                .onSuccess { _myReservations.value = it }
                .onFailure { SeatLog.d(TAG) { "load reservations failed: ${it.message}" } }
        }
    }

    /**
     * 今日剩余可取消次数。
     *
     * 服务端没有这个计数（规则写死在说明里：每天 2 次），只能在本地按天记录。
     * 仅统计**本 App 内**发起的取消，网页端或其他设备的取消不会计入 —— 所以只作提示。
     */
    private val cancelPrefs by lazy { appContext.getSharedPreferences("seat_cancels", Context.MODE_PRIVATE) }

    private fun todayKey(): String = java.time.LocalDate.now().toString()

    fun remainingCancelsToday(): Int {
        val storeDay = cancelPrefs.getString("day", "")
        if (storeDay != todayKey()) return CANCELS_PER_DAY
        return (CANCELS_PER_DAY - cancelPrefs.getInt("used", 0)).coerceAtLeast(0)
    }

    private fun markCancelUsed() {
        val used = if (cancelPrefs.getString("day", "") == todayKey()) cancelPrefs.getInt("used", 0) else 0
        cancelPrefs.edit().putString("day", todayKey()).putInt("used", used + 1).apply()
    }

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
                verifySessionOrLogout()
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

    /**
     * 用静默认证探一次会话；确认失效就清空 token。
     *
     * 为什么需要：**token 失效时，服务端连「无需认证」的接口也会返回 HTTP 500 + 空响应**
     * （2026-09-18 实测：带失效 token 请求 `/api/Seat/tree` → 500，空 body；
     * 不带 token 却是 200 + 正常数据）。这种情况下拿不到 10001 业务码，
     * [handleApiError] 判定不出来，表现就是「新建预约」页只报加载失败、
     * 而 UI 仍以为已登录、不给重新授权入口。
     */
    private suspend fun verifySessionOrLogout() {
        if (seatApi.token.isEmpty()) return
        val stillValid = seatSession.authenticateSeatlib().isSuccess
        if (!stillValid) {
            SeatLog.w(TAG, "session probe failed, clearing token to surface the auth gate")
            seatApi.token = ""
            repository.stopAll("登录已失效，请重新授权座位系统")
        }
    }

    /**
     * 按既有查询参数重新加载座位图（取消预约后刷新用）。
     *
     * 直接委托 [openSeatMap]：需要重算时段、重取底图、重新标记「我订的座位」，
     * 否则取消后那张座位仍会显示成「我已预约」。
     */
    fun loadSeatsForMap(
        areaId: String,
        day: String,
        segmentId: String = "1",
        startTime: String = "08:00",
        endTime: String = "22:30"
    ) {
        openSeatMap(areaId, day)
    }

    /**
     * 打开座位图。先确保时段数据就绪，再按解析出的时段参数加载座位。
     * 把时段解析放在这里而不是 UI 侧，可以避免 seatDates 尚未加载时用回落值先行请求、导致时段错发。
     *
     * 顺带并行取两样东西：
     * - 区域底图（`/api/seat/map`）——有则用真实房间图渲染
     * - 我的预约（`/api/index/subscribe`）——用于把「我订的座位」与其他人的预约区分开
     *   （服务端状态码 `2` 只表示「已预约」，不区分是谁订的）
     */
    fun openSeatMap(areaId: String, day: String) {
        viewModelScope.launch {
            _seatMapState.value = _seatMapState.value.copy(isLoading = true, error = null)
            val params = resolveSegmentParams(areaId, day)
            val query = SeatQuery(areaId, day, params.segmentId, params.startTime, params.endTime)
            _seatMapState.value = _seatMapState.value.copy(query = query)

            val imgDeferred = async { seatApi.getSeatMap(areaId) }
            val mineDeferred = async { seatApi.getMyReservations() }

            val areaName = _seatTree.value.find { it.id == areaId }?.name ?: ""
            val result = seatApi.getSeats(areaId, params.segmentId, day, params.startTime, params.endTime)

            val images = imgDeferred.await().getOrNull()
            val mineIds = mineDeferred.await().getOrNull()
                ?.filter { it.isActive }
                ?.map { it.seatId }?.toSet() ?: emptySet()

            if (result.isSuccess) {
                val seats = result.getOrThrow().map { if (it.id in mineIds) it.asMine() else it }
                _seatMapState.value = SeatMapState(
                    seats = seats, areaName = areaName, isLoading = false, query = query, images = images
                )
            } else {
                handleApiError(result.exceptionOrNull())
                verifySessionOrLogout()
                _seatMapState.value = _seatMapState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "加载失败",
                    query = query,
                    images = images
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

    fun addTask(
        mode: TaskMode,
        campusName: String,
        floorName: String,
        areaName: String,
        areaId: String,
        seatNo: String,
        reserveDate: String,
        /** 优先模式的偏好座位（按优先级），留空表示不限 */
        preferredSeats: List<String> = emptyList(),
    ) {
        // 单次预约不走任务流：它由 SeatMapScreen.reserveSeat() 直接完成，可以在座位图上精确挑座
        if (mode == TaskMode.SINGLE) return
        repository.add(
            ReservationTask(
                mode = mode, status = TaskStatus.RUNNING, areaId = areaId, seatNo = seatNo,
                preferredSeats = preferredSeats,
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
        return finishCancel(result)
    }

    /** 按**预约记录 id** 取消（「我的预约」列表用）。 */
    suspend fun cancelReservationById(recordId: String): String? {
        val result = seatApi.cancelReservation(recordId)
        return finishCancel(result)
    }

    private suspend fun finishCancel(result: Result<Boolean>): String? {
        if (result.isFailure || result.getOrNull() != true) {
            return result.exceptionOrNull()?.message ?: "取消失败，未知错误"
        }
        // 规则规定每天限取消 2 次，服务端无计数接口 → 本地按天累计，仅用于提示
        markCancelUsed()
        refreshMyReservations()
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
