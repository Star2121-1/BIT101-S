package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 底部 Tab 单项（规范 §5.5）。
 *
 * - 未选：图标 24 线性 + 单字 label-2，色 `onSurfaceVariant`
 * - 按压：80ms scale(.98) + 图标区 40dp 圆 `--text` @8%
 * - 选中：图标**填充态** + 文字 `primary` + 顶部 2×24dp 指示条（圆角 full）
 * - 禁用：图标与字 `tokens.textDisabled` + **无按压反馈**；点击**消费掉**并提示 Snackbar
 *   （[onDisabledClick] 由页面去弹提示，组件不自己弹）
 * - 加载：Tab **立即**切为选中（反馈优先），内容区渲染骨架；**Tab 本身不加 spinner**
 * - 空态：Tab 正常可选，内容区出空态；**Tab 不显示红点/角标**，避免误读为「有内容」
 *
 * @param icon 由调用方按 `selected` 给线性/填充两版图标（组件不猜图标名）。
 */
@Composable
fun GridBottomTabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (selected: Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val interaction = rememberGridInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.98f else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "tabPress",
    )
    val tint = when {
        !enabled -> tokens.textDisabled
        selected -> cs.primary
        else -> cs.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = true, // 禁用项也要"消费掉"点击 ⇒ 总是可点，内部再分流
                onClickLabel = null,
                role = Role.Tab,
                onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部指示条：2dp 高 × 24dp 宽，圆角 full
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected && enabled) cs.primary else cs.surface, CircleShape),
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(40.dp)
                .gridStateLayer(
                    pressed = pressed,
                    enabled = enabled,
                    color = cs.onSurface,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp)) { icon(selected) }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * 底部 Tab 行（§5.5）。只负责排布与选中；内容区骨架 / 空态由页面自己处理。
 *
 * @param items 每项：label + 图标（按 selected 切换线性/填充）+ enabled
 */
@Composable
fun GridBottomTabRow(
    items: List<GridTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            GridBottomTabItem(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                icon = item.icon,
                label = item.label,
                enabled = item.enabled,
                onDisabledClick = item.onDisabledClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 一个 Tab 的描述（图标由调用方给，组件不依赖具体图标集）。 */
data class GridTabItem(
    val label: String,
    val icon: @Composable (selected: Boolean) -> Unit,
    val enabled: Boolean = true,
    val onDisabledClick: (() -> Unit)? = null,
)

@Preview(name = "底部 Tab · 三态", showBackground = true)
@Composable
private fun PreviewGridBottomTab() {
    PreviewSurface {
        GridBottomTabRow(
            items = listOf(
                GridTabItem("卷", { }),
                GridTabItem("图", { }),
                GridTabItem("网", { }),
                GridTabItem("话", { }),
                GridTabItem("我", { }),
                GridTabItem("座", { }, enabled = false),
            ),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
