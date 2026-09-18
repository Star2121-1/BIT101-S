package cn.bit101.android.features.seat.ui.screen

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
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.ui.component.rememberNotificationPermissionState
import kotlinx.coroutines.launch

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

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.refreshMyReservations()
            remainingCancels = viewModel.remainingCancelsToday()
        }
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                if (authNotice != null) {
                    Text(authNotice!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    if (bit101LoggedIn) "学校账号已登录，但座位系统尚未授权" else "尚未登录学校账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    if (!bit101LoggedIn) mainController.navigate(NavDest.Login)
                    else scope.launch { viewModel.ensureSeatlibSession() }
                }) { Text(if (bit101LoggedIn) "授权座位系统" else "登录") }
            }
        }
        return
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(snackbarHostState)
        },
        modifier = modifier
    ) { padding ->
        if (tasks.isEmpty() && reservations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("暂无预约", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("选择区域和座位，开始预约吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!notificationPermission.granted && tasks.any { it.status == TaskStatus.RUNNING || it.status == TaskStatus.IDLE }) {
                item { NotificationPermissionBanner(onRequest = { notificationPermission.request() }) }
            }

            // 「我的预约」：真实存在的预约（含单次预约）。签到时限与取消都在这里 ——
            // 规则里未签到会记违约、累计 5 次停用 7 天，这一块必须显眼
            if (reservations.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "我的预约",
                        subtitle = "每天最多可取消 ${SeatViewModel.CANCELS_PER_DAY} 次 · 今天还剩 $remainingCancels 次",
                        onRefresh = { viewModel.refreshMyReservations() }
                    )
                }
                items(reservations, key = { "res-${it.id}" }) { record ->
                    ReservationCard(
                        record = record,
                        busy = busy,
                        onCancel = { pendingCancel = record }
                    )
                }
            }

            if (tasks.isNotEmpty()) {
                item { SectionHeader(title = "预约任务", subtitle = "监控/优先由前台服务后台执行") }
                items(tasks, key = { it.id }) { task ->
                    EnhancedTaskCard(task = task, onCancel = { viewModel.cancelTask(task.id) })
                }
            }
        }
    }

    pendingCancel?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("取消预约") },
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
                        snackbarHostState.showSnackbar(if (err == null) "已取消座位 ${rec.seatNo}" else "取消失败: $err")
                    }
                }) { Text("确认取消") }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("不") } }
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, onRefresh: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新") }
        }
    }
}

/**
 * 「我的预约」卡片。
 *
 * 签到提示按规则算：当日预约要在开始后 60 分钟内刷卡，次日预约要在次日 9:00 前刷卡；
 * 未签到会记违约（累计 5 次停用 7 天），所以这里用醒目色显示。
 */
@Composable
private fun ReservationCard(record: ReservationRecord, busy: Boolean, onCancel: () -> Unit) {
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
                    "${record.beginTime.take(16)} - ${record.endTime.take(11).takeLast(5)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            record.signInHint()?.let { hint ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        hint,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                Text("取消预约", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun NotificationPermissionBanner(onRequest: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("通知权限未开启", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("后台监控仍在运行，但通知栏看不到进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = onRequest) { Text("开启") }
        }
    }
}

@Composable
private fun EnhancedTaskCard(task: ReservationTask, onCancel: () -> Unit) {
    var showCancelDialog by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.areaName.ifBlank { task.areaId }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (task.campusName.isNotBlank() || task.floorName.isNotBlank()) {
                        Text(buildString { if (task.campusName.isNotBlank()) append(task.campusName); if (task.floorName.isNotBlank()) { if (isNotEmpty()) append(" · "); append(task.floorName) } },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                TaskStatusBadge(status = task.status)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(task.reserveDate.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val seatLabel = task.preferredSeats.takeIf { it.isNotEmpty() }?.joinToString(",")
                    ?: task.seatNo.takeIf { it.isNotBlank() }
                if (seatLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(seatLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(when (task.mode) {
                        TaskMode.SINGLE -> "单次"
                        TaskMode.MONITOR -> "监控"
                        TaskMode.PREFER -> if (task.preferredSeats.isEmpty()) "优先(不限)" else "优先(指定)"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Text(task.message, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.IDLE) {
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = { showCancelDialog = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("取消任务", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (showCancelDialog) {
            AlertDialog(onDismissRequest = { showCancelDialog = false }, title = { Text("确认取消") }, text = { Text("确定要取消此预约任务吗？") },
                confirmButton = { TextButton(onClick = { showCancelDialog = false; onCancel() }) { Text("确认取消") } },
                dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("不") } })
        }
    }
}

@Composable
private fun TaskStatusBadge(status: TaskStatus) {
    val (text, containerColor, contentColor) = when (status) {
        TaskStatus.IDLE -> Triple("等待中", Color(0xFF9E9E9E).copy(alpha = 0.15f), Color(0xFF9E9E9E))
        TaskStatus.RUNNING -> Triple("运行中", Color(0xFF1565C0).copy(alpha = 0.15f), Color(0xFF1565C0))
        TaskStatus.SUCCESS -> Triple("成功", Color(0xFF2E7D32).copy(alpha = 0.15f), Color(0xFF2E7D32))
        TaskStatus.FAILED -> Triple("失败", Color(0xFFC62828).copy(alpha = 0.15f), Color(0xFFC62828))
        TaskStatus.CANCELLED -> Triple("已取消", Color(0xFF757575).copy(alpha = 0.15f), Color(0xFF757575))
    }
    Surface(shape = RoundedCornerShape(8.dp), color = containerColor) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = contentColor, fontWeight = FontWeight.SemiBold)
    }
}
