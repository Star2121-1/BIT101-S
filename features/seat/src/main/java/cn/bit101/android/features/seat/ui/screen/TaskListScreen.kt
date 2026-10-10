package cn.bit101.android.features.seat.ui.screen

// 预约 / 任务列表**页本身**（内容见 `TaskListCards` 系列文件）。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.helper.rememberNotificationPermissionState
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.api.SeatHttp
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.SeminarRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 预约 / 任务列表页。
 *
 * ## 这一页的两块内容是什么关系
 * - **我的预约**：服务端上真实存在的预约（含座位图上直接订的单次预约）。
 *   签到时限与取消都在这里 —— 未签到会记违约、累计 5 次停用 7 天，所以这块必须显眼。
 * - **预约任务**：本机后台在跑的「抢座」任务（监控 / 优先）。它**成功后会变成**上面那条
 *   我的预约 —— 两者是**因果**而非并列。因此任务区默认只显示**进行中**的，
 *   已结束的收进可展开的「已结束」分组，避免几十条死任务把真正在跑的埋掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    mainController: MainController,
    viewModel: SeatViewModel,
    modifier: Modifier = Modifier,
    /** 空态里「去预约座位」按钮：切到同一页的「预约」页签（由 `SeatScreen` 提供）。 */
    onNavigateToReserve: () -> Unit = {},
) {
    val tasks by viewModel.tasks.collectAsState()
    val reservations by viewModel.myReservations.collectAsState()
    /** 「我的违约」（座位）——图书馆服务端数据；`null` = 还没拉到（≠ 0 条）。 */
    val reneges by viewModel.reneges.collectAsState()

    /** 「我的违约」里的研讨室那一类（与座位各记各的）。 */
    val seminarReneges by viewModel.seminarReneges.collectAsState()

    /** 「我的研讨间预约」（与座位同一后端）。 */
    val seminars by viewModel.seminars.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val authorizing by viewModel.authorizing.collectAsState()
    val bit101LoggedIn by viewModel.bit101LoggedIn.collectAsState(initial = false)
    val authNotice by viewModel.authNotice.collectAsState()
    val notificationPermission = rememberNotificationPermissionState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var pendingCancel by remember { mutableStateOf<ReservationRecord?>(null) }
    var pendingSeminarCancel by remember { mutableStateOf<SeminarRecord?>(null) }
    var remainingCancels by remember { mutableStateOf(viewModel.remainingCancelsToday()) }
    var busy by remember { mutableStateOf(false) }

    // 数据新鲜度：列表是静态的，用户无法区分「确实没变」与「刷新早失败了」
    var lastUpdatedAt by remember { mutableLongStateOf(0L) }

    // 「已结束」分组默认收起
    var showFinished by remember { mutableStateOf(false) }

    // 座位预约规则弹窗
    var showRules by remember { mutableStateOf(false) }

    // 每秒重算一次相对时间（已等待 / 最近尝试 / 倒计时 / 更新时间）。
    // 用一个 tick 驱动而不是各处各自 LaunchedEffect，避免多份定时器不同步。
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }

    suspend fun refreshAll() {
        viewModel.refreshMyReservations()
        viewModel.refreshSeminars()
        viewModel.refreshReneges()
        // 给服务端一点时间，也让下拉动画有反馈；同时把「更新时间」推到刷新完成之后
        delay(600)
        remainingCancels = viewModel.remainingCancelsToday()
        lastUpdatedAt = System.currentTimeMillis()
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.refreshReneges()
            viewModel.refreshMyReservations()
            viewModel.refreshSeminars()
            remainingCancels = viewModel.remainingCancelsToday()
            lastUpdatedAt = System.currentTimeMillis()
        }
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Default.EventSeat, null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "登录后即可管理座位预约",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (bit101LoggedIn) "学校账号已登录，还需开通座位系统的访问权限"
                    else "使用学校统一身份认证登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (authNotice != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        authNotice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        if (!bit101LoggedIn) mainController.navigate(NavDest.Login)
                        // 走 VM 的统一入口：与「预约 / 座位图」页是同一套「进行中」状态，
                        // 链路可能要走静默续期 + CAS 直登（最长 25s），必须给反馈
                        else viewModel.authorize()
                    },
                    enabled = !authorizing,
                ) {
                    if (authorizing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            authorizing -> "正在恢复授权…"
                            bit101LoggedIn -> "开通座位系统权限"
                            else -> "登录"
                        }
                    )
                }
            }
        }
        return
    }

    // 排序与分组（纯逻辑，见 TaskListLogic）
    val activeTasks = remember(tasks) { TaskListLogic.active(tasks) }
    val finishedTasks = remember(tasks) { TaskListLogic.finished(tasks) }
    val hasAnyTask = tasks.isNotEmpty()

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        // ⚠️ 这里**刻意没有「空态提前 return」**。
        //
        // 原先没有预约时整页只剩一句「暂无预约」，把下面这些全都挡住了：
        // 违约计数（还能不能预约的前提）、签到时限规则、每天可取消次数、「去预约」入口。
        // 而「没有预约」恰恰是**最需要这些信息**的时候（刚入学期、或刚被暂停预约权）。
        // 现在空态降级成列表里的**一个卡片**，其余内容照常显示。
        // ⚠️ 研讨间预约也算「有东西」：只约了研讨间、没约座位的用户，
        // 不该看到「还没有座位预约」这种像是空页面的话。
        val hasSeminar = seminars?.isNotEmpty() == true
        val nothingAtAll = activeTasks.isEmpty() && finishedTasks.isEmpty() &&
            reservations.isEmpty() && !hasSeminar

        // 下拉刷新。本项目 material3 为 1.2.0-rc01，只有旧的 `PullToRefreshContainer`
        // （`PullToRefreshBox` 要 1.3.0+），因此这里用「Box + nestedScroll + Container」的旧式组合。
        val pullState = rememberPullToRefreshState()
        if (pullState.isRefreshing) {
            LaunchedEffect(Unit) {
                refreshAll()
                pullState.endRefresh()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!notificationPermission.granted && activeTasks.isNotEmpty()) {
                    item { NotificationPermissionBanner(onRequest = { notificationPermission.request() }) }
                }

                // ── 一条预约都没有时：给「怎么约」的入口与规则，而不是一堵墙 ────
                if (nothingAtAll) {
                    item {
                        NoReservationCard(
                            cancelsLeft = remainingCancels,
                            onGoReserve = onNavigateToReserve,
                            onShowRules = { showRules = true },
                        )
                    }
                }

                // ── 违约计数 ──────────────────────────────────────────────
                // 放最前面：它是「还能不能预约」的前提，比下面任何一条都先看。
                item {
                    ViolationCard(
                        records = reneges,
                        seminarRecords = seminarReneges,
                        onRefresh = { scope.launch { viewModel.refreshReneges() } },
                    )
                }

                // ── 我的预约 ──────────────────────────────────────────────
                if (reservations.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "我的预约",
                            subtitle = "每天最多可取消 ${SeatViewModel.CANCELS_PER_DAY} 次 · 今天还剩 $remainingCancels 次",
                            trailing = TaskListLogic.updatedAgoText(lastUpdatedAt, nowTick),
                            onRefresh = { scope.launch { refreshAll() } }
                        )
                    }
                    items(reservations, key = { "res-${it.id}" }) { record ->
                        ReservationCard(
                            record = record,
                            busy = busy,
                            now = nowTick,
                            onCancel = { pendingCancel = record }
                        )
                    }
                }

                // ── 我的研讨间预约 ────────────────────────────────────────
                //
                // ⚠️ 研讨间与座位是**同一个后端的两类资源**（`/api/Seat/…` ↔ `/api/Seminar/…`，
                // 取消共用 `/api/Space/cancel`），所以才放在同一页、同一套卡片里；
                // 但**发起预约的流程完全不同**（研讨间要按「整间 + 时段 + 参与成员」申请），
                // 那一半还没有接 —— 这里明确只做「看 + 取消」，并把人送到官方页面去约。
                //
                // ⚠️ **这一块必须常显**：v1.9.24 只在「已有研讨间预约」时才渲染，
                // 于是没约过的人根本不知道 App 里有研讨间这回事
                //（用户 2026-09-28 反馈「我没看到研讨室」）。空的时候要给解释和入口，
                // 而不是整块消失 —— 与「列表」页空态是同一个教训。
                item(key = "seminar-header") {
                    SectionHeader(
                        title = "我的研讨间预约",
                        // ⚠️ 只说确定的事：同属图书馆空间预约。
                        // 「两类是否共用每天的取消额度」服务端没有明说，别写进 UI 当结论
                        subtitle = "与座位同属图书馆空间预约（同一后端）",
                        onRefresh = { scope.launch { viewModel.refreshSeminars() } },
                    )
                }
                val seminarList = seminars.orEmpty()
                if (seminarList.isEmpty()) {
                    item(key = "seminar-empty") {
                        NoSeminarCard(
                            unknown = seminars == null,
                            onOpenWeb = { mainController.openWebPage(SeatHttp.H5_SEMINAR_BOOKING) },
                            onRefresh = { scope.launch { viewModel.refreshSeminars() } },
                        )
                    }
                } else {
                    items(seminarList, key = { "sem-${it.id}" }) { record ->
                        SeminarCard(
                            record = record,
                            busy = busy,
                            onCancel = { pendingSeminarCancel = record },
                        )
                    }
                }

                // ── 预约任务（进行中）──────────────────────────────────────
                if (activeTasks.isNotEmpty() || hasAnyTask) {
                    item {
                        SectionHeader(
                            title = "预约任务",
                            subtitle = if (activeTasks.isEmpty()) {
                                "抢到座位后会自动出现在「我的预约」里"
                            } else {
                                "进行中 ${activeTasks.size} 个 · 成功后会自动出现在「我的预约」"
                            },
                            trailing = if (reservations.isEmpty() && activeTasks.isNotEmpty()) {
                                TaskListLogic.updatedAgoText(lastUpdatedAt, nowTick)
                            } else null
                        )
                    }
                    items(activeTasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            now = nowTick,
                            onCancel = { viewModel.cancelTask(task.id) }
                        )
                    }
                }

                // ── 已结束（折叠）─────────────────────────────────────────
                if (finishedTasks.isNotEmpty()) {
                    item {
                        FinishedHeader(
                            count = finishedTasks.size,
                            expanded = showFinished,
                            onToggle = { showFinished = !showFinished },
                            onClear = {
                                val n = viewModel.clearFinishedTasks()
                                scope.launch { snackbarHostState.showSnackbar("已清除 $n 条已结束的任务") }
                            }
                        )
                    }
                    if (showFinished) {
                        items(finishedTasks, key = { "fin-${it.id}" }) { task ->
                            TaskCard(
                                task = task,
                                now = nowTick,
                                onCancel = null,
                                onDelete = { viewModel.removeTask(task.id) }
                            )
                        }
                    }
                }

                // ── 规则入口（放列表末尾：想看的人才点，不占预约信息的视线）──────
                // ⚠️ 空态时**不再重复放一个** —— 引导卡里已经有一个同样的入口，
                // 同一屏两个按钮开同一个弹窗只会让人以为是两件事。
                if (!nothingAtAll) {
                    item {
                        TextButton(
                            onClick = { showRules = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("查看座位预约规则", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ⚠️ **只在有得看的时候才组合它**。
            //
            // material3 1.2.0-rc01 的 `PullToRefreshContainer` 在**不刷新时不会把自己完全
            // 藏起来** —— 它只把指示器上移约半个高度，而父 Box 默认不裁剪，于是页签下面
            // 会露出**半个灰圆**（用户 2026-09-28 反馈，卷的动态页 / 座的列表页都有）。
            //
            // 判据用 `progress > 0`：手指一按下去开始拉，progress 立刻 > 0 → 指示器出现；
            // 松手后回弹到 0 → 自动移出组合，既不漏半圆也不影响下拉手感。
            if (pullState.progress > 0f || pullState.isRefreshing) {
                PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        if (showRules) {
            SeatRulesDialog(onDismiss = { showRules = false })
        }
    }

    pendingCancel?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("取消这个预约？") },
            text = {
                Text(
                    "座位 ${record.seatNo}（${record.areaName}）将被取消。\n\n" +
                        "规则：每天最多取消 ${SeatViewModel.CANCELS_PER_DAY} 次，" +
                        "今天还剩 $remainingCancels 次。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rec = record
                    pendingCancel = null
                    busy = true
                    scope.launch {
                        val err = viewModel.cancelReservationById(rec.id)
                        busy = false
                        remainingCancels = viewModel.remainingCancelsToday()
                        lastUpdatedAt = System.currentTimeMillis()
                        snackbarHostState.showSnackbar(if (err == null) "已取消座位 ${rec.seatNo}" else "取消失败: $err")
                    }
                }) { Text("确认取消") }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("再想想") } }
        )
    }

    pendingSeminarCancel?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingSeminarCancel = null },
            title = { Text("取消这个研讨间预约？") },
            text = {
                Text(
                    "${record.nameMerge}\n${record.timeText()}\n\n" +
                        "将被取消。规则：每天最多取消 ${SeatViewModel.CANCELS_PER_DAY} 次" +
                        "，本机记录今天还剩 $remainingCancels 次。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rec = record
                    pendingSeminarCancel = null
                    busy = true
                    scope.launch {
                        // ⚠️ 走的是与座位**同一个**取消接口（/api/Space/cancel），见 ViewModel 注释
                        val err = viewModel.cancelSeminar(rec.id)
                        busy = false
                        remainingCancels = viewModel.remainingCancelsToday()
                        lastUpdatedAt = System.currentTimeMillis()
                        viewModel.refreshSeminars()
                        snackbarHostState.showSnackbar(
                            if (err == null) "已取消 ${rec.nameMerge}" else "取消失败: $err"
                        )
                    }
                }) { Text("确认取消") }
            },
            dismissButton = { TextButton(onClick = { pendingSeminarCancel = null }) { Text("再想想") } }
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String,
    trailing: String? = null,
    onRefresh: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRefresh != null) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新") }
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 0.dp, top = 0.dp)
            )
        }
    }
}

/** 「已结束」折叠头：整行可点开合，右侧一键清除。 */
@Composable
internal fun FinishedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.HistoryToggleOff, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "已结束 $count",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear) { Text("清除") }
            Icon(
                Icons.Default.ExpandMore, null,
                modifier = Modifier.size(22.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
internal fun NotificationPermissionBanner(onRequest: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("通知权限未开启", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("后台监控仍在运行，但通知栏看不到进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = onRequest) { Text("开启") }
        }
    }
}

