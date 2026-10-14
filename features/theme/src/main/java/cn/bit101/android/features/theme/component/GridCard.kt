package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme

/**
 * 卡片（规范 §5.1：课程卡 / 空教室行 / 借阅行同一套容器）。
 *
 * 六态：
 * - 默认：整卡触控区，`--surface` 底 + 圆角 12 + 内边距 16 + 左竖条（课程色 4px）
 * - 按压：80ms scale(.98) + Primary @8% 蒙层（**无阴影变化**）
 * - 选中：1.5dp Primary 描边 + `primaryContainer` 底 + 右上 20dp 勾选圆（仅多选模式出现）
 * - 禁用：`tokens.disabledBg` 底 + 文字/图标 `tokens.textDisabled` + **竖条降饱和 50%** +
 *   **无按压反馈**（第二编码：禁用不得仅靠颜色表达）
 * - 加载：骨架（`tokens.skeleton` + 1.4s 微光），**保留卡片外框**以免跳动
 * - 空态：**不在这里做** —— 由 [GridEmptyState] 整块替换（240dp），见 §3 空态
 *
 * ⚠️ 「未登录」**不是**第七态（§3.6 登录门：页面级 fail-open，卡片层不做登录引导）。
 *
 * @param courseColorIndex 课程色号 0..11；传 -1 表示没有竖条（如借阅行、纯文字行）。
 */
@Composable
fun GridCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    courseColorIndex: Int = -1,
    selected: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val interaction = rememberGridInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.98f else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "cardPress",
    )

    val shape = RoundedCornerShape(12.dp)
    val containerColor = when {
        !enabled -> tokens.disabledBg
        selected -> cs.primaryContainer
        else -> cs.surface
    }
    val contentColor = when {
        !enabled -> tokens.textDisabled
        else -> cs.onSurface
    }
    val stripeColor = if (courseColorIndex >= 0) {
        val ink = tokens.course(courseColorIndex).ink
        if (enabled) ink else desaturate(ink, 0.5f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .then(
                if (selected && enabled) {
                    Modifier.border(1.5.dp, cs.primary, shape)
                } else {
                    Modifier
                }
            )
            .background(containerColor, shape)
            .gridStateLayer(pressed, enabled, cs.primary, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClickLabel = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        if (loading) {
            CardSkeleton(shape = shape)
            return@Box
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // 左竖条 4px（课程色；-1 时宽度 0，不占位）
            if (courseColorIndex >= 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(stripeColor),
                )
            }
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) cs.onSurfaceVariant else tokens.textDisabled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (meta != null) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) tokens.textPlaceholder else tokens.textDisabled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected && enabled) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp)
                            .background(cs.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = cs.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (trailing != null) {
                    Box(modifier = Modifier.padding(start = 8.dp)) { trailing() }
                }
            }
        }
    }
}

/** 卡片加载态骨架（保留外框，避免加载完成时跳动）。 */
@Composable
private fun CardSkeleton(shape: androidx.compose.ui.graphics.Shape) {
    val tokens = GridTheme.tokens
    val transition = rememberInfiniteTransition(label = "cardShimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400)),
        label = "cardShimmerShift",
    )
    val brush = Brush.linearGradient(
        colors = listOf(tokens.skeleton, tokens.skeleton.copy(alpha = 0.55f), tokens.skeleton),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1000f * shift, 0f),
    )
    Box(
        modifier = Modifier
            .height(52.dp)
            .fillMaxWidth()
            .background(brush, shape),
    )
}

/** 降饱和 —— 禁用态竖条用（保留色相关系但明确「不可用」）。 */
internal fun desaturate(color: Color, amount: Float): Color {
    val l = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return Color(
        red = (color.red + (l - color.red) * amount).coerceIn(0f, 1f),
        green = (color.green + (l - color.green) * amount).coerceIn(0f, 1f),
        blue = (color.blue + (l - color.blue) * amount).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

/**
 * 空态占位（规范 §5.1 空态 / §3 系列空态）：整块 240dp，图标 + 文案 + 行动按钮。
 *
 * 卡片/列表**不**自己画空态，统一替换为这个组件。
 */
@Composable
fun GridEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: (@Composable () -> Unit)? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tokens.surface3, CircleShape),
                contentAlignment = Alignment.Center,
            ) { icon() }
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textPlaceholder,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (actionText != null && onAction != null) {
            GridTextButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Preview(name = "卡片 · 三态", showBackground = true)
@Composable
private fun PreviewGridCard() {
    cn.bit101.android.features.theme.PreviewSurface {
        Column {
            GridCard(
                title = "数据结构",
                subtitle = "综合教学楼大楼A 402",
                meta = "3-4 节 · 李明轩",
                courseColorIndex = 4,
                onClick = {},
            )
            GridCard(
                title = "大学英语(三)",
                subtitle = "文萃楼B 201",
                meta = "6-7 节 · 张佳",
                courseColorIndex = 4,
                selected = true,
                onClick = {},
            )
            GridCard(
                title = "金工实习(一)",
                subtitle = "工训楼 B102",
                courseColorIndex = 4,
                enabled = false,
            )
            GridCard(title = "加载中", courseColorIndex = 0, loading = true)
        }
    }
}
