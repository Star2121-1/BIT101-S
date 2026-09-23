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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.config.common.FocusKeys
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.eclass.EclassDdlLogic
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
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
    val sessionAlive by vm.sessionAlive.collectAsState()

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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                            // 未登录 / 会话失效 → 给登录入口；否则只是「暂时没有」
                            loggedIn = sessionAlive,
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

        // 设置按钮 —— 与 DDL 页 FAB 同规格（42dp、右下角）
        FloatingActionButton(
            modifier = Modifier
                .padding(end = 10.dp, bottom = 20.dp)
                .size(42.dp),
            onClick = { mainController.navigate(NavDest.Setting("activity")) },
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.8f),
            contentColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(0.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "settings",
            )
        }
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

@Composable
private fun ActivityEmptyCard(
    loggedIn: Boolean,
    onOpenEclass: () -> Unit,
    onRetry: () -> Unit,
) {
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
            text = if (loggedIn) "暂时没有课程动态" else "还没登录延河课堂",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (loggedIn) {
                "老师发布资料或作业后，这里会显示出来"
            } else {
                "登录后即可看到课程动态（作业、资料、公告）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            Button(onClick = onOpenEclass) {
                Text(if (loggedIn) "打开延河课堂" else "去登录")
            }
            if (loggedIn) {
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onRetry) { Text("刷新") }
            }
        }
    }
}
