package cn.bit101.android.features.user

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.data.school.CampusNetLogic
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
 * 「校园服务」卡片区（仅本人页显示）—— 点击进**原生详情页**，不跳网页。
 *
 * 一卡通：余额摘要（WebView 登录一次后 cookie 与快照请求共享会话）。
 * 校园网：免登录 Srun 自助接口（仅校园网环境可取），显示已用流量与余额摘要。
 */
@Composable
private fun CampusServiceSection(mainController: MainController) {
    val vm: CampusCardViewModel = hiltViewModel()

    val snapshot by vm.snapshot.collectAsState()
    val netInfo by vm.netInfo.collectAsState()
    val loading by vm.loading.collectAsState()
    val fetched by vm.fetched.collectAsState()

    // 一卡通余额摘要：取解析出的第一个候选值（标签去重过）
    val balanceText = when {
        loading && !fetched -> "获取中…"
        snapshot == null -> "获取失败，点重试"
        snapshot?.loggedIn == false -> "点开登录后显示"
        else -> snapshot?.entries?.firstOrNull()?.let { "¥${it.second}" }
            ?: "未识别到余额"
    }

    // 校园网摘要：流量 + 余额；取不到时提示需校园网
    val netText = when {
        loading && !fetched -> "获取中…"
        netInfo == null -> "需连接校园网"
        else -> "已用 " + CampusNetLogic.formatTraffic(netInfo!!.bytesTotal) +
            " · 余额 ¥%.2f".format(netInfo!!.balanceYuan)
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
                Text(
                    text = if (loading && fetched) "刷新中…" else "点开流水",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
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
