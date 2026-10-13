package cn.bit101.android.features.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.data.repo.base.LibBorrowRepo
import cn.bit101.android.data.repo.base.StudySpotRepository
import cn.bit101.android.data.school.BalanceTrend
import cn.bit101.android.data.school.CampusCardBalanceStore
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.school.StudySpotLogic
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
    private val studySpotRepository: StudySpotRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * 「现在去哪儿自习」的完整建议（**排好序的候选列表**）；`null` = 取不到或当下不该给
     * （**不给瞎建议**）。
     */
    private val _studySpotPlan = MutableStateFlow<StudySpotLogic.Plan?>(null)
    val studySpotPlan: StateFlow<StudySpotLogic.Plan?> = _studySpotPlan.asStateFlow()

    /**
     * 「换一个」选中的第几条候选。
     *
     * ⚠️ 换它**不打网络** —— 候选在取数时就算完了（见 `StudySpotRepository.plan`）。
     *    这个下标纯粹是「显示哪一条」，所以放在这里而不是再走一遍仓库。
     */
    private val _studySpotIndex = MutableStateFlow(0)
    val studySpotIndex: StateFlow<Int> = _studySpotIndex.asStateFlow()

    /**
     * 自习建议是否正在取。
     *
     * ⚠️ 这个建议要逐栋拉空教室（并发但仍是好几个请求），**不是瞬间就能有** ——
     * 没有它的话界面就是「什么都不显示，十几秒后突然冒出一张卡」，
     * 用户会以为功能坏了（座位页栽过同一个坑：没有反馈就当成没反应）。
     */
    private val _studySpotLoading = MutableStateFlow(false)
    val studySpotLoading: StateFlow<Boolean> = _studySpotLoading.asStateFlow()

    /** 切到下一个候选取舍（**本地**，不到最后一条不会绕回第一条之外的地方）。 */
    fun cycleStudySpot() {
        val size = _studySpotPlan.value?.options?.size ?: return
        if (size <= 1) return
        _studySpotIndex.value = (_studySpotIndex.value + 1) % size
    }

    /**
     * **只重算**「自习建议」—— 一卡通 / 校园网 / 借阅都不动。
     *
     * ## 什么时候必须调它：**跨过了节次边界**之后
     *
     * 卡片上那句「正在上 X」「空到 20:55」「空闲到明天」是**取数那一刻**按当时的节次
     * 渲染好的**字符串**。而 `init` 与 `LaunchedEffect(Unit)` 都只在**进组合**时跑 ——
     * **「切后台一会儿再回来」不会重新进组合**（`LaunchedEffect(Unit)` 的 key 是 `Unit`，
     * 不随 resume 重跑）⇒ 卡片会继续说几分钟到几小时前的话。
     *
     * ⚠️ 通律：**缓存里别放与当前时间/节次相关的东西**（桌面组件的快照栽过同一条）。
     *    这里做不到零缓存（空教室要逐栋请求），所以退一步：**在会变的那一刻重算**。
     *
     * ⚠️ **不打网络**：10 分钟 TTL 内 [cn.bit101.android.data.repo.DefaultStudySpotRepository]
     *    命中的是「楼 → 原始教室列表」缓存，重算只是读库 + 纯计算。
     *    过期了才真的重新逐栋请求 —— 那也正是我们想要的（顺便刷新了数据）。
     *
     * ⚠️ 换不换、要不要把「换一个」的下标收回，判在 [StudySpotLogic.planUpdate]（纯函数、有单测）：
     *    取不到数据时**不动**，数据没变时**不收回下标**。
     */
    fun refreshStudySpot() {
        viewModelScope.launch { loadStudySpot() }
    }

    /** 取一次自习建议并落到界面上 —— **唯一实现**，[refresh] 与 [refreshStudySpot] 都走它。 */
    private suspend fun loadStudySpot() {
        _studySpotLoading.value = true
        try {
            val fresh = runCatching { studySpotRepository.plan() }
                .getOrNull()
                ?.takeIf { it.hasContent }
            val update = StudySpotLogic.planUpdate(_studySpotPlan.value, fresh) ?: return
            if (update.resetIndex) _studySpotIndex.value = 0
            _studySpotPlan.value = update.plan
        } finally {
            _studySpotLoading.value = false
        }
    }

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
        // ⚠️ 自习建议**单独起一个协程**：它要逐栋拉空教室（最多 16 栋），
        //    不该拖慢一卡通 / 校园网这些秒回的数据。
        //    ⚠️ 走同一个方法 —— 「取数 → 判要不要换 → 落状态」只有一份实现，
        //       别在这里再写一遍（下标收不收回的规矩就在里面）。
        refreshStudySpot()
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
