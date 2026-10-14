package cn.bit101.android.features.theme.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 顶部提示条（规范 §3.6 登录门 / §3.B B0 调休 / B5 周次判不出来 / F1 离线 / F2 保存）。
 *
 * 规范里散着 4 种提示条（调休 / 未登录 / 离线 / 周次缺失），这里**统一成一个组件**，
 * 差异只体现在文案、图标与主文案的强调色上 —— 避免出现第五种行式。
 *
 * 通用规格：`tokens.surface2` 底 + 圆角 8 + 16dp 图标 + 文案 body-3 `onSurfaceVariant`。
 * - 标准 40dp（调休 / 未登录 / 周次缺失）
 * - `compact = true` ⇒ 32dp（离线那条，§F1）
 *
 * ⚠️ 与登录卡的区别：提示条是**页面级**的「降级说明」，登录卡只在「发起需要会话的动作」时才展开。
 */
@Composable
fun GridTopBanner(
    text: String,
    modifier: Modifier = Modifier,
    subText: String? = null,
    icon: (@Composable () -> Unit)? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    emphasis: Boolean = false,
    compact: Boolean = false,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.surface2, shape)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(modifier = Modifier.size(16.dp)) { icon() }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (emphasis) tokens.stateAttention else cs.onSurface,
            )
            if (subText != null) {
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (actionText != null && onAction != null) {
            GridTextButton(text = actionText, onClick = onAction, modifier = Modifier.height(32.dp))
        }
    }
}

/** §3.B B0 · 补课日：「按周 X 课表上课」+ 学校原文（state-attention 强调）。 */
@Composable
fun GridMakeupBanner(weekdayText: String, schoolNote: String?, modifier: Modifier = Modifier) {
    GridTopBanner(
        text = "按周$weekdayText 课表上课",
        subText = schoolNote,
        icon = {
            Icon(
                imageVector = Icons.Default.CalendarToday,
                contentDescription = null,
                tint = GridTheme.tokens.stateAttention,
                modifier = Modifier.size(16.dp),
            )
        },
        emphasis = true,
        modifier = modifier,
    )
}

/** §3.B B0 · 放假日：「放假（无教学安排）」+ 学校原文（**不得**复用「今天没有课」）。 */
@Composable
fun GridHolidayBanner(schoolNote: String?, modifier: Modifier = Modifier) {
    GridTopBanner(
        text = "放假（无教学安排）",
        subText = schoolNote,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = GridTheme.tokens.stateAttention,
                modifier = Modifier.size(16.dp),
            )
        },
        emphasis = true,
        modifier = modifier,
    )
}

/**
 * §3.B B5 · 周次判不出来（开学第一天缺失）。
 *
 * ⚠️ 规范硬要求：**不得**只给一个点了没用的「获取课程表」按钮 —— 出口必须是
 * 「去设置开学第一天」这种**能处置**的动作。
 */
@Composable
fun GridNoFirstDayBanner(onSetupFirstDay: () -> Unit, modifier: Modifier = Modifier) {
    GridTopBanner(
        text = "没设置开学第一天，周次判不出来",
        subText = "当前显示全部课程，不按周次过滤",
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = GridTheme.tokens.stateAttention,
                modifier = Modifier.size(16.dp),
            )
        },
        actionText = "去设置开学第一天",
        onAction = onSetupFirstDay,
        modifier = modifier,
    )
}

/** §3.6 登录门 · 未登录：fail-open 降级说明 +「去登录」。 */
@Composable
fun GridNotLoggedInBanner(onLogin: () -> Unit, modifier: Modifier = Modifier) {
    GridTopBanner(
        text = "未登录 · 数据可能不是最新",
        actionText = "去登录",
        onAction = onLogin,
        modifier = modifier,
    )
}

/** §F1 离线（32dp 紧凑档）。 */
@Composable
fun GridOfflineBanner(text: String = "离线 · 显示的是上周缓存", modifier: Modifier = Modifier) {
    GridTopBanner(
        text = text,
        icon = {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        },
        compact = true,
        modifier = modifier,
    )
}

@Preview(name = "提示条 · 四种", showBackground = true)
@Composable
private fun PreviewGridTopBanner() {
    PreviewSurface {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            GridMakeupBanner(weekdayText = "二", schoolNote = "10 月 14 日按第 2 周周二课表上课")
            GridHolidayBanner(schoolNote = "国庆节放假调休")
            GridNotLoggedInBanner(onLogin = {})
            GridNoFirstDayBanner(onSetupFirstDay = {})
            GridOfflineBanner()
        }
    }
}
