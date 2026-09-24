package cn.bit101.android.features.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.CampusNetInfo
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我」页「校园服务」卡片的数据：一卡通快照 + 校园网在线信息，一次刷新同时取。
 *
 * ⚠️ 一卡通快照**依赖 WebView 登录**：没登录过时首页 302 到 CAS →
 * `loggedIn = false`，UI 显示「点开登录」而不是报错。
 * 校园网数据免登录，但**仅校园网环境可取**（10.0.0.55 是内网地址），
 * 取不到时 `netInfo = null`。
 */
@HiltViewModel
internal class CampusCardViewModel @Inject constructor(
    private val campusCardRepo: CampusCardRepo,
    private val campusNetRepo: CampusNetRepo,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<CampusCardSnapshot?>(null)
    val snapshot: StateFlow<CampusCardSnapshot?> = _snapshot.asStateFlow()

    private val _netInfo = MutableStateFlow<CampusNetInfo?>(null)
    val netInfo: StateFlow<CampusNetInfo?> = _netInfo.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 已完成过一次刷新（决定卡片显示「获取中」还是「失败/点开登录」）。 */
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
                _netInfo.value = net.await()
                _fetched.value = true
            } finally {
                _loading.value = false
            }
        }
    }
}
