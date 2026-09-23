package cn.bit101.android.features.schedule.ddl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.config.common.FocusKeys
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.eclass.EclassDdlLogic
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * @author flwfdd
 * @date 13/05/2023 15:57
 * @description DDL主页面
 * _(:з」∠)_
 */


@Composable
internal fun DDLSchedule(
    mainController: MainController,
    active: Boolean,
    vm: DDLScheduleViewModel = hiltViewModel()
) {
    // 编辑日程弹窗
    val showEditDialog = remember { mutableStateOf(false) }
    var editData: DDLScheduleEntity? by remember { mutableStateOf(null) }
    if (showEditDialog.value) {
        DDLScheduleEditDialog(mainController, vm, item = editData, showDialog = showEditDialog)
    }

    // 日程详情弹窗
    val showDetailDialog = remember { mutableStateOf(false) }
    var detailData: DDLScheduleEntity? by remember { mutableStateOf(null) }
    if (showDetailDialog.value && detailData != null) {
        DDLScheduleDetailDialog(
            mainController = mainController,
            vm = vm,
            event = detailData!!,
            showDialog = showDetailDialog,
            showEditDialog = {
                editData = it
                showEditDialog.value = true
            })
    }

    // 学士帽弹窗：去哪个平台的主页（两个平台都还能用，不替用户决定）
    val showPlatformDialog = remember { mutableStateOf(false) }
    if (showPlatformDialog.value) {
        PlatformPickDialog(
            onDismiss = { showPlatformDialog.value = false },
            onEclass = {
                showPlatformDialog.value = false
                mainController.openWebPage(EclassDdlLogic.LOGIN_URL)
            },
            onLexue = {
                showPlatformDialog.value = false
                // 乐学地址要按「校内 / 校外」选表（读设置是挂起调用）→ 起个协程再开
                MainScope().launch { mainController.openWebPage(vm.lexueHomeUrl()) }
            },
        )
    }

    // 判断是否已经有订阅链接
    val url = vm.lexueCalendarUrlFlow.collectAsState(initial = null)
    if (url.value.isNullOrBlank()) {
        // 还没有订阅链接
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var loading by remember { mutableStateOf(false) }
            Button(enabled = !loading, onClick = {
                MainScope().launch {
                    loading = true
                    vm.updateLexueCalendarUrl()
                    vm.updateLexueCalendar()
                    loading = false
                }
            }) {
                if (loading) CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                else Text("获取乐学日程")
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            // 日程列表
            val events = vm.events.collectAsState()
            val listState = rememberLazyListState()

            // 组件点 DDL 条目跳进来时带着「定位到哪一条」的请求：数据到齐后滚过去。
            // ⚠️ 只认自己那一种键（`ddl:` 前缀）—— 同一个 Pager 里动态页也活着，
            //    不分归属的话两边会互相把请求消费掉（见 FocusKeys 的说明）。
            val focus by GotoRequest.focus.collectAsState()
            LaunchedEffect(focus?.key, events.value) {
                val uid = FocusKeys.ddlTarget(focus?.key) ?: return@LaunchedEffect
                // 数据还没到就等下一次（events 变化会重新触发这个副作用）
                if (events.value.isEmpty()) return@LaunchedEffect

                val index = DdlListOrder.flatIndexOf(events.value, uid)
                if (index >= 0) listState.animateScrollToItem(index)
                // 找不到也消费掉 —— 条目可能已被删除，留着它下次进页面会又跳一下
                GotoRequest.consumeKey()
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (events.value.isEmpty()) {
                    item {
                        Text(
                            text = "怎么会有人没事儿了啊ヽ(`Д´)ﾉ\n快去卷😭",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp, 5.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(10.dp, 5.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    // 分区顺序由 DdlListOrder 定义 —— 与「定位到某一条」的下标换算同源，
                    // 两处各写一遍 filter/sortedBy 早晚会改歪一处。
                    val pending = DdlListOrder.pending(events.value)
                    val done = DdlListOrder.done(events.value)

                    if (pending.isNotEmpty()) {
                        item { DdlSectionTitle("未完成 · ${pending.size}") }
                        itemsIndexed(pending) { _, item ->
                            DDLScheduleItem(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp, 5.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        detailData = item
                                        showDetailDialog.value = true
                                    }, item, vm
                            )
                        }
                    }
                    if (done.isNotEmpty()) {
                        item { DdlSectionTitle("已完成 · ${done.size}") }
                        itemsIndexed(done) { _, item ->
                            DDLScheduleItem(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp, 5.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        detailData = item
                                        showDetailDialog.value = true
                                    }, item, vm
                            )
                        }
                    }
                }

                // 防止悬浮按钮遮挡
                item {
                    Spacer(modifier = Modifier.height(124.dp))
                }
            }

            // 悬浮按钮组
            val fabSize = 42.dp
            Column(
                modifier = Modifier
                    .padding(10.dp, 20.dp)
            ) {
                // 添加按钮
                FloatingActionButton(
                    modifier = Modifier
                        .size(fabSize),
                    onClick = {
                        editData = null
                        showEditDialog.value = true
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "next week",
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 「学士帽」= 去作业平台的主页。**两个平台都还能用**，所以不默认只去一个，
                // 而是弹窗问一句（用户 2026-09-23 要求）。
                // ⚠️ 必须用 **App 内 WebView** 打开：只有它和我们共用的 CookieManager 互通，
                //    换成系统浏览器登录的话，App 这边拿不到会话（见 WebViewCookieSync）
                FloatingActionButton(
                    modifier = Modifier
                        .size(fabSize),
                    onClick = { showPlatformDialog.value = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.School,
                        contentDescription = "作业平台主页",
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 设置按钮
                FloatingActionButton(
                    modifier = Modifier
                        .size(fabSize),
                    onClick = { mainController.navigate(NavDest.Setting("ddl")) },
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

    }
}

/**
 * DDL 列表的分区标题（「未完成 · 3」「已完成 · 2」）。
 *
 * 与桌面上组件 DDL 页的分区保持一致 —— 两处看到的结构一样，用户不用重新理解。
 */
@Composable
private fun DdlSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 学士帽按钮的弹窗：去**乐学**主页还是**延河课堂**主页。
 *
 * 为什么不是直接跳一个：两个平台**都还能用** —— 延河课堂是 2026 年起的作业与资料来源，
 * 乐学（Moodle）是旧平台、历史数据仍在校内可访问。用户 2026-09-23 明确要求
 * 「点之前问一句」，所以这里不替用户猜。
 *
 * ⚠️ 两个选项都是 **App 内 WebView**（`MainController.openWebPage`）：
 * 只有它和 App 共用 CookieManager，延河课堂的会话才拿得到（见 `WebViewCookieSync`）。
 * 乐学那边没有会话检查接口，所以文案里如实提示「可能需要重新登录」。
 */
@Composable
private fun PlatformPickDialog(
    onDismiss: () -> Unit,
    onEclass: () -> Unit,
    onLexue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开哪个平台的主页？") },
        text = {
            Column {
                Text(
                    text = "两个平台都还能用，选一个打开它的主页：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onEclass, modifier = Modifier.fillMaxWidth()) {
                    Text("延河课堂")
                }
                Text(
                    text = "2026 年起的作业与资料来源",
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onLexue, modifier = Modifier.fillMaxWidth()) {
                    Text("乐学")
                }
                Text(
                    text = "旧平台（Moodle）· 可能需要重新登录",
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
