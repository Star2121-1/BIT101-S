// ⚠️ 下拉刷新用的是 material3 1.2 的实验 API（与座位页任务列表同一套写法）
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.bit101.android.features.schedule.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.config.common.FocusKeys
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.eclass.EclassDdlLogic
import cn.bit101.android.data.repo.base.EclassSessionState
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.component.PageFab
import cn.bit101.android.features.common.nav.NavDest
import java.time.LocalDateTime

/**
 * 「动态」页：延河课堂的课程动态（作业 / 资料 / 公告）。
 *
 * ## 与 DDL 页的分工
 *
 * | 页 | 回答的问题 |
 * |---|---|
 * | DDL | 我**还有什么要交**（只列作业，带截止时间） |
 * | 动态 | 课程里**最近发生了什么**（资料、作业、公告都列，按时间倒序） |
 *
 * 点一条动态 → 用 **App 内 WebView** 打开**这条动态所属的课程页**
 * （只有 App 内 WebView 与 App 共用的 CookieManager 互通，用户已经登录过）。
 *
 * 跳转地址由 [EclassActivityLogic.EclassActivity.targetUrl] 给出：
 * 课程列表里的 `url` 字段优先，拿不到才退回延河课堂首页。
 *
 * ⚠️ 单条活动**自身**的 URL 服务端没给过（抓包时 activities 里没有可用地址），
 * 所以落到课程页而不是那一条动态 —— 见 `docs/ddl-source-contract.md`。
 */
@Composable
internal fun EclassActivityScreen(mainController: MainController) {
    val vm: EclassActivityViewModel = hiltViewModel()

    val activities by vm.activities.collectAsState()
    val loading by vm.loading.collectAsState()
    val sessionState by vm.sessionState.collectAsState()

    val now = LocalDateTime.now()

    val listState = rememberLazyListState()

    // 组件点动态条目跳进来时带着「定位到哪一条」的请求：数据到齐后滚过去。
    // ⚠️ 只认 `activity:` 前缀的键 —— 同一个 Pager 里 DDL 页也活着，
    //    不分归属两边会互相消费（见 FocusKeys 的说明）。
    val focus by GotoRequest.focus.collectAsState()
    LaunchedEffect(focus?.key, activities) {
        val id = FocusKeys.activityTarget(focus?.key) ?: return@LaunchedEffect
        // 还没取到数据就等下一次（activities 变化会重新触发）
        if (activities.isEmpty()) return@LaunchedEffect

        val index = activities.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
        // 找不到也消费掉：活动可能已被删；留着它下次进页面会又跳一下
        GotoRequest.consumeKey()
    }

    // 下拉刷新。本项目 material3 为 1.2.0-rc01，只有旧的 `PullToRefreshContainer`
    // （`PullToRefreshBox` 要 1.3.0+），与座位页任务列表同一套写法。
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(Unit) {
            vm.refresh()
            // 刷新完成与否由页面自身的 loading 表现；指示器不等它（与座位页一致）
            pullState.endRefresh()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // 手里有数据、但这次没拉到 ⇒ 顶部加一条提示，**不动列表内容**。
            // 一次 5xx 就把用户手里的动态清空，等于「刷新一下反而没了」，体验很差。
            if (activities.isNotEmpty() && sessionState == EclassSessionState.UNAVAILABLE) {
                item { StaleDataBanner(onRetry = vm::refresh) }
            }
            if (activities.isEmpty()) {
                item {
                    if (loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else {
                        ActivityEmptyCard(
                            // 三态：可用 / 未登录 / 服务器不可用，各自给不同文案与按钮
                            state = sessionState,
                            onOpenEclass = { mainController.openWebPage(EclassDdlLogic.LOGIN_URL) },
                            onRetry = vm::refresh,
                        )
                    }
                }
            } else {
                itemsIndexed(activities) { _, item ->
                    ActivityRow(item = item, now = now) {
                        // 直接用逻辑层算好的目标地址：课程页优先，退回首页（保证非空可用）
                        mainController.openWebPage(item.targetUrl)
                    }
                }
            }

            // 列表底部留白：88dp ≈ 设置 FAB（42dp）+ 下边距，避免最后一条被按钮压住
            item { Spacer(modifier = Modifier.height(88.dp)) }
            }

            // 下拉指示器（material3 1.2 的旧 API，覆盖在列表上方）。
            // ⚠️ **只在有得看的时候才组合它**：1.2.0-rc01 的 `PullToRefreshContainer`
            // 不刷新时**不会把自己完全藏起来**（只上移约半个高度，而父 Box 默认不裁剪），
            // 于是页签下面会露出**半个灰圆**（用户 2026-09-28 反馈）。
            // `progress > 0` 就够：手指一拉就出现，回弹到 0 自动移出组合。
            if (pullState.progress > 0f || pullState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        // 设置按钮 —— 统一走 PageFab（与 DDL 页同规格：42dp、右下角）
        PageFab(
            icon = Icons.Outlined.Settings,
            description = "settings",
            onClick = { mainController.navigate(NavDest.Setting("activity")) },
            modifier = Modifier.padding(end = 10.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ActivityRow(
    item: EclassActivityLogic.EclassActivity,
    now: LocalDateTime,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp, 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 类型标签：作业 / 资料 / 公告 / 动态 —— 一眼看出这是什么
            Text(
                text = item.kind.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = item.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOf(item.courseName, EclassActivityLogic.agoText(item.time, now))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
        )
    }
}

/**
 * 「服务器暂时连不上，以下是上次的数据」。
 *
 * 与 [ActivityEmptyCard] 的区别：这条是**有数据时**才出现的横幅 ——
 * 它不占掉列表，只说明一句，并给一个「重试」。用户手里的内容不会被一次网络抖动清空。
 */
@Composable
private fun StaleDataBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp, 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "延河课堂暂时连不上，以下是上次的数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ActivityEmptyCard(
    state: EclassSessionState,
    onOpenEclass: () -> Unit,
    onRetry: () -> Unit,
) {
    val alive = state == EclassSessionState.ALIVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp, 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (state) {
                EclassSessionState.ALIVE -> "暂时没有课程动态"
                EclassSessionState.NEEDS_LOGIN -> "还没登录延河课堂"
                // ⚠️ 服务器 5xx / 网络不通时**不能**说"没登录"：那会让用户去重登，
                //    而重登进去只会看到 Keycloak 的 500 页（2026-10-30 实测过）
                EclassSessionState.UNAVAILABLE -> "延河课堂暂时打不开"
            },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when (state) {
                EclassSessionState.ALIVE -> "老师发布资料或作业后，这里会显示出来"
                EclassSessionState.NEEDS_LOGIN -> "登录后即可看到课程动态（作业、资料、公告）"
                EclassSessionState.UNAVAILABLE -> "学校那边没响应，不是你没登录。稍后再试即可"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            if (state == EclassSessionState.UNAVAILABLE) {
                // 服务器故障时主按钮给「刷新」，别把用户往登录页赶
                Button(onClick = onRetry) { Text("重试") }
                Spacer(modifier = Modifier.size(8.dp))
                TextButton(onClick = onOpenEclass) { Text("打开延河课堂") }
            } else {
                Button(onClick = onOpenEclass) {
                    Text(if (alive) "打开延河课堂" else "去登录")
                }
                if (alive) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(onClick = onRetry) { Text("刷新") }
                }
            }
        }
    }
}
