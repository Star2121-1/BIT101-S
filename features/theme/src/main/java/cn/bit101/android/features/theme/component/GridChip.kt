package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 筛选 Chip（规范 §5.3）。
 *
 * 高 40、圆角 8、左右内边距 12，横向滚动。六态：
 * - 默认：`tokens.surface2` 底 + `onSurfaceVariant` 字 label-2 + 可选 16dp 前置图标
 * - 按压：80ms scale(.98) + `--text` @8% 蒙层
 * - 选中：`primaryContainer` 底 + `onPrimaryContainer` 字 + 1dp Primary 描边 +
 *   **16dp 勾选图标**（第二编码：不只靠颜色）
 * - 禁用：`tokens.disabledBg` 底 + `tokens.textDisabled` 字 + 无按压反馈；
 *   **不移除**，保留条件可见性（让用户知道还有这个条件，只是当前不可用）
 * - 加载：骨架 48×40 圆角 8 微光，占位 4 个
 * - 空态：**隐藏整行**（连同 sticky 高度），列表直接顶到摘要条下
 */
@Composable
fun GridFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val interaction = rememberGridInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.98f else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "chipPress",
    )

    val shape = RoundedCornerShape(8.dp)
    val (container, content) = when {
        !enabled -> tokens.disabledBg to tokens.textDisabled
        selected -> cs.primaryContainer to cs.onPrimaryContainer
        else -> tokens.surface2 to cs.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .then(
                if (selected && enabled) {
                    Modifier.border(1.dp, cs.primary, shape)
                } else {
                    Modifier
                }
            )
            .background(container, shape)
            .gridStateLayer(pressed, enabled, cs.onSurface, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = null,
                role = Role.Checkbox,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected && enabled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选",
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            } else if (icon != null) {
                Box(Modifier.size(16.dp)) { icon() }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

/**
 * Chip 行（空态 / 加载态在这层处理，[GridFilterChip] 只管单个）。
 *
 * - `items` 为空 ⇒ **整行不渲染**（§5.3 空态：隐藏整行，不留占位高度）
 * - `loading` ⇒ 出 4 个 48×40 骨架
 */
@Composable
fun GridFilterChipRow(
    items: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabledItems: Set<String> = items.toSet(),
    loading: Boolean = false,
) {
    if (items.isEmpty() && !loading) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            repeat(4) { GridChipSkeleton() }
            return@Row
        }
        items.forEach { item ->
            GridFilterChip(
                text = item,
                selected = item in selected,
                enabled = item in enabledItems,
                onClick = { onToggle(item) },
            )
        }
    }
}

/** Chip 加载骨架：48×40 圆角 8。 */
@Composable
fun GridChipSkeleton(modifier: Modifier = Modifier) {
    val tokens = GridTheme.tokens
    GridSkeleton(
        modifier = modifier
            .width(48.dp)
            .height(40.dp),
        cornerRadius = 8.dp,
        color = tokens.skeleton,
    )
}

@Preview(name = "Chip · 四态", showBackground = true)
@Composable
private fun PreviewGridChip() {
    PreviewSurface {
        Column {
            GridFilterChipRow(
                items = listOf("全部", "理学楼A", "综合教学楼大楼A", "文萃楼B"),
                selected = setOf("全部"),
                onToggle = {},
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GridFilterChip(text = "禁用（无空教室）", selected = false, enabled = false, onClick = {})
                GridFilterChip(text = "加载骨架", selected = false, onClick = {})
            }
        }
    }
}
