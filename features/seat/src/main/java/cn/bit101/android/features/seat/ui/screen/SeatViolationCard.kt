package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.SeatViolationLogic
import cn.bit101.android.features.seat.model.RenegeRecord

// 违约计数卡与其明细弹窗。

/**
 * 违约计数卡。
 *
 * ## 数据是图书馆给的，不是我们算的
 *
 * `POST /api/Member/reneges`（body `{"type":1}`）—— 就是 h5「我的中心 → 我的违约」
 * (`#/my/contract`) 用的接口。v1.9.19 因为没有找到它，曾用「本地推算签到情况」顶上，
 * 那个做法已被**推翻并删除**（原因与教训见 [SeatViolationLogic] 顶部注释）。
 *
 * ⚠️ `records == null` 表示**还没拉到**（没登录 / 网络失败）—— 必须与「拉到 0 条」分开：
 * 把「不知道」显示成「没有违约」，会让人以为自己处于安全状态。
 *
 * 因此这里也**不再有补记 / 撤销 / 清空**：数字以图书馆为准，本地改它没有意义。
 */
@Composable
internal fun ViolationCard(
    records: List<RenegeRecord>?,
    /** 「我的违约」里的研讨室那一类（`type=2`）。⚠️ **不并入上面的计数**：
     *  h5 就是两个页签各算各的，规则原文也只说「各类违约累计 5 次」，
     *  合并出来的「总数」没有依据 —— 照实分开显示。 */
    seminarRecords: List<RenegeRecord>?,
    onRefresh: () -> Unit,
) {
    val known = records != null
    val count = records?.size ?: 0
    val seminarCount = seminarRecords?.size ?: 0
    val left = SeatViolationLogic.remaining(count)
    // 只剩 1 次就到上限时用警示色 —— 这是「下一次违约就会停用 7 天」的信号
    val urgent = known && count > 0 && left <= 1
    val warn = known && count > 0

    val container = when {
        urgent -> MaterialTheme.colorScheme.errorContainer
        warn -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when {
        urgent -> MaterialTheme.colorScheme.onErrorContainer
        warn -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var showDetail by remember { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(12.dp), color = container) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (urgent) Icons.Default.Timelapse else Icons.Default.HistoryToggleOff, null,
                    modifier = Modifier.size(20.dp),
                    tint = onContainer
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (known) "违约 $count/${SeatViolationLogic.LIMIT}" else "违约次数未知",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Spacer(Modifier.weight(1f))
                if (count > 0 || seminarCount > 0) {
                    TextButton(onClick = { showDetail = true }) { Text("明细", color = onContainer) }
                }
                TextButton(onClick = onRefresh) { Text("刷新", color = onContainer) }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (known) SeatViolationLogic.summaryText(count)
                else "暂时取不到图书馆的违约记录",
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
            )
            // 研讨室违约单独一行 —— 有就照实说，没有就不占位置、也不改变上面那句的口径
            if (known && seminarCount > 0) {
                Text(
                    "另有研讨室违约 $seminarCount 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
            Text(
                if (known) "数据来自图书馆座位系统"
                else "登录座位系统后点「刷新」，或下拉本页重试",
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.7f),
            )
        }
    }

    val seatList = records.orEmpty()
    val seminarList = seminarRecords.orEmpty()
    if (showDetail && (seatList.isNotEmpty() || seminarList.isNotEmpty())) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("违约明细（共 ${seatList.size + seminarList.size} 条）") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RenegeSection("座位违约", seatList)
                    RenegeSection("研讨室违约", seminarList)
                }
            },
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text("关闭") } },
        )
    }
}

/** 违约明细里的一节（两类分开列，与 h5 的两个页签一致）。 */
@Composable
internal fun RenegeSection(title: String, list: List<RenegeRecord>) {
    if (list.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    list.forEach { r ->
        Text(r.label(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val sub = listOf(r.time, r.statusName).filter { it.isNotBlank() }
        if (sub.isNotEmpty()) {
            Text(
                sub.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(4.dp))
}

