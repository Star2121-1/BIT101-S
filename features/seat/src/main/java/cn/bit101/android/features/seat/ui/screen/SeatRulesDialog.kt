package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.SeatRules

/**
 * 座位预约规则弹窗。
 *
 * 内容来自 [SeatRules]（纯函数），依据是座位系统《预约规则》页
 * （`/api/index/booking_rules`）—— 考证见 `docs/seatlib-contract.md` 第 10 节。
 *
 * ⚠️ 故意做成**要点列表**而不是把原文整段贴上来：原文很长且包含大量
 * 「网页端如何操作」的步骤，对我们这个 App 的用户没有价值；
 * 与 App 行为相关的（取消次数、暂离保留时长）才值得占据首屏。
 */
@Composable
fun SeatRulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("座位预约规则") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(SeatRules.all()) { rule ->
                    Column {
                        Text(
                            rule.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            rule.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text(
                        SeatRules.DISCLAIMER,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
