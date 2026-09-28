package cn.bit101.android.features.seat.ui.screen

// 「我的研讨间预约」相关的卡片。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.model.SeminarRecord

/**
 * 没有研讨间预约时的说明卡。
 *
 * ⚠️ 这张卡存在的理由：**App 内还不能发起研讨间预约**（要填申请主题/内容/手机号、
 * 指定参与成员，与座位「点一下座位就订」完全不是一回事），但用户需要知道两件事：
 * 这里能看能取消；想约的话去哪儿约。
 *
 * ⇒ 与其给一个点了没反应的「去预约」，不如给一条**真的能用**的路（官方页面），
 * 并说清「约完回来下拉刷新就能在这里看到」。这也是把「看不到研讨间」
 * 这个反馈真正解决掉的地方。
 */
@Composable
internal fun NoSeminarCard(
    unknown: Boolean,
    onOpenWeb: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                if (unknown) "暂时取不到研讨间预约" else "还没有研讨间预约",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (unknown) {
                    "登录座位系统后点「刷新」，或下拉本页重试。"
                } else {
                    "研讨间要按「整间 + 时段 + 参与成员」提交申请，App 内还没做这一步；" +
                        "先用图书馆网页预约，约好后回到本页下拉刷新，就能在这里查看和取消。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenWeb) { Text("去图书馆网页预约") }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }
    }
}

/**
 * 一条研讨间预约。
 *
 * ⚠️ `record.cancellable`（服务端 `status == "2"`）为假时**不显示取消按钮**，
 * 改显示服务端下发的 `statusname` —— 与座位同一套口径：**能不能取消由服务端说了算**，
 * 不给一个点了会失败/报错的按钮。
 */
@Composable
internal fun SeminarCard(
    record: SeminarRecord,
    busy: Boolean,
    onCancel: (SeminarRecord) -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.nameMerge.ifBlank { "研讨间预约" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        record.timeText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!record.cancellable && record.statusName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        record.statusName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (record.cancellable) {
                TextButton(onClick = { onCancel(record) }, enabled = !busy) { Text("取消预约") }
            }
        }
    }
}

