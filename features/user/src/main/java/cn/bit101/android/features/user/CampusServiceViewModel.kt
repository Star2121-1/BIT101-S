package cn.bit101.android.features.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.CampusNetResult
import cn.bit101.android.features.notify.NetFeeChecker
import cn.bit101.android.features.notify.NetFlowChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                _snapshot.value = card.await()
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
