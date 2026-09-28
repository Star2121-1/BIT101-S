package cn.bit101.android.features.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.data.repo.base.LibBorrowRepo
import cn.bit101.android.data.school.BalanceTrend
import cn.bit101.android.data.school.CampusCardBalanceStore
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.CampusNetResult
import cn.bit101.android.data.school.CampusNetTrafficStore
import cn.bit101.android.data.school.LibBorrowResult
import cn.bit101.android.data.school.TrafficTrend
import cn.bit101.android.features.notify.LibBorrowChecker
import cn.bit101.android.features.notify.NetFeeChecker
import cn.bit101.android.features.notify.NetFlowChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 校园服务（一卡通 + 校园网）的唯一 ViewModel ——「我」页卡片与详情页共用。
 *
 * 两个数据源并发取，各自独立失败（一卡通靠 WebView 会话；校园网仅校园网内可达）。
 */
@HiltViewModel
internal class CampusServiceViewModel @Inject constructor(
    private val campusCardRepo: CampusCardRepo,
    private val campusNetRepo: CampusNetRepo,
    private val libBorrowRepo: LibBorrowRepo,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<CampusCardSnapshot?>(null)
    val snapshot: StateFlow<CampusCardSnapshot?> = _snapshot.asStateFlow()

    /**
     * 校园网结果；`null` = 还没取过。
     *
     * ⚠️ 用 [CampusNetResult] 而不是可空值：UI 要能区分「不在校内」「未认证」「请求失败」。
     */
    private val _netResult = MutableStateFlow<CampusNetResult?>(null)
    val netResult: StateFlow<CampusNetResult?> = _netResult.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 已完成过一次刷新（决定 UI 显示「获取中」还是降级文案）。 */
    private val _fetched = MutableStateFlow(false)
    val fetched: StateFlow<Boolean> = _fetched.asStateFlow()

    /**
     * 一卡通余额趋势（本地按次记录余额后算出来的「今日 / 近 N 天」变化）。
     *
     * 流水拿不到（钉钉客户端专属），这是替代方案 —— 见 [CampusCardBalanceLogic]。
     */
    private val _balanceTrend = MutableStateFlow(BalanceTrend())
    val balanceTrend: StateFlow<BalanceTrend> = _balanceTrend.asStateFlow()

    /**
     * 校园网流量趋势（本地按次记录累计用量后算出的「今日 / 日均 / 月末预测」）。
     *
     * ⚠️ 与余额趋势同一套路，但**多一件必须处理的事**：接口 `[6]` 是**按计费周期累计**的，
     * **换周期会归零** —— 采样必须检测重置，否则会算出「负的增量」这种荒谬结果
     * （见 [CampusNetTrafficLogic]）。
     */
    private val _trafficTrend = MutableStateFlow(TrafficTrend())
    val trafficTrend: StateFlow<TrafficTrend> = _trafficTrend.asStateFlow()

    /**
     * 图书馆借阅（当前在借）。`null` = 还没取过。
     *
     * ⚠️ 用 [LibBorrowResult] 而不是「可空列表」：UI 必须能区分
     * 「未登录（可处置）」「取不到（重试）」与「确实没有在借的书」——
     * 三件事塌成一个 null，就会在真正该提醒的时候告诉用户「你没借书」。
     */
    private val _libCurrent = MutableStateFlow<LibBorrowResult?>(null)
    val libCurrent: StateFlow<LibBorrowResult?> = _libCurrent.asStateFlow()

    /** 历史借阅（纯展示，失败不影响当前借阅）。 */
    private val _libHistory = MutableStateFlow<LibBorrowResult?>(null)
    val libHistory: StateFlow<LibBorrowResult?> = _libHistory.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val card = async { runCatching { campusCardRepo.fetchSnapshot() }.getOrNull() }
                val net = async {
                    runCatching { campusNetRepo.fetchOnlineInfo() }
                        .getOrElse { CampusNetResult.Failed(it.message ?: "未知异常") }
                }
                val lib = async {
                    runCatching { libBorrowRepo.fetchCurrent() }
                        .getOrElse { LibBorrowResult.Failed(it.message ?: "未知异常") }
                }
                val libHis = async {
                    runCatching { libBorrowRepo.fetchHistory() }
                        .getOrElse { LibBorrowResult.Failed(it.message ?: "未知异常") }
                }
                val snapshot = card.await()
                _snapshot.value = snapshot

                // 记一笔余额采样（细节见 CampusCardBalanceLogic）。
                // ⚠️ 文件读写在 IO 线程做：refresh 每次进页面都会跑，别把主线程卡住。
                // ⚠️ 只在「已登录 + 解析出金额」时记：未登录页面上那几个数字是登录页里的，
                //    记进去会污染趋势。
                _balanceTrend.value = withContext(Dispatchers.IO) {
                    val amount = snapshot
                        ?.takeIf { it.loggedIn && !it.failed }
                        ?.entries?.firstOrNull()?.second
                        ?.replace(",", "")     // 服务端可能给 `1,234.56`
                        ?.toDoubleOrNull()
                    CampusCardBalanceStore.record(appContext, amount)
                }

                val result = net.await()
                _netResult.value = result

                val borrow = lib.await()
                _libCurrent.value = borrow
                _libHistory.value = libHis.await()

                _fetched.value = true
                // 校园网提醒（余额不足 / 流量阈值）：拿到数据 = 人正在校内，是唯一可靠的时机
                // （每日周期任务的固定时刻多半在校外，会被永远跳过）。各自内部去重。
                val info = result.infoOrNull
                // 记一笔流量采样（同样在 IO 线程）。
                // ⚠️ 只有「在线」（拿到累计用量）才记 —— NotOnline / Failed 时 info 为 null ⇒
                //    不新增采样也不写盘，别用空值把趋势污染了。
                _trafficTrend.value = withContext(Dispatchers.IO) {
                    CampusNetTrafficStore.record(appContext, info?.bytesTotal)
                }
                runCatching {
                    NetFeeChecker.check(appContext, info)
                    NetFlowChecker.check(appContext, info)
                }
                // 图书馆借阅到期提醒：与校园网提醒同一条思路 ——
                // 「刚成功取到数据」是唯一可靠的时机（后台周期任务多半在校外/未登录而落空）。
                // ⚠️ 传的是**结果对象**而不是条数：未登录/失败要能区分出来，见 LibBorrowChecker。
                runCatching { LibBorrowChecker.check(appContext, borrow) }
            } finally {
                _loading.value = false
            }
        }
    }
}
