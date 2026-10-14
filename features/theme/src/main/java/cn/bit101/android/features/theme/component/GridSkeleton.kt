package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 骨架屏（规范 §5.8）。
 *
 * - 进入 Tab / 下拉刷新时**立即**渲染（≤100ms），底 `tokens.skeleton`
 * - 圆角跟随目标组件（卡片 12 / 行 12 / Chip 8）
 * - 1.4s 左右微光循环
 * - **不响应任何交互**（无可按压反馈，防止误触）
 * - 加载完成但无数据 ⇒ **直接**换成正式空态（[GridEmptyState]），不做「骨架→空」闪烁
 *
 * @param cornerRadius 圆角跟随被占位的组件（卡片 12 / 行 12 / Chip 8）。
 */
@Composable
fun GridSkeleton(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    color: Color = GridTheme.tokens.skeleton,
    shimmer: Boolean = true,
) {
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "skeletonShimmer")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonShift",
    )
    val shape: Shape = RoundedCornerShape(cornerRadius)
    val brush = if (shimmer) {
        Brush.linearGradient(
            colors = listOf(
                color,
                color.copy(alpha = (color.alpha * 0.55f).coerceIn(0f, 1f)),
                color,
            ),
            start = Offset(0f, 0f),
            end = Offset(400f * shift, 0f),
        )
    } else {
        Brush.linearGradient(listOf(color, color))
    }
    Box(modifier = modifier.background(brush, shape))
}

/**
 * §5.8 的「超过 8s 未结束」分支：骨架换成内联错误 + 重试。
 *
 * 组件只负责渲染；**计时**由调用方（ViewModel）做，到点传 `timedOut = true`。
 */
@Composable
fun GridLoadingOrError(
    loading: Boolean,
    timedOut: Boolean,
    loadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String = "加载失败",
    retryText: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    if (!timedOut) {
        Box(modifier = modifier) { loadingContent() }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) {
            GridTextButton(text = retryText, onClick = onRetry)
        }
    }
}

/** 三行骨架（抽屉元信息区加载态，§5.6）。 */
@Composable
fun GridSkeletonLines(
    count: Int,
    modifier: Modifier = Modifier,
    lineHeight: Dp = 44.dp,
) {
    Column(modifier = modifier) {
        repeat(count) {
            GridSkeleton(
                modifier = Modifier
                    .padding(top = if (it == 0) 0.dp else 8.dp)
                    .fillMaxWidth()
                    .height(lineHeight * 0.5f),
                cornerRadius = 8.dp,
            )
        }
    }
}

@Preview(name = "骨架屏", showBackground = true)
@Composable
private fun PreviewGridSkeleton() {
    PreviewSurface {
        Column {
            GridSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                cornerRadius = 12.dp,
            )
            GridSkeletonLines(count = 3, modifier = Modifier.padding(top = 12.dp))
            GridLoadingOrError(
                loading = false,
                timedOut = true,
                loadingContent = {},
                modifier = Modifier.padding(top = 12.dp),
            ) {}
        }
    }
}
