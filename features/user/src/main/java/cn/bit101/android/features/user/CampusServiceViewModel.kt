package cn.bit101.android.features.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.CampusNetInfo
import cn.bit101.android.features.notify.NetFeeChecker
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
 * 两个数据源并发取，各自独立失败（一卡通靠 WebView 会话；校园网仅校园网内可达）：
 * 任一失败其对应字段为 null，UI 自行降级，不互相影响。
 */
@HiltViewModel
internal class CampusServiceViewModel @Inject constructor(
    private val campusCardRepo: CampusCardRepo,
    private val campusNetRepo: CampusNetRepo,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<CampusCardSnapshot?>(null)
    val snapshot: StateFlow<CampusCardSnapshot?> = _snapshot.asStateFlow()

    private val _netInfo = MutableStateFlow<CampusNetInfo?>(null)
    val netInfo: StateFlow<CampusNetInfo?> = _netInfo.asStateFlow()

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
                val net = async { runCatching { campusNetRepo.fetchOnlineInfo() }.getOrNull() }
                _snapshot.value = card.await()
                val info = net.await()
                _netInfo.value = info
                _fetched.value = true
                // 网费不足提醒：拿到数据 = 人正在校内，是唯一可靠的判断时机
                // （每日周期任务的固定时刻多半在校外，会被永远跳过）。按天去重。
                runCatching { NetFeeChecker.check(appContext, info) }
            } finally {
                _loading.value = false
            }
        }
    }
}
