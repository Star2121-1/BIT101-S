package cn.bit101.android.features.schedule.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.ActivitySettings
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.eclass.EclassRefreshBus
import cn.bit101.android.data.repo.base.EclassRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * 「动态」页（延河课堂的课程动态）的 ViewModel。
 *
 * 取数完全交给 [EclassRepo]（它已经有并发 + 逐课程容错）；
 * 这里只管**加载态**、**失败不打扰用户**（拉不到就显示空态/登录引导，
 * 不弹错误弹窗）与**按设置过滤**。
 *
 * 三项设置见 `ActivitySettings`：
 * - 只看作业 / 显示已过期 → 订阅后实时生效（见 [activities]）
 * - 条数上限 → 订阅后即时生效（截断在过滤之后，见 [activities]），
 *   同时它是**取数参数**，改了还要重新拉一次（见 init）
 */
@HiltViewModel
internal class EclassActivityViewModel @Inject constructor(
    private val eclassRepo: EclassRepo,
    private val activitySettings: ActivitySettings,
    /**
     * 延河课堂刷新总线 —— 动态设置页点「重新拉取动态」时，这个 VM 活着的话
     * 会立刻收到并重新取数（否则用户从设置页回来还得手动下拉）。
     */
    private val eclassRefreshBus: EclassRefreshBus,
) : ViewModel() {

    /** 是否正在加载（首次进入或下拉刷新）。 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * 最近一次取到的**原始**列表（未按设置过滤，已由 repo 排序 + 按**取数上限**截断）。
     *
     * ⚠️ 这里存的上限是 [EclassActivityLogic.fetchLimit] 算出的取数上限，
     * 「只看作业」时它已放大（多取才够过滤）；用户真正看到的条数由 [activities]
     * 在过滤之后截断，两者不要混。
     */
    private val _rawActivities =
        MutableStateFlow<List<EclassActivityLogic.EclassActivity>>(emptyList())

    /**
     * 展示列表 = 取数结果 × 四项设置（只看作业 / 显示已过期 / 条数上限 + 当前时间）。
     *
     * 用 combine 而不是在 refresh 里算完就存：用户去「动态设置」改完返回时，
     * 这个 ViewModel 还在同一个返回栈上、不会重新 init ——
     * 不订阅设置的话，改了要等下次刷新才生效。
     *
     * ⚠️ 过滤与截断的顺序在 [EclassActivityLogic.visible] 里锁着：**先过滤后截断**。
     */
    val activities: StateFlow<List<EclassActivityLogic.EclassActivity>> = combine(
        _rawActivities,
        activitySettings.onlyHomework.flow,
        activitySettings.showExpired.flow,
        activitySettings.limit.flow,
    ) { raw, onlyHomework, showExpired, limit ->
        EclassActivityLogic.visible(
            activities = raw,
            onlyHomework = onlyHomework,
            showExpired = showExpired,
            limit = limit,
            now = LocalDateTime.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 会话是否可用。
     *
     * 只在**列表为空**时才去问（多打一次请求）：用来区分「没登录」和
     * 「登录了但课程里还没有动态」—— 前者要给登录入口，后者只显示空态。
     *
     * ⚠️ 判据用**原始列表**而不是过滤后的：开了「只看作业」时过滤结果可能是空的，
     * 但那只是没有作业，不代表没登录。
     */
    private val _sessionAlive = MutableStateFlow(true)
    val sessionAlive: StateFlow<Boolean> = _sessionAlive.asStateFlow()

    init {
        refresh()
        // 条数上限是取数参数（repo 按它截断），改了必须重新拉一次
        viewModelScope.launch {
            activitySettings.limit.flow.drop(1).collect { refresh() }
        }
        // 打开「只看作业」也要重新取数：**之前那份原始列表是按不放大（未过滤）的上限取的**，
        // 直接拿去过滤会「吃不饱」—— 列表里作业稀疏时只剩几条，更早的作业在截断之外
        //（放大理由见 EclassActivityLogic.fetchLimit）。
        // 关掉时不用重取：手里那份（放大的）数据对「全部动态」同样够用。
        viewModelScope.launch {
            activitySettings.onlyHomework.flow
                .distinctUntilChanged()
                .drop(1)
                .collect { on -> if (on) refresh() }
        }
        // 设置页（或将来其它入口）点了「重新拉取动态」：活着就立刻重新取
        viewModelScope.launch {
            eclassRefreshBus.requests.collect { kind ->
                if (kind == EclassRefreshBus.Kind.ACTIVITY) refresh()
            }
        }
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val limit = activitySettings.limit.get()
                // ⚠️ 开「只看作业」时**多取一些**：过滤发生在截断之前，如果只按用户
                //    上限去取，混合列表里作业稀疏就会「吃不饱」（详见
                //    EclassActivityLogic.visible / fetchLimit 的说明）。
                val onlyHomework = runCatching { activitySettings.onlyHomework.get() }
                    .getOrDefault(false)
                val fetchLimit = EclassActivityLogic.fetchLimit(limit, onlyHomework)

                val list = runCatching { eclassRepo.fetchActivities(fetchLimit) }
                    .getOrDefault(emptyList())
                _rawActivities.value = list
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
