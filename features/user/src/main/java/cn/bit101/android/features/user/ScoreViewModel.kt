package cn.bit101.android.features.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.ScoreRepo
import cn.bit101.android.data.score.ScoreCheckStore
import cn.bit101.android.data.score.ScoreTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 成绩页的 ViewModel。
 *
 * ## 为什么不是「每次打开都拉一遍」
 * 取一次成绩要用学号密码走一遍学校统一身份认证，频繁登录会被要求短信二次验证
 * （2026-09-26 实测）。所以加载默认**吃缓存**（[ScoreRepo.load] 里 30 分钟新鲜期），
 * 只有下拉刷新 / 点重取才 `force = true`。
 *
 * ## 失败为什么要分类
 * `NOT_LOGGED_IN` / `AUTH_FAILED` / `NEED_SMS` / `FAILED` 四者的**下一步动作完全不同**
 * （去登录 / 去改密码 / 只能等 / 重试），给一句「加载失败」等于没说。
 */
@HiltViewModel
internal class ScoreViewModel @Inject constructor(
    private val scoreRepo: ScoreRepo,
) : ViewModel() {

    /**
     * 页面状态。
     *
     * ⚠️ 筛选条件存**已取消的项**还是**已选中的项**？这里存「选中集合」，
     * 并且「空集合 = 不过滤」—— 这样首次进入（还不知道有哪些学期）也是全显示。
     */
    data class UiState(
        val loading: Boolean = true,
        val table: ScoreTable.Table? = null,
        /** 数据的取到时间（缓存命中时是缓存的写入时间）。 */
        val dataAtMillis: Long? = null,
        /** 顶部「这份数据可能是旧的」提示；null = 就是最新的。 */
        val staleNote: String? = null,
        /** 一条数据都没有时的失败标题；null = 没失败。 */
        val errorTitle: String? = null,
        /** 失败的人话解释（服务端原文 / 我们推断的原因）。 */
        val errorDetail: String? = null,
        /** 此刻该做什么（「去登录」「改密码」「稍后重试」）。 */
        val errorAction: String? = null,
        val selectedTerms: Set<String> = emptySet(),
        val selectedTypes: Set<String> = emptySet(),
        val keyword: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(force = false)
    }

    /**
     * 加载。
     *
     * @param force true = 用户主动刷新，跳过新鲜度判断真的去登录取数。
     */
    fun load(force: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, errorTitle = null, errorDetail = null, errorAction = null) }
            when (val r = scoreRepo.load(force)) {
                is ScoreRepo.Load.Fresh -> applyTable(r.table, System.currentTimeMillis(), null)

                is ScoreRepo.Load.Cached -> {
                    val stale = r.reason?.let { reason ->
                        "这次没更新：${reasonText(reason)}；显示的是 ${formatTime(r.cachedAtMillis)} 的数据"
                    } ?: r.detail?.let { d -> "这次没更新：$d；显示的是 ${formatTime(r.cachedAtMillis)} 的数据" }
                    applyTable(r.table, r.cachedAtMillis, stale)
                }

                is ScoreRepo.Load.Malformed -> _state.update {
                    it.copy(
                        loading = false,
                        table = null,
                        errorTitle = "成绩表格式变了",
                        errorDetail = "数据拿到了，但里面找不到「课程名称 / 成绩」这些列 —— " +
                            "教务改版了，原生页这边还没跟上。先点右上角用网页版看。",
                        errorAction = null,
                    )
                }

                is ScoreRepo.Load.Failed -> _state.update {
                    it.copy(
                        loading = false,
                        table = null,
                        errorTitle = titleOf(r.code),
                        errorDetail = listOfNotNull(hintOf(r.code), r.detail?.takeIf { d -> d.isNotBlank() })
                            .joinToString("\n"),
                        errorAction = actionOf(r.code),
                    )
                }
            }
        }
    }

    private fun applyTable(
        parsed: ScoreTable.Table,
        atMillis: Long,
        staleNote: String?,
    ) {
        _state.update {
            it.copy(
                loading = false,
                table = parsed,
                dataAtMillis = atMillis,
                staleNote = staleNote,
                errorTitle = null,
                errorDetail = null,
                errorAction = null,
            )
        }
    }

    fun setKeyword(value: String) = _state.update { it.copy(keyword = value) }

    /** 切换一个学期筛选；空集合 = 全显示。 */
    fun toggleTerm(term: String) = _state.update {
        val next = if (term in it.selectedTerms) it.selectedTerms - term else it.selectedTerms + term
        // 全选和"没选"是同一件事（都等于不过滤），归一化成空集合，避免状态看起来不一样
        val all = it.table?.terms.orEmpty().toSet()
        it.copy(selectedTerms = if (next.isEmpty() || next == all) emptySet() else next)
    }

    fun toggleType(type: String) = _state.update {
        val next = if (type in it.selectedTypes) it.selectedTypes - type else it.selectedTypes + type
        val all = it.table?.courseTypes.orEmpty().toSet()
        it.copy(selectedTypes = if (next.isEmpty() || next == all) emptySet() else next)
    }

    fun clearFilters() = _state.update {
        it.copy(selectedTerms = emptySet(), selectedTypes = emptySet(), keyword = "")
    }

    // ------------------------------------------------------------ 文案

    private fun titleOf(code: ScoreCheckStore.Code): String = when (code) {
        ScoreCheckStore.Code.NOT_LOGGED_IN -> "还没登录学校账号"
        ScoreCheckStore.Code.AUTH_FAILED -> "学校没通过这次登录"
        ScoreCheckStore.Code.NEED_SMS -> "学校要求短信验证"
        ScoreCheckStore.Code.TIMEOUT -> "取数超时"
        ScoreCheckStore.Code.FAILED -> "没能取到成绩"
        ScoreCheckStore.Code.OK -> "没能取到成绩" // 不该走到这里
    }

    private fun hintOf(code: ScoreCheckStore.Code): String = when (code) {
        ScoreCheckStore.Code.NOT_LOGGED_IN ->
            "成绩要经学校统一身份认证，先去「我 → 设置 → 账号设置」登录校园账号。"
        ScoreCheckStore.Code.AUTH_FAILED ->
            "通常是学号或密码变了 —— 重试不会成功，请先去账号设置里改对密码。"
        ScoreCheckStore.Code.NEED_SMS ->
            "学校风控拦截了这次后台登录，只能去教务网页端手动确认一次，或过几个小时再试。"
        ScoreCheckStore.Code.TIMEOUT -> "学校那边一直没就绪，稍后再试。"
        ScoreCheckStore.Code.FAILED -> "网络或接口异常，稍后重试。"
        ScoreCheckStore.Code.OK -> ""
    }

    private fun actionOf(code: ScoreCheckStore.Code): String? = when (code) {
        ScoreCheckStore.Code.NOT_LOGGED_IN, ScoreCheckStore.Code.AUTH_FAILED -> "去账号设置"
        ScoreCheckStore.Code.NEED_SMS -> null
        else -> "重试"
    }

    private fun reasonText(code: ScoreCheckStore.Code): String = when (code) {
        ScoreCheckStore.Code.NOT_LOGGED_IN -> "没登录"
        ScoreCheckStore.Code.AUTH_FAILED -> "学校没通过登录"
        ScoreCheckStore.Code.NEED_SMS -> "学校要短信验证"
        ScoreCheckStore.Code.TIMEOUT -> "学校那边超时"
        ScoreCheckStore.Code.FAILED -> "网络或接口异常"
        ScoreCheckStore.Code.OK -> ""
    }

    private fun formatTime(millis: Long): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(fmt)
    }
}
