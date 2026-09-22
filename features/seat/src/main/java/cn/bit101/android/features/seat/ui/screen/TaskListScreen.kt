package cn.bit101.android.features.seat.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.common.helper.rememberNotificationPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

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
fun TaskListScreen(mainController: MainController, viewModel: SeatViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.tasks.collectAsState()
    val reservations by viewModel.myReservations.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val bit101LoggedIn by viewModel.bit101LoggedIn.collectAsState(initial = false)
    val authNotice by viewModel.authNotice.collectAsState()
    val notificationPermission = rememberNotificationPermissionState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var pendingCancel by remember { mutableStateOf<ReservationRecord?>(null) }
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
        // 给服务端一点时间，也让下拉动画有反馈；同时把「更新时间」推到刷新完成之后
        delay(600)
        remainingCancels = viewModel.remainingCancelsToday()
        lastUpdatedAt = System.currentTimeMillis()
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.refreshMyReservations()
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
                Button(onClick = {
                    if (!bit101LoggedIn) mainController.navigate(NavDest.Login)
                    else scope.launch { viewModel.ensureSeatlibSession() }
                }) { Text(if (bit101LoggedIn) "开通座位系统权限" else "登录") }
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
        if (activeTasks.isEmpty() && finishedTasks.isEmpty() && reservations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.EventSeat, null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "暂无预约",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "选择区域和座位，开始预约吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@Scaffold
        }

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
                item {
                    TextButton(
                        onClick = { showRules = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看座位预约规则", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
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
}

@Composable
private fun SectionHeader(
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
private fun FinishedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
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

/**
 * 「我的预约」卡片。
 *
 * 签到提示按规则算：当日预约要在开始后 60 分钟内刷卡，次日预约要在次日 9:00 前刷卡；
 * 未签到会记违约（累计 5 次停用 7 天），所以这里给出**倒计时**并用醒目色显示 ——
 * 只写「请在 10:55 前刷卡」用户还得自己换算还剩多久，紧迫感差很多。
 */
@Composable
private fun ReservationCard(
    record: ReservationRecord,
    busy: Boolean,
    now: Long,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("座位 ${record.seatNo}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (record.areaName.isNotBlank()) {
                        Text(record.areaName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF2E7D32).copy(alpha = 0.15f)) {
                    Text(
                        "有效",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.size(4.dp))
                Text(
                    "${record.beginTime.datePart()} ${record.beginTime.timePart()} - ${record.endTime.timePart()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            signInRow(record, now)?.let { (text, overdue) ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (overdue) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Timelapse, null,
                            modifier = Modifier.size(15.dp),
                            tint = if (overdue) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (overdue) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                Text("取消预约", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 签到提示行的文案。返回 null 表示不需要提示（无截止时间 / 非近期预约）。
 *
 * 复用 `ReservationRecord.signInHint` 的规则判断，但把「还剩多久」换成倒计时 ——
 * 超时的情况下必须明确说「已超时」。
 */
private fun signInRow(record: ReservationRecord, now: Long): Pair<String, Boolean>? {
    val deadline = record.signInDeadline(java.time.LocalDateTime.now()) ?: return null
    val deadlineMs = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val (countdown, overdue) = TaskListLogic.signInCountdown(deadlineMs, now)
    val tail = if (overdue) "$countdown · 请尽快刷卡" else "$countdown（截止 ${deadline.toLocalTime().withSecond(0).withNano(0)}）"
    val prefix = if (record.reserveDate == java.time.LocalDate.now()) "今日预约" else "次日预约"
    return "$prefix · $tail" to overdue
}

@Composable
private fun NotificationPermissionBanner(onRequest: () -> Unit) {
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

/**
 * 预约任务卡片。
 *
 * 与「我的预约」卡的区别：
 * - 显示**时间段**（此前只有日期，与预约卡不一致）
 * - 进行中的任务显示**存活信息**：已等待多久、尝试次数、最近尝试时间 ——
 *   这是用户判断「后台到底还在不在抢」的唯一依据
 * - 终态任务不可取消，但可删除（长按 / 删除按钮）
 */
@Composable
private fun TaskCard(
    task: ReservationTask,
    now: Long,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    val active = task.status == TaskStatus.IDLE || task.status == TaskStatus.RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.areaName.ifBlank { task.areaId },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val sub = buildString {
                        if (task.campusName.isNotBlank()) append(task.campusName)
                        if (task.floorName.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(task.floorName)
                        }
                    }
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                TaskStatusBadge(task.status)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "${task.reserveDate.ifBlank { "-" }} ${TaskListLogic.timeRangeLabel(task)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TaskListLogic.seatLabel(task)?.let { seat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.size(4.dp))
                        Text(seat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(4.dp))
                    Text(TaskListLogic.modeLabel(task), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 存活信息：只在进行中显示
            if (active) {
                TaskListLogic.livenessText(task.createdAt, task.attempts, task.lastAttemptAt, now)?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timelapse, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            if (task.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Text(
                        task.message,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                active && onCancel != null -> {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showCancelDialog = true }) {
                            Text("取消任务", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                onDelete != null -> {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDelete) {
                            Text("删除记录", color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                title = { Text("取消这个任务？") },
                text = {
                    Text(
                        if (task.message.contains("预约成功")) {
                            "任务已抢到座位，取消不会撤销已成功的预约。\n如需退座，请在「我的预约」中操作。"
                        } else {
                            "取消后后台将停止为该任务轮询抢座。\n已经抢到的座位不会受影响。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showCancelDialog = false; onCancel?.invoke() }) { Text("确认取消") }
                },
                dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("再想想") } }
            )
        }
    }
}

@Composable
private fun TaskStatusBadge(status: TaskStatus) {
    val (text, key) = TaskListLogic.statusBadge(status)
    val base = when (key) {
        "idle" -> Color(0xFF9E9E9E)
        "running" -> Color(0xFF1565C0)
        "success" -> Color(0xFF2E7D32)
        "failed" -> Color(0xFFC62828)
        else -> Color(0xFF757575)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = base.copy(alpha = 0.15f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = base,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 服务端时间形如 `2026-09-19 10:55:00`（也可能是 ISO 的 `T` 分隔、或只有日期）。
 * 按分隔符取而不是按固定下标切 —— 固定切片遇到格式变化会静默切错。
 */
private fun String.normalizeTime(): String = replace('T', ' ').trim()

/** `2026-09-19 10:55:00` → `2026-09-19`。 */
private fun String.datePart(): String = normalizeTime().split(' ').firstOrNull().orEmpty()

/** `2026-09-19 10:55:00` → `10:55`；取不到时分时回落为空串。 */
private fun String.timePart(): String =
    normalizeTime().split(' ').getOrNull(1)?.take(5).orEmpty()
