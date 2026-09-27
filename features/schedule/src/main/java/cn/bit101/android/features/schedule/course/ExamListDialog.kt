package cn.bit101.android.features.schedule.course

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.features.common.helper.SimpleState
import java.time.LocalDateTime

/**
 * 考试安排列表。
 *
 * ## 为什么需要它
 *
 * 考试数据随课表一起同步到本地（`exam_schedule` 表），但此前**只能**在课表格子里
 * 当一个小色块出现 —— 想知道「这学期几门考试、下一场什么时候」得逐周翻。
 *
 * ## 顺序与配色
 *
 * 用 [ExamListLogic.sorted]：**正在进行的排最前**（最要紧），未开始按时间升序，
 * 考过的垫底。「进行中」用 `errorContainer`（红）是有意的：这一眼要能让人立刻
 * 反应过来「我正在错过考试」。
 *
 * ⚠️ 不做「已结束」的折叠：一学期也就几场，全列出来反而让人安心（知道自己考完了多少）。
 *
 * ## 为什么有「从学校同步」
 *
 * 课表页的「获取课程表」按钮**只在课表为空时出现**，所以它顺带拉的那次考试安排
 * 对老用户等于没有；设置 → 课表 → 「考试安排数据」倒是能重新拉，但藏在三级菜单里。
 * 这里给一个**手边**的入口 —— 打开列表就能刷新，不用退出去翻设置。
 */
@Composable
internal fun ExamListDialog(
    exams: List<ExamScheduleEntity>,
    now: LocalDateTime,
    refreshState: SimpleState?,
    onRefresh: () -> Unit,
    onExamClick: (ExamScheduleEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(exams, now) { ExamListLogic.sorted(exams, now) }
    val refreshing = refreshState is SimpleState.Loading

    AlertDialog(
        modifier = Modifier.fillMaxSize(0.9f),
        tonalElevation = 1.dp,
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (sorted.isEmpty()) "考试安排" else "考试安排（${sorted.size} 场）",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "从学校同步考试安排",
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                // ⚠️ 同步失败要说清「下一步做什么」：这条路走的是校内会话，
                // 最常见的原因是校内登录过期，而不是网络差 —— 只说「失败」用户会一直重试
                if (refreshState is SimpleState.Fail) {
                    Text(
                        text = "同步失败：校内登录可能已过期，到「网」页登录一次再试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                if (sorted.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "还没有考试安排",
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "可能本学期还没排考，也可能只是本地没同步过。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = onRefresh, enabled = !refreshing) {
                                Text(if (refreshing) "同步中…" else "从学校同步")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(sorted) { exam ->
                            ExamRow(exam = exam, now = now, onClick = { onExamClick(exam) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 列表里的一行。点进 [ExamScheduleDetailDialog] 看完整信息 / 加进系统日历。 */
@Composable
private fun ExamRow(
    exam: ExamScheduleEntity,
    now: LocalDateTime,
    onClick: () -> Unit,
) {
    val status = ExamListLogic.status(exam, now)

    val container = when (status) {
        ExamListLogic.Status.ONGOING -> MaterialTheme.colorScheme.errorContainer
        ExamListLogic.Status.UPCOMING -> MaterialTheme.colorScheme.secondaryContainer
        ExamListLogic.Status.FINISHED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (status) {
        ExamListLogic.Status.ONGOING -> MaterialTheme.colorScheme.onErrorContainer
        ExamListLogic.Status.UPCOMING -> MaterialTheme.colorScheme.onSecondaryContainer
        ExamListLogic.Status.FINISHED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 考过的压低视觉权重（颜色已暗一档，字重再降一级）
    val weight = if (status == ExamListLogic.Status.FINISHED) FontWeight.Normal else FontWeight.SemiBold

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exam.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ExamListLogic.remainingText(exam, now),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = weight,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // whenText 已含开始时刻（「明天 08:00」），这里只补结束时刻
                text = "${ExamListLogic.whenText(exam, now)} - ${hm(exam.endTime)}",
                style = MaterialTheme.typography.bodySmall,
            )
            val where = listOf(
                exam.classroom.trim(),
                exam.seatId.trim().let { if (it.isBlank()) "" else "座位 $it" },
            ).filter { it.isNotBlank() }
            if (where.isNotEmpty()) {
                Text(
                    text = where.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
