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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus

@Composable
fun TaskListScreen(viewModel: SeatViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.tasks.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)

    if (!isLoggedIn && tasks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("未登录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(4.dp))
                Text("预约功能需要登录后才能使用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            }
        }
    } else if (tasks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("暂无预约", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text("选择区域和座位，开始预约吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tasks, key = { it.id }) { task -> EnhancedTaskCard(task = task, onCancel = { viewModel.cancelTask(task.id) }) }
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
                if (task.seatNo.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(task.seatNo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(when (task.mode) {
                        cn.bit101.android.features.seat.model.TaskMode.SINGLE -> "单次"
                        cn.bit101.android.features.seat.model.TaskMode.MONITOR -> "监控"
                        cn.bit101.android.features.seat.model.TaskMode.PREFER -> "优先"
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
                    Text("取消预约", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
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
