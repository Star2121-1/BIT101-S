package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.SeatViolationLogic
import cn.bit101.android.features.seat.model.RenegeRecord
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.SeminarRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我的预约」的唯一持有者。
 *
 * ## 为什么要独立成一个仓库
 *
 * 这些记录原先只活在 `SeatViewModel` 的内存里（`_myReservations`），
 * 只有用户打开座位页才刷新一次。现在桌面组件也要显示**实时座位状态**
 * （已预约待签到 / 使用中 / 暂离），而组件自己**不联网**，所以必须有一个
 * 脱离 UI 生命周期、可被后台观察的数据源 —— 就是这里。
 *
 * ## 谁负责刷新
 *
 * - App 内：座位页进入时、预约/取消后（ViewModel 调用 [refresh]）
 * - 后台：`SeatWidgetPublisher` 周期性地调 [refreshIfStale]
 *
 * ⚠️ 本类**不做持久化**：记录里有时效性很强的状态（暂离、使用中），
 * 隔天恢复出来的"使用中"是错的。组件要显示的是「组装后的行」，
 * 那部分由 `SeatWidgetSnapshot` 负责持久化。
 */
@Singleton
class SeatReservationRepository @Inject constructor(
    private val seatApi: SeatApi,
    private val violationTracker: SeatViolationTracker,
) {

    private val _records = MutableStateFlow<List<ReservationRecord>>(emptyList())
    val records: StateFlow<List<ReservationRecord>> = _records.asStateFlow()

    /**
     * 「我的违约」（**图书馆服务端**的权威列表）。
     *
     * `null` = 还没拉到（没登录 / 网络失败）—— 与「拉到 0 条」是两回事，
     * UI 必须区分，不能把「不知道」显示成「没有违约」。
     */
    private val _reneges = MutableStateFlow<List<RenegeRecord>?>(null)
    val reneges: StateFlow<List<RenegeRecord>?> = _reneges.asStateFlow()

    /**
     * 「我的违约」里的**研讨室**那一类（`reneges` 的 `type=2`）。
     *
     * 与座位违约**分开保存**：两类各自计数（h5 也是两个页签），
     * 合并计数会凭空引入一个我们并不知道的规则口径。
     */
    private val _seminarReneges = MutableStateFlow<List<RenegeRecord>?>(null)
    val seminarReneges: StateFlow<List<RenegeRecord>?> = _seminarReneges.asStateFlow()

    /**
     * 「我的研讨间预约」（`/api/Member/seminar`）。
     *
     * `null` = 还没拉到（≠ 空列表）。
     */
    private val _seminars = MutableStateFlow<List<SeminarRecord>?>(null)
    val seminars: StateFlow<List<SeminarRecord>?> = _seminars.asStateFlow()

    @Volatile
    private var lastRefreshAt = 0L

    /** 上次查违约的时刻（见 [refresh] 里的节流说明）。 */
    @Volatile
    private var lastRenegeCheckAt = 0L

    /**
     * 拉取「我的预约」。
     *
     * 失败时**保留上一次的数据**而不是清空 —— 网络抖一下就把组件上的预约信息抹掉，
     * 会让人以为预约没了。认证失效（token 被清）时才清空，那种情况继续显示旧状态是误导。
     */
    suspend fun refresh(): Result<List<ReservationRecord>> {
        val result = seatApi.getMyReservations()
        result.onSuccess {
            _records.value = it
            lastRefreshAt = System.currentTimeMillis()
            // 顺便查违约：这是**新增违约通知的唯一驱动点**，App 内刷新与组件后台刷新
            // 都会经过这里，所以不开 App 也能收到提醒。
            //
            // ⚠️ 必须**自己再套一层节流**：`refresh()` 在后台最快每 5 分钟就被调一次
            // （提醒源），再叠两个请求等于每小时多打 24 次。违约是「几十分钟内知道就行」
            // 的信息，缓 30 分钟完全够。
            // ⚠️ 研讨间预约列表**不在这里拉**：那是纯界面数据，由「列表」页按需拉。
            if (System.currentTimeMillis() - lastRenegeCheckAt >= RENEGE_CHECK_INTERVAL_MS) {
                lastRenegeCheckAt = System.currentTimeMillis()
                // 失败无所谓（违约是「知道就好」的信息，不该拖累预约刷新）
                runCatching { refreshReneges() }
                    .onFailure { SeatLog.w(TAG, "refresh reneges failed: ${it.message}") }
                runCatching { refreshSeminarReneges() }
                    .onFailure { SeatLog.w(TAG, "refresh seminar reneges failed: ${it.message}") }
            }
        }.onFailure {
            SeatLog.w(TAG, "refresh my reservations failed: ${it.message}")
            if (it.message == SeatApi.TOKEN_EXPIRED || it.cause?.message == SeatApi.TOKEN_EXPIRED) {
                clear()
            }
        }
        return result
    }

    /**
     * 拉「我的违约」。
     *
     * 拿到后交给 [SeatViolationTracker] 比对「见过的集合」——有新条目就发通知。
     * 文件读写放 IO 线程（这个方法既可能从主线程的 ViewModel 调，也可能从后台调）。
     */
    suspend fun refreshReneges(): Result<List<RenegeRecord>> {
        val result = seatApi.getRenegeRecords(SeatViolationLogic.TYPE_SEAT)
        result.onSuccess { list ->
            _reneges.value = list
            withContext(Dispatchers.IO) { violationTracker.observe(list) }
        }.onFailure {
            SeatLog.w(TAG, "refresh reneges failed: ${it.message}")
        }
        return result
    }

    /**
     * 拉「研讨室违约」（`reneges` 的 `type=2`）。
     *
     * ⚠️ 与座位违约**各记各的**，不做合并 —— 合并出来的「总数」并不可靠
     * （规则原文只说「各类违约累计 5 次」，没说两类是否同一池子，别替服务端下结论）。
     */
    suspend fun refreshSeminarReneges(): Result<List<RenegeRecord>> {
        val result = seatApi.getRenegeRecords(SeatViolationLogic.TYPE_SEMINAR)
        result.onSuccess { list ->
            _seminarReneges.value = list
            withContext(Dispatchers.IO) { violationTracker.observe(list) }
        }.onFailure {
            SeatLog.w(TAG, "refresh seminar reneges failed: ${it.message}")
        }
        return result
    }

    /**
     * 拉「我的研讨间预约」。
     *
     * 研讨间与座位是同一套后端（见 `model/Seminar.kt`），所以放在同一个仓库里 ——
     * 页面刷新一次要拿两份数据，分开两个仓库只会让「先刷哪个」变成问题。
     */
    suspend fun refreshSeminars(): Result<List<SeminarRecord>> {
        val result = seatApi.getMySeminarReservations()
        result.onSuccess { _seminars.value = it }
            .onFailure { SeatLog.w(TAG, "refresh seminars failed: ${it.message}") }
        return result
    }

    /**
     * 距上次成功刷新超过 [maxAgeMs] 才真正请求。
     *
     * 组件后台刷新用它做节流：座位页的状态变化不频繁（签到、暂离、超时释放），
     * 没必要每次重绘都打一次接口。
     *
     * @return 是否真的发起了请求
     */
    suspend fun refreshIfStale(maxAgeMs: Long): Boolean {
        if (System.currentTimeMillis() - lastRefreshAt < maxAgeMs) return false
        refresh()
        return true
    }

    /** 登出 / 会话彻底失效时调用。 */
    fun clear() {
        _records.value = emptyList()
        lastRefreshAt = 0L
        lastRenegeCheckAt = 0L
        // 这三份都是「服务端才能回答」的数据 → 登出后回到**未知**（null），
        // 不能留在界面上冒充仍然有效。
        _reneges.value = null
        _seminarReneges.value = null
        _seminars.value = null
    }

    private companion object {
        const val TAG = "SeatReservationRepo"

        /** 后台顺带查违约的节流（见 [refresh]）。 */
        const val RENEGE_CHECK_INTERVAL_MS = 30 * 60_000L
    }
}
