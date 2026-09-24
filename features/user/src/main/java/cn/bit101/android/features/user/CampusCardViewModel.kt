package cn.bit101.android.features.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.repo.base.CampusCardRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我」页「校园服务」卡片的数据。
 *
 * 首版只做**一卡通首页快照**（启发式解析余额，详见
 * [cn.bit101.android.data.repo.DefaultCampusCardRepo]）；校园网那边的
 * 接口形态未确认，先只给 WebView 入口，不在此取数。
 *
 * ⚠️ 快照**依赖 WebView 登录**：用户没在 App 内登录过一卡通时，
 * 首页会 302 到 CAS 登录页 → `loggedIn = false`，UI 显示「点开登录」
 * 而不是报错。登录是 WebView 的事，这里不负责。
 */
@HiltViewModel
internal class CampusCardViewModel @Inject constructor(
    private val campusCardRepo: CampusCardRepo,
) : ViewModel() {

    companion object {
        /** 一卡通首页（CAS service 指回这里）。 */
        const val CAMPUS_CARD_URL = "https://dkykt.info.bit.edu.cn/home/openHomePageByCas"

        /**
         * 校园网（深澜 Srun 缴费系统）的**登录页**。
         *
         * ⚠️ 不要直接开 `campus-service?entry=dingtalk`：那是 i北理（钉钉壳）
         * 的免登入口，在普通 WebView 里只会渲染出空壳。SPA 有独立的
         * `/client/signIn` 账号登录页，登录后才能看到网费/套餐。
         */
        const val CAMPUS_NET_URL = "https://netpay.bit.edu.cn:8091/client/signIn"
    }

    private val _snapshot = MutableStateFlow<CampusCardSnapshot?>(null)
    val snapshot: StateFlow<CampusCardSnapshot?> = _snapshot.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 是否已经至少取过一次（决定卡片显示「获取中」还是「点开登录」）。 */
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
                _snapshot.value = runCatching { campusCardRepo.fetchSnapshot() }.getOrNull()
                _fetched.value = true
            } finally {
                _loading.value = false
            }
        }
    }
}
