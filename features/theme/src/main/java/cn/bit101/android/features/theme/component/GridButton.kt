package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.LocalThemeIsDark
import cn.bit101.android.features.theme.PreviewSurface
import cn.bit101.android.features.theme.primaryPressedDark
import cn.bit101.android.features.theme.primaryPressedHeldDark
import cn.bit101.android.features.theme.primaryPressedHeldLight
import cn.bit101.android.features.theme.primaryPressedLight

/** 按钮三种形态（规范 §5.2）。 */
enum class GridButtonVariant {
    /** Primary 填充 + 白字 */
    Filled,

    /** 1dp Primary 边 + Primary 字 */
    Outlined,

    /** Primary 字，无底无边 */
    Text,
}

/**
 * 按钮（规范 §5.2）。
 *
 * 高 48、圆角 12、左右内边距 24。六态要点：
 * - 按压：80ms scale(.98)；填充底换 [primaryPressedLight]（保持中再深一档）
 * - 选中：**只**用于分段控件内的按钮组（surface-2 底 + text 字 + 1dp Primary 边）
 * - 禁用：`tokens.disabledBg` 底 + `tokens.textDisabled` 字；描边改 1dp `outlineVariant`；
 *   **无 scale、无 state layer**（第二编码：禁用不得仅靠颜色表达）
 * - 加载：文字替换为 20dp 环形进度，**宽度锁定不变**（文字保留布局、仅 alpha=0）
 * - 登录中：同加载态，另在下方出「正在登录…」label-3 `--text-2`；
 *   ⚠️ 规范同时要求「全屏不得再出现第二个登录入口」——那是页面级的活，组件管不到
 */
@Composable
fun GridButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GridButtonVariant = GridButtonVariant.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
    selected: Boolean = false,
    hint: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val isDark = LocalThemeIsDark.current
    val interaction = rememberGridInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.98f else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "buttonPress",
    )

    val shape = RoundedCornerShape(12.dp)
    val (container, content) = when {
        !enabled -> when (variant) {
            GridButtonVariant.Filled -> tokens.disabledBg to tokens.textDisabled
            else -> Color.Transparent to tokens.textDisabled
        }

        selected -> tokens.surface2 to cs.onSurface
        variant == GridButtonVariant.Filled -> {
            val base = if (pressed) {
                if (isDark) primaryPressedHeldDark else primaryPressedHeldLight
            } else {
                cs.primary
            }
            base to cs.onPrimary
        }

        else -> Color.Transparent to cs.primary
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .scale(scale)
                .clip(shape)
                .then(
                    when {
                        !enabled && variant == GridButtonVariant.Outlined ->
                            Modifier.border(1.dp, cs.outlineVariant, shape)

                        selected -> Modifier.border(1.dp, cs.primary, shape)

                        enabled && variant == GridButtonVariant.Outlined ->
                            Modifier.border(1.dp, cs.primary, shape)

                        else -> Modifier
                    }
                )
                .background(container, shape)
                .gridStateLayer(
                    pressed = pressed,
                    enabled = enabled && variant != GridButtonVariant.Filled,
                    color = cs.primary,
                    shape = shape,
                )
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (variant == GridButtonVariant.Text) {
                        Modifier.padding(horizontal = 12.dp)
                    } else {
                        Modifier.padding(horizontal = 24.dp)
                    }
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled && !loading,
                    onClickLabel = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                // 加载时文字保留布局（宽度锁定），只把文字隐藏、叠上环形进度
                Row(
                    modifier = Modifier.alpha(if (loading) 0f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (icon != null) {
                        Box(Modifier.size(20.dp)) { icon() }
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        textAlign = TextAlign.Center,
                    )
                }
                if (loading) {
                    GridProgressRing(
                        progress = null,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
            }
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textPlaceholder,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 文本按钮（§5.2 第三种形态）—— 无底无边，只有 Primary 字。 */
@Composable
fun GridTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = GridButton(
    text = text,
    onClick = onClick,
    modifier = modifier.height(48.dp),
    variant = GridButtonVariant.Text,
    enabled = enabled,
)

/**
 * 分段控件（§5.2 选中态：排序方式之类的一组互斥按钮）。
 *
 * 选中段 `tokens.surface2` 底 + `--text` 字 + 1dp Primary 边；未选段即普通文本按钮。
 */
@Composable
fun GridSegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            GridButton(
                text = label,
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                variant = GridButtonVariant.Outlined,
                enabled = enabled,
                selected = i == selectedIndex,
            )
        }
    }
}

@Preview(name = "按钮 · 三形态 × 四态", showBackground = true)
@Composable
private fun PreviewGridButton() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GridButton(text = "在地图上查看", onClick = {})
            GridButton(text = "手动改课", onClick = {}, variant = GridButtonVariant.Outlined)
            GridTextButton(text = "删除", onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GridButton(text = "禁用填充", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                GridButton(
                    text = "禁用描边",
                    onClick = {},
                    variant = GridButtonVariant.Outlined,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            GridButton(text = "保存", onClick = {}, loading = true)
            GridButton(text = "登录", onClick = {}, loading = true, hint = "正在登录…")
            GridSegmentedButton(
                options = listOf("空闲最长", "离我最近"),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }
}
