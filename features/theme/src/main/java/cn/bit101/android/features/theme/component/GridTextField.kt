package cn.bit101.android.features.theme.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 表单输入（规范 §5.4，添加自定义日程等场景）。
 *
 * - 默认：高 56、圆角 12、1dp `outlineVariant` 边；`surface` 底；输入文字 body-1 `onSurface`；
 *   **占位用 `tokens.textPlaceholder`（5.22:1），不是 disabled 档**
 * - 聚焦：200ms 边色过渡 → 1.5dp `primary` 边 + 标签上浮为 label-3 `primary`
 * - 选中：选择高亮 `primary` @24%，光标 `primary`
 * - 禁用：`tokens.disabledBg` 底 + 1dp `outlineVariant` 边 + `tokens.textDisabled` 字 + 无聚焦态
 * - 加载：右侧 20dp 进度环（输入框仍可编辑，但底部按钮转加载）
 * - 空态/校验失败：1.5dp `error` 边 + 下方 16dp 处 error label-3 文案 + 错误图标；`aria-live` 播报
 */
@Composable
fun GridTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    interactionSource: MutableInteractionSource = rememberGridInteraction(),
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val focused by interactionSource.collectIsFocusedAsState()
    val floated = focused || value.isNotEmpty()

    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> cs.error
            focused && enabled -> cs.primary
            else -> cs.outlineVariant
        },
        animationSpec = tween(200),
        label = "fieldBorder",
    )
    val containerColor = if (enabled) cs.surface else tokens.disabledBg
    val textColor = if (enabled) cs.onSurface else tokens.textDisabled
    val shape = RoundedCornerShape(12.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null && floated) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (!enabled) tokens.textDisabled else if (isError) cs.error else cs.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = cs.primary,
                backgroundColor = cs.primary.copy(alpha = 0.24f),
            ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                interactionSource = interactionSource,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                cursorBrush = SolidColor(cs.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(containerColor, shape)
                            .border(
                                width = if (isError || (focused && enabled)) 1.5.dp else 1.dp,
                                color = borderColor,
                                shape = shape,
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (!floated && placeholder != null) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = tokens.textPlaceholder,
                                    )
                                }
                                inner()
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
                },
            )
        }
        if (isError && errorText != null) {
            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.error,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Preview(name = "表单输入 · 五态", showBackground = true)
@Composable
private fun PreviewGridTextField() {
    PreviewSurface {
        Column {
            GridTextField(value = "", onValueChange = {}, label = "课程名", placeholder = "如：数据结构")
            GridTextField(
                value = "数据结构",
                onValueChange = {},
                label = "课程名",
                modifier = Modifier.padding(top = 12.dp),
            )
            GridTextField(
                value = "",
                onValueChange = {},
                placeholder = "周次",
                isError = true,
                errorText = "周次格式不对（示例：1-16 或 1,3,5）",
                modifier = Modifier.padding(top = 12.dp),
            )
            GridTextField(
                value = "导入课程（不可改）",
                onValueChange = {},
                label = "教室",
                enabled = false,
                modifier = Modifier.padding(top = 12.dp),
            )
            GridTextField(
                value = "保存中",
                onValueChange = {},
                label = "课程名",
                loading = true,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
