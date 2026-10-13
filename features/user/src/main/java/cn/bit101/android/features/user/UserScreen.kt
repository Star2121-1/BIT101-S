package cn.bit101.android.features.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.ScheduleTabs
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.school.CampusCardBalanceLogic
import cn.bit101.android.data.school.CampusCardLogic
import cn.bit101.android.data.school.CampusNetLogic
import cn.bit101.android.data.school.CampusNetResult
import cn.bit101.android.data.school.StudySpotLogic
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.component.Avatar
import cn.bit101.android.features.common.component.CircularProgressIndicatorForPage
import cn.bit101.android.features.common.component.ErrorMessageForPage
import cn.bit101.android.features.common.component.gallery.PosterCard
import cn.bit101.android.features.common.component.loadable.LoadableLazyColumnWithoutPullRequest
import cn.bit101.android.features.common.component.loadable.LoadableLazyColumnWithoutPullRequestState
import cn.bit101.android.features.common.component.loadable.rememberLoadableLazyColumnWithoutPullRequestState
import cn.bit101.android.features.common.component.user.UserInfoContent
import cn.bit101.android.features.common.component.user.UserInfoContentForMe
import cn.bit101.android.features.common.component.user.UserInfoTopAppBar
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.api.model.common.Image
import cn.bit101.api.model.http.bit101.GetPostersDataModel
import cn.bit101.api.model.http.bit101.GetUserInfoDataModel
import androidx.core.graphics.toColorInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserScreenContent(    mainController: MainController,
    data: GetUserInfoDataModel.Response,
    posters: List<GetPostersDataModel.ResponseItem>,
    state: LoadableLazyColumnWithoutPullRequestState,
    hide: Boolean,
    loadState: SimpleState?,
    followState: SimpleState?,
    hideState: SimpleState?,
    showBack: Boolean,
    unreadMessageCount: Int,

    onFollow: () -> Unit,
    onHide: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMessage: () -> Unit,
    onOpenFollowerPage: () -> Unit,
    onOpenFollowingPage: () -> Unit,
    onOpenPoster: (Long) -> Unit,
    onOpenImages: (Int, List<Image>) -> Unit,
) {
    val cm = LocalClipboardManager.current

    val topAppBarBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        snapAnimationSpec = null,
        flingAnimationSpec = null,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarBehavior.nestedScrollConnection),
        topBar = {
            UserInfoTopAppBar(
                title = {
                    Column {
                        Spacer(modifier = Modifier.padding(4.dp))
                        if (data.own) {
                            UserInfoContentForMe(
                                data = data,
                                onOpenFollowerDialog = onOpenFollowerPage,
                                onOpenFollowingDialog = onOpenFollowingPage,
                                onCopyText = { mainController.copyText(cm, it) },
                                onShowImage = { mainController.showImage(it) },
                                onOpenPoster = { mainController.navigate(NavDest.Poster(it)) },
                                onOpenUser = { mainController.navigate(NavDest.User(it)) },
                                onOpenUrlInternal = { mainController.navigate(NavDest.Web(it)) },
                            )
                        } else {
                            UserInfoContent(
                                data = data,
                                hide = hide,
                                following = followState is SimpleState.Loading,
                                hiding = hideState is SimpleState.Loading,
                                onFollow = onFollow,
                                onHide = onHide,
                                onCopyText = { mainController.copyText(cm, it) },
                                onShowImage = { mainController.showImage(it) },
                                onOpenPoster = { mainController.navigate(NavDest.Poster(it)) },
                                onOpenUser = { mainController.navigate(NavDest.User(it)) },
                                onOpenUrlInternal = { mainController.navigate(NavDest.Web(it)) },
                            )
                        }
                        Spacer(modifier = Modifier.padding(8.dp))
                    }
                },
                scrollBehavior = topAppBarBehavior,
                smallTitle = {
                    Row(
                        modifier = Modifier.wrapContentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            user = data.user,
                            low = true,
                            size = 32.dp,
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .align(Alignment.CenterVertically)
                        ) {
                            Text(
                                text = data.user.nickname,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = data.user.identity.text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if(data.user.identity.id == 0) {
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                    }
                                    else {
                                        Color(data.user.identity.color.toColorInt())
                                    }
                                ),
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { mainController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "back"
                            )
                        }
                    }
                },
                actions = {
                    if (data.own) {
                        IconButton(onClick = onRefresh) {
                            Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                        Box {
                            IconButton(onClick = onOpenMessage) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "消息",
                                )
                            }
                            // ⚠️ 未读为 0 时不要画角标 —— 否则消息图标上永远挂着一个「0」
                            if (unreadMessageCount > 0) {
                                Badge(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                ) {
                                    Text(text = unreadMessageCount.toString())
                                }
                            }
                        }

                        IconButton(onClick = onOpenSettings) {
                            Icon(imageVector = Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LoadableLazyColumnWithoutPullRequest(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 16.dp),
            state = state,
            loading = loadState == SimpleState.Loading,
        ) {
            // 「现在去哪儿自习」（仅本人页）：放在校园服务卡**更上面** ——
            // 这是当下最该知道的事，比查余额更即时。
            if (data.own) {
                item(key = "study-spot") {
                    StudySpotSection(mainController = mainController)
                    Divider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }
            // 校园服务卡（仅本人页显示）：一卡通余额摘要 + 校园网入口。
            // 放在信息流最顶上 —— 查余额是高频操作，不该滚去找。
            if (data.own) {
                item(key = "campus-services") {
                    CampusServiceSection(mainController = mainController)
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(0.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }
            posters.forEachIndexed { index, poster ->
                item(index + 100) {
                    PosterCard(
                        data = poster,
                        onOpenPoster = { onOpenPoster(poster.id) },
                        onOpenImages = onOpenImages,
                    )
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(0.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }
        }
    }
}

@Composable
fun UserScreen(
    mainController: MainController,
    id: Long = 0,
) {

    val vm: UserViewModel = hiltViewModel()

    val getUserInfoState by vm.getUserInfoStateFlow.collectAsState()

    val unreadMessageCount by vm.unreadMessageCountFlow.collectAsState()

    val posters by vm.posterStateExport.dataFlow.collectAsState()
    val postersRefreshState by vm.posterStateExport.refreshStateFlow.collectAsState()
    val postersLoadMoreState by vm.posterStateExport.loadMoreStateFlow.collectAsState()

    val followState by vm.followStateMutableLiveData.observeAsState()
    val hideState by vm.hideStateMutableLiveData.observeAsState()

    val hideUserUids by vm.hideUserUidsFlow.collectAsState(initial = emptyList())

    val uploadUserInfoState by vm.uploadUserInfoStateLiveData.observeAsState()

    DisposableEffect(uploadUserInfoState) {
        if (uploadUserInfoState is SimpleState.Success) {
            vm.uploadUserInfoStateLiveData.value = null
            mainController.snackbar("保存成功OvO")
        } else if (uploadUserInfoState is SimpleState.Fail) {
            vm.uploadUserInfoStateLiveData.value = null
            mainController.snackbar("保存失败Orz")
        }
        onDispose { }
    }

    LaunchedEffect(getUserInfoState) {
        if (getUserInfoState == null) {
            vm.getUserInfo(id)
        }
    }

    LaunchedEffect(postersRefreshState) {
        if (postersRefreshState == null) {
            vm.posterStateExport.refresh(id)
        }
    }

    if(getUserInfoState == null || postersRefreshState == null) {
        return
    } else if(getUserInfoState is SimpleDataState.Loading || postersRefreshState is SimpleState.Loading) {
        CircularProgressIndicatorForPage()
    } else if(getUserInfoState is SimpleDataState.Success && postersRefreshState is SimpleState.Success) {
        val data = (getUserInfoState as SimpleDataState.Success).data
        val hidden by remember(hideUserUids) { mutableStateOf(hideUserUids.contains(id.toInt())) }
        UserScreenContent(
            mainController = mainController,
            data = data,
            posters = posters,

            state = rememberLoadableLazyColumnWithoutPullRequestState(
                onLoadMore = { vm.posterStateExport.loadMore(id) }
            ),
            hide = hidden,
            loadState = postersLoadMoreState,
            followState = followState,
            hideState = hideState,
            showBack = id != 0L,
            unreadMessageCount = (unreadMessageCount as? SimpleDataState.Success)?.data ?: 0,

            onOpenImages = mainController::showImages,
            onOpenPoster = { mainController.navigate(NavDest.Poster(it)) },
            onFollow = { vm.follow(id) },
            onHide = { vm.hide(id) },
            onRefresh = { vm.refreshUserInfo(id) },
            onOpenSettings = { mainController.navigate(NavDest.Setting()) },
            onOpenMessage = { mainController.navigate(NavDest.Message) },
            onOpenFollowerPage = { mainController.navigate(NavDest.FollowList(NavDest.FollowList.FOLLOWER)) },
            onOpenFollowingPage = { mainController.navigate(NavDest.FollowList(NavDest.FollowList.FOLLOWING)) },
        )
    } else {
        // ⚠️ BIT101 接口失败时**校园服务卡仍要显示**：一卡通/校园网走的是
        // 学校域（dkykt/netpay），与 BIT101 的可用性无关 —— 2026-09-24 实测
        // BIT101 全站宕机时「我」页整页变成错误页，校园卡被连带挡住。
        Column {
            if (id == 0L) {
                CampusServiceSection(mainController = mainController)
            }
            ErrorMessageForPage()
        }
    }
}

/**
 * 「现在去哪儿自习」卡片（仅本人页）。
 *
 * ⚠️⚠️ **空闲态哪怕一间候选都没查到，也必须出卡** —— 说「现在没课」+「暂未查到空教室」两句真话。
 * 这里曾经是「取不到就整张卡不渲染」，2026-10-12 真机栽过：取数失败时整块区域**静默消失**，
 * 界面看起来完全正常（就是少了一块），用户分不清「没查到数据」和「功能坏了」；
 * 而且详情页那句兜底文案**永远够不着**（详情页唯一的入口就是这张卡）。见 `Plan.hasContent` 的注释。
 * 但**取值期间要显示「正在找空教室…」**，否则就是「半天什么都不出现、然后突然冒出来」。
 *
 * ## 为什么是「一排候选」而不是「一个答案」
 *
 * 只给唯一最优时，用户看到的是**每天都同一栋楼**（推荐逻辑是确定性的）。
 * 现在的做法是把候选**排好序**给出来，底部给一个「换一个」——
 * 切换**纯本地**（候选在取数时就算完了，见 [StudySpotLogic.Plan]），点一下立刻换，
 * 不会又等一轮网络。顺序本身是有理由的（空教室更多 / 上一节就在这栋楼上），
 * 所以每次切换都是「另一个合理的选择」，而不是随机掷骰子。
 *
 * ## 点开去哪儿
 *
 * 空教室 / 正在上课 → **详情页**（`NavDest.StudySpot`）：先看有哪些地方、各自空几间、
 * 能待到几点，页底再给「打开空教室页」。
 * 有预约 → 直接去座位页（没有候选可看，进详情页只是绕路）。
 *
 * ⚠️ 「空教室」不是一条 `NavDest` 路由，而是课表页里的**子页签** —— 所以详情页底部
 *    不能用 `mainController.navigate(...)`，必须 `GotoRequest` + `ScheduleTabs` 下标：
 *    `IndexScreen` 消费 route 切底栏页，`ScheduleScreen` 消费 tab 切子页签。
 *    ⚠️ `GotoRequest.request` 的 route 取 `toPageData().value`（`"schedule"`），
 *    **不是** `PageShowOnNav.Schedule.toString()`（那是 NavHost 内部用的另一套）。
 */
@Composable
private fun StudySpotSection(mainController: MainController) {
    val vm: CampusServiceViewModel = hiltViewModel()
    val plan by vm.studySpotPlan.collectAsState()
    val index by vm.studySpotIndex.collectAsState()
    val loading by vm.studySpotLoading.collectAsState()

    // ⚠️ 卡片会「说话过时」：上面那些字（「正在上 X」「空到 20:55」「空闲到明天」）是
    //    **取数那一刻**按当时节次渲染好的**字符串**，只在**进组合**时才重算（VM 的 init、
    //    或切底栏来回重新进组合）—— 而**「切后台一会儿再回来」不会重新进组合**
    //    （`LaunchedEffect(Unit)` 的 key 是 `Unit`，不随 resume 重跑）
    //    ⇒ 卡片会继续说几分钟到几小时前的话。
    //    ⇒ 补一次「回到前台就重算」：10 分钟 TTL 内**不打网络**（见 ViewModel.refreshStudySpot）。
    //
    // ⚠️ 为什么不做「每分钟轮询」：真正的过时点是**节次边界**，而 resume 已经覆盖了
    //    用户能看到的绝大多数情况（停在同一页跨过一整节是少数）；每分钟读一次库
    //    换不来对应的收益。真要做，判据也该是「节次变了」而不是「时间过了」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshStudySpot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = plan
    if (current == null) {
        if (loading) StudySpotLoadingCard()
        return
    }

    // 文案在逻辑层就渲染好了（那里才有时间表与「现在几点」）—— 界面只按下标取一条
    val card = StudySpotLogic.studySpotCardOf(current, index) ?: return
    val options = current.options

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable { openStudySpotFromCard(mainController, card.kind) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "现在去哪儿自习",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 可点击的提示：光靠卡片本身「看起来能点」用户不一定敢点
                Text(
                    text = studySpotActionHint(card.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
            )
            // ⚠️ 第二行没有内容时**整行不渲染** —— 留一个空行会让卡片看着像坏了
            card.detail?.let { detail ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 楼层分布 + 空闲时长可能有点长，给两行；再多就截断（用户点进去看完整的）
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 只有一条候选时不给「换一个」—— 点了没反应比没有更糟
            if (options.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}/${options.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // ⚠️ 「换一个」是**本地**换下标：候选在取数时就算好了，这里不打网络。
                    //    子级的 clickable 会吃掉点击，所以点它**不会**连带触发卡片的跳转。
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable { vm.cycleStudySpot() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "换一个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 取值中的占位卡 —— 有反馈总比「什么都没发生」强。 */
@Composable
private fun StudySpotLoadingCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "正在找空教室…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 卡片右上角的动作提示 —— 必须与点下去真的发生的事一致。 */
private fun studySpotActionHint(kind: StudySpotLogic.Kind): String = when (kind) {
    // ⚠️ 有预约时**不进详情页**：那一页对「已约好」没有任何可看的候选，
    //    多一次点击只是绕路 ⇒ 直接去座位页
    StudySpotLogic.Kind.HAS_BOOKING -> "看座位"
    else -> "看详情"
}

/**
 * 详情页底部动作键的文案。
 *
 * ⚠️ 必须与 [openStudySpotTarget] 真的去的地方一致（两处不许各说各的）。
 */
internal fun studySpotActionLabel(kind: StudySpotLogic.Kind): String = when (kind) {
    StudySpotLogic.Kind.FREE_ROOM -> "打开空教室页"
    StudySpotLogic.Kind.IN_CLASS -> "打开课表"
    StudySpotLogic.Kind.HAS_BOOKING -> "打开座位页"
}

/**
 * 「看空教室 / 看课表 / 看座位」→ 跳到对应的页面。
 *
 * ⚠️ 三种建议指向三个不同页面，别图省事全跳同一个地方。
 * ⚠️ **只有这一份实现** —— 卡片（有预约时直接走）与详情页底部的动作键都调它。
 */
internal fun openStudySpotTarget(kind: StudySpotLogic.Kind) {
    when (kind) {
        // 空教室 → 课表页的「空教室」子页签（要看得具体是哪几间）
        StudySpotLogic.Kind.FREE_ROOM -> GotoRequest.request(
            PageShowOnNav.Schedule.toPageData().value,
            tab = ScheduleTabs.FREE_CLASSROOM,
        )

        // 正在上课 → 课表页（看得见这门课在哪、几点下课）
        StudySpotLogic.Kind.IN_CLASS -> GotoRequest.request(
            PageShowOnNav.Schedule.toPageData().value,
            tab = ScheduleTabs.COURSE,
        )

        // 有座位预约 → 座位页
        StudySpotLogic.Kind.HAS_BOOKING -> GotoRequest.request(
            PageShowOnNav.Seat.toPageData().value,
        )
    }
}

/**
 * 点卡片 → 去哪儿。
 *
 * ⚠️ 空教室 / 上课都进**详情页**（先看清有哪些地方、各自空几间），详情页底部再给
 *    「打开空教室页」；有预约没有候选可看 ⇒ 直接去座位页，不绕这一趟。
 */
private fun openStudySpotFromCard(mainController: MainController, kind: StudySpotLogic.Kind) {
    if (kind == StudySpotLogic.Kind.HAS_BOOKING) openStudySpotTarget(kind)
    else mainController.navigate(NavDest.StudySpot)
}

/**
 * 详情页（**顶层路由**）底部的动作键 → 目标页面。
 *
 * ⚠️⚠️ 必须先 `popBackStack()` 再发请求，**不能直接 [openStudySpotTarget]**。
 *
 * `GotoRequest.route` 的**唯一消费方是 `IndexScreen`**（它 `LaunchedEffect(pendingGoto)`
 * 里切底栏页）；而详情页是**顶层**路由 —— 详情页在前台时 `IndexScreen` 根本没在组合，
 * 请求发出去**没人消费**。
 *
 * 真机实测就是：点「打开课表」**页面纹丝不动**（也不报错，也不排队 —— 就是没反应）。
 * 先退回到有 `IndexScreen` 的那一层，它重新组合时会消费这个请求，才真的跳过去。
 * （`LaunchedEffect` 以 `pendingGoto` 为 key，所以「先退再发」与「先发再退」两种顺序都成立。）
 */
internal fun openStudySpotTargetFromDetail(
    mainController: MainController,
    kind: StudySpotLogic.Kind,
) {
    mainController.popBackStack()
    openStudySpotTarget(kind)
}

/**
 * 「校园服务」卡片区（仅本人页显示）—— 点击进**原生详情页**，不跳网页。
 *
 * 一卡通：余额摘要（WebView 登录一次后 cookie 与快照请求共享会话）。
 * 校园网：免登录 Srun 自助接口（仅校园网环境可取），显示本月已用流量与余额摘要。
 */
@Composable
private fun CampusServiceSection(mainController: MainController) {
    val vm: CampusServiceViewModel = hiltViewModel()

    val snapshot by vm.snapshot.collectAsState()
    val netResult by vm.netResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val balanceTrend by vm.balanceTrend.collectAsState()

    val balanceText = CampusCardLogic.balanceText(snapshot, loading, fetched)

    // ⚠️ 两个坑都要挡：
    //   ① 用 [CampusCardBalanceLogic.trendText]（算不出返回 null）而不是详情页那个
    //      [trendRowText]（算不出返回「记录中…」）—— 卡片上一行字很金贵，
    //      「记录中…」这种占位放在天天看的地方是噪音，留给详情页去说。
    //   ② 余额一直没动时 [trendText] 会给出「今日 无变化 · 近 7 天 无变化」，
    //      占了那一行却什么都没说 ⇒ 没实质变化就不显示，把行让回「点开详情」。
    val balanceTrendText = CampusCardBalanceLogic.trendText(balanceTrend)
        ?.takeIf { CampusCardBalanceLogic.hasChange(balanceTrend) }

    // 校园网摘要：区分「不在校内」与「在校内但未认证」—— 一律说「需连接校园网」
    // 会让在校内的用户无从下手（2026-09-25 踩过）
    val netText = when (val r = netResult) {
        null -> "获取中…"
        is CampusNetResult.Online ->
            "本月 " + CampusNetLogic.formatTraffic(r.info.bytesTotal) +
                " · 余额 ¥%.2f".format(r.info.balanceYuan)
        CampusNetResult.NotOnline -> "本机未认证"
        is CampusNetResult.Failed -> "不在校园网"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 一卡通
        Surface(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable { mainController.navigate(NavDest.CampusService) },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "一卡通",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = balanceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // C3（2026-10-10）：趋势上卡片 —— 这才是天天看的地方，
                // 详情页那行反而没人点开。算不出趋势（刚装 / 没历史）时退回原来的入口提示。
                Text(
                    text = when {
                        loading && fetched -> "刷新中…"
                        balanceTrendText != null -> balanceTrendText
                        else -> "点开详情"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 校园网（先只给入口）
        Surface(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable { mainController.navigate(NavDest.CampusService) },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "校园网",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = netText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "点开详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}
