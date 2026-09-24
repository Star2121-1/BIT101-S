package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.ActivitySettings
import cn.bit101.android.data.eclass.EclassRefreshBus
import cn.bit101.android.data.repo.base.EclassRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import cn.bit101.android.data.school.LexueUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「动态设置」页的 ViewModel。
 *
 * 三个持久化设置项走 [ActivitySettings]；两个「动作项」不入持久化，
 * 直接调已有能力：[EclassRepo.isSessionAlive] 查延河课堂会话、
 * [LexueUrls.home] 取乐学主页地址（由 DDL 页学士帽弹窗共用）。
 *
 * ⚠️ 乐学**没有会话检查接口**（对比延河课堂），所以那边只给入口、查不了登录态。
 */
@HiltViewModel
internal class ActivitySettingViewModel @Inject constructor(
    private val activitySettings: ActivitySettings,
    override val eclassRepo: EclassRepo,
    private val lexueUrls: LexueUrls,
    /** 跨页刷新总线：设置页发请求，活着的动态页 VM 收到就重新取数。 */
    private val eclassRefreshBus: EclassRefreshBus,
) : EclassSessionViewModel() {

    val onlyHomework = activitySettings.onlyHomework
    val limit = activitySettings.limit
    val showExpired = activitySettings.showExpired

    /** 条数上限的可选项 —— 不给自由输入，免得填出「只显示 1 条」这种怪值。 */
    val limitOptions = listOf(30, 60, 100)

    /** 当前环境下的乐学主页地址；空表示还没取到。 */
    private val _lexueHome = MutableStateFlow("")
    val lexueHome: StateFlow<String> = _lexueHome.asStateFlow()

    init {
        checkEclassSession()
        loadLexueHome()
    }

    fun setOnlyHomework(value: Boolean) = write { activitySettings.onlyHomework.set(value) }

    fun setLimit(value: Int) = write { activitySettings.limit.set(value) }

    fun setShowExpired(value: Boolean) = write { activitySettings.showExpired.set(value) }

    /**
     * 「重新拉取动态」：广播给动态页（活着就立刻刷新），并顺手复查会话状态。
     *
     * ⚠️ 这里**不自己拉数据**：动态页的数据在它自己的 VM 里，这里拉了也存不进去；
     * 总线（[EclassRefreshBus]）才是把两边接起来的通道。
     */
    fun pullActivities() {
        eclassRefreshBus.request(EclassRefreshBus.Kind.ACTIVITY)
        checkEclassSession()
    }

    private fun loadLexueHome() {
        viewModelScope.launch {
            _lexueHome.value = runCatching { lexueUrls.home() }.getOrDefault("")
        }
    }

    private fun write(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
