package cn.bit101.android.features.schedule.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.repo.base.EclassRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「动态」页（延河课堂的课程动态）的 ViewModel。
 *
 * 取数完全交给 [EclassRepo]（它已经有并发 + 逐课程容错）；
 * 这里只管**加载态**与**失败不打扰用户** —— 拉不到就显示空态/登录引导，
 * 不弹错误弹窗：动态是「有就看看」的信息，不是必须完成的事。
 */
@HiltViewModel
internal class EclassActivityViewModel @Inject constructor(
    private val eclassRepo: EclassRepo,
) : ViewModel() {

    /** 是否正在加载（首次进入或下拉刷新）。 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 动态列表（最近的在最前）。 */
    private val _activities =
        MutableStateFlow<List<EclassActivityLogic.EclassActivity>>(emptyList())
    val activities: StateFlow<List<EclassActivityLogic.EclassActivity>> = _activities.asStateFlow()

    /**
     * 会话是否可用。
     *
     * 只在**列表为空**时才去问（多打一次请求）：用来区分「没登录」和
     * 「登录了但课程里还没有动态」—— 前者要给登录入口，后者只显示空态。
     */
    private val _sessionAlive = MutableStateFlow(true)
    val sessionAlive: StateFlow<Boolean> = _sessionAlive.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val list = runCatching { eclassRepo.fetchActivities() }.getOrDefault(emptyList())
                _activities.value = list
                _sessionAlive.value = if (list.isNotEmpty()) {
                    true
                } else {
                    // 空的时候再确认一次会话 —— 失败一律当作「未登录」，
                    // 这样至少能把登录入口给出来，而不是让用户对着空页面发呆
                    runCatching { eclassRepo.isSessionAlive() }.getOrDefault(false)
                }
            } finally {
                _loading.value = false
            }
        }
    }
}
