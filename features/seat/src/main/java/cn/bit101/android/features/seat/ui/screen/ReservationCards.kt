package cn.bit101.android.features.seat.ui.screen

// 「我的座位预约」相关的卡片与小组件。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.SeatStatusLogic
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.ReservationRecord
import java.time.Instant
import java.time.ZoneId

/**
 * 「我的预约」卡片。
 *
 * 签到提示按规则算：当日预约要在开始后 60 分钟内刷卡，次日预约要在次日 9:00 前刷卡；
 * 未签到会记违约（累计 5 次停用 7 天），所以这里给出**倒计时**并用醒目色显示 ——
 * 只写「请在 10:55 前刷卡」用户还得自己换算还剩多久，紧迫感差很多。
 */
@Composable
internal fun ReservationCard(
    record: ReservationRecord,
    busy: Boolean,
    now: Long,
    onCancel: () -> Unit,
) {
    val phase = SeatStatusLogic.phaseOf(record)
    val nowLocal = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val badgeText = when (phase) {
        SeatStatusLogic.Phase.IN_USE -> "使用中"
        SeatStatusLogic.Phase.LEAVE -> "暂离"
        SeatStatusLogic.Phase.PENDING -> "有效"
        SeatStatusLogic.Phase.STARTED -> record.statusName ?: "预约开始提醒"
        SeatStatusLogic.Phase.OTHER -> record.statusName ?: "已结束"
    }
    val badgeColor = when {
        phase == SeatStatusLogic.Phase.IN_USE -> Color(0xFF2BD06D)
        phase == SeatStatusLogic.Phase.LEAVE -> Color(0xFFFF8E5D)
        phase == SeatStatusLogic.Phase.STARTED -> Color(0xFF3B97FB)
        phase == SeatStatusLogic.Phase.PENDING -> Color(0xFF2E7D32)
        else -> Color(0xFF999999)
    }
    // 签到提示只给「还没刷到卡」的（2 = 还没到时间，4 = 已开始但还没刷）
    val signIn = if (record.needsSignIn) signInRow(record, now) else null

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
                Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.15f)) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
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
            when {
                phase == SeatStatusLogic.Phase.IN_USE -> {
                    val text = SeatStatusLogic.usedText(record, nowLocal)
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ReservationStatusRow(text, urgent = false)
                    }
                }
                phase == SeatStatusLogic.Phase.LEAVE -> {
                    Spacer(Modifier.height(8.dp))
                    ReservationStatusRow(SeatStatusLogic.leaveRemainingText(record, nowLocal), urgent = true)
                }
                signIn != null -> {
                    Spacer(Modifier.height(8.dp))
                    ReservationStatusRow(signIn.first, urgent = signIn.second)
                }
            }
            if (record.isActive) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text("取消预约", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ReservationStatusRow(text: String, urgent: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (urgent) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Timelapse, null,
                modifier = Modifier.size(15.dp),
                tint = if (urgent) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (urgent) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * 签到提示行的文案。返回 null 表示不需要提示（无截止时间 / 非近期预约）。
 *
 * 复用 `ReservationRecord.signInHint` 的规则判断，但把「还剩多久」换成倒计时 ——
 * 超时的情况下必须明确说「已超时」。
 *
 * ⚠️ **判定与倒计时必须用同一个「现在」**（传入的 [now]）：
 * 之前截止时刻用 `LocalDateTime.now()`、倒计时用传入的 [now]，
 * 两个时钟不一致 ⇒ 跨零点/单测里会出现「截止时间算的是今天、倒计时算的是昨天」这种自相矛盾。
 */
internal fun signInRow(record: ReservationRecord, now: Long): Pair<String, Boolean>? {
    val nowLocal = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val deadline = record.signInDeadline(nowLocal) ?: return null
    val deadlineMs = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val (countdown, overdue) = TaskListLogic.signInCountdown(deadlineMs, now)
    val tail = if (overdue) "$countdown · 请尽快刷卡" else "$countdown（截止 ${deadline.toLocalTime().withSecond(0).withNano(0)}）"
    val prefix = if (record.reserveDate == nowLocal.toLocalDate()) "今日预约" else "次日预约"
    return "$prefix · $tail" to overdue
}

/**
 * 「还没有座位预约」时的引导卡。
 *
 * ⚠️ 不要把这一页做成「没有数据就只剩一句暂无预约」：那样用户进来什么也学不到。
 * 这条卡片同时给出**唯一缺的那一步**（去预约）、**最容易踩的三个规则**，
 * 以及今天还剩几次取消机会 —— 都是没有预约时最该知道的东西。
 */
@Composable
internal fun NoReservationCard(
    cancelsLeft: Int,
    onGoReserve: () -> Unit,
    onShowRules: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.EventSeat, null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "还没有座位预约",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "每天 6:00 起可预约当日或次日座位，馆内开放 08:00-22:30。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGoReserve) { Text("去预约座位") }
            TextButton(onClick = onShowRules) { Text("查看完整预约规则") }
            Text(
                "今天还可取消 $cancelsLeft 次（规则：每天 ${SeatViewModel.CANCELS_PER_DAY} 次）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

