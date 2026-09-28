package cn.bit101.android.features.seat.ui.screen

// 「抢座任务」相关的卡片与时间文案工具。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.TaskStatus

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
internal fun TaskCard(
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
internal fun TaskStatusBadge(status: TaskStatus) {
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
internal fun String.normalizeTime(): String = replace('T', ' ').trim()

/** `2026-09-19 10:55:00` → `2026-09-19`。 */
internal fun String.datePart(): String = normalizeTime().split(' ').firstOrNull().orEmpty()

/** `2026-09-19 10:55:00` → `10:55`；取不到时分时回落为空串。 */
internal fun String.timePart(): String =
    normalizeTime().split(' ').getOrNull(1)?.take(5).orEmpty()

