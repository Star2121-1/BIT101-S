package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.model.ReservationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {

    private val _records = MutableStateFlow<List<ReservationRecord>>(emptyList())
    val records: StateFlow<List<ReservationRecord>> = _records.asStateFlow()

    @Volatile
    private var lastRefreshAt = 0L

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
        }.onFailure {
            SeatLog.w(TAG, "refresh my reservations failed: ${it.message}")
            if (it.message == SeatApi.TOKEN_EXPIRED || it.cause?.message == SeatApi.TOKEN_EXPIRED) {
                clear()
            }
        }
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
    }

    private companion object {
        const val TAG = "SeatReservationRepo"
    }
}
