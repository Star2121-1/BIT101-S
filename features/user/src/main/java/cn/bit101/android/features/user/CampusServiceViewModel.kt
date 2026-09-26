package cn.bit101.android.features.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.data.school.BalanceTrend
import cn.bit101.android.data.school.CampusCardBalanceStore
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.CampusNetResult
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
                _fetched.value = true
                // 校园网提醒（余额不足 / 流量阈值）：拿到数据 = 人正在校内，是唯一可靠的时机
                // （每日周期任务的固定时刻多半在校外，会被永远跳过）。各自内部去重。
                val info = result.infoOrNull
                runCatching {
                    NetFeeChecker.check(appContext, info)
                    NetFlowChecker.check(appContext, info)
                }
            } finally {
                _loading.value = false
            }
        }
    }
}
