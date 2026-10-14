package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.LocalThemeIsDark
import cn.bit101.android.features.theme.PreviewSurface

/**
 * Snackbar（规范 §5.7）。
 *
 * 颜色走 P1 映射好的 `inverse*` 三件套（= 规范的 Snackbar 底 / 字 / Action）：
 * - 亮色：底 `inverseSurface`(#2E2822) + 字 `inverseOnSurface`(白) + Action `inversePrimary`(#EFB392 ≈6.7:1)
 * - 暗色：**暗色模式的 Snackbar 是浅色卡** —— 底 `inverseSurface`(#E8DFD4) + 字 `inverseOnSurface`(#1E1A16)
 *   + Action `inversePrimary`(#8A4E26)。⚠️ 底色必须 **100% 不透明**（半透明会让 Action 掉到 AA 以下）
 *
 * 六态：
 * - 按压：动作文本 **80ms 无 scale**，仅透明度 0.8
 * - 禁用：动作文本转 `tokens.textDisabled`（亮 #8A7F72 / 暗 #857B72），点击无效
 * - 加载：「保存中…」+ 20dp 环形进度（跟随主题前景色），**不可手动关闭**
 * - 空态：**完全不渲染**，不保留占位高度
 *
 * ⚠️ 时序（入 180ms / 停留 4s / 出 140ms）与「距底 56(Tab)+16+inset」由 SnackbarHost 负责，
 * 本组件只画内容 —— 与 M3 的 Snackbar/Host 分工一致。
 */
@Composable
fun GridSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val isDark = LocalThemeIsDark.current
    val interaction = rememberGridInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val actionAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.8f else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "snackActionAlpha",
    )

    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.inverseSurface, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = cs.inverseOnSurface,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            GridProgressRing(
                progress = null,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(20.dp),
                strokeWidth = 1.5.dp,
                progressColor = cs.inverseOnSurface,
                trackColor = cs.inverseOnSurface.copy(alpha = 0.3f),
            )
        } else if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (actionEnabled) {
                    cs.inversePrimary
                } else {
                    if (isDark) tokens.textDisabled else tokens.textDisabled
                },
                modifier = Modifier
                    .padding(start = 12.dp)
                    .alpha(actionAlpha)
                    .clickable(enabled = actionEnabled) { onAction() },
            )
        }
    }
}

@Preview(name = "Snackbar · 亮暗 + 加载/禁用", showBackground = true)
@Composable
private fun PreviewGridSnackbar() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GridSnackbar(message = "已定位 理学楼A")
            GridSnackbar(
                message = "已复制教室名",
                actionLabel = "撤销",
                onAction = {},
            )
            GridSnackbar(
                message = "保存中…",
                actionLabel = "撤销",
                onAction = {},
                actionEnabled = false,
            )
            GridSnackbar(message = "同步中…", loading = true)
        }
    }
}

@Preview(name = "Snackbar · 暗色（浅色卡）", showBackground = true)
@Composable
private fun PreviewGridSnackbarDark() {
    PreviewSurface(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GridSnackbar(message = "已定位 理学楼A")
            GridSnackbar(message = "已复制教室名", actionLabel = "撤销", onAction = {})
        }
    }
}
