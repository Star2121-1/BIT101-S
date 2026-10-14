package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * 《一格 GRID》组件层共享件（规范 §5）。
 *
 * 只放**跨组件共用**的东西（按压反馈 / 状态层 / 色盲模拟）；
 * 每个组件自己的东西留在各自文件里。
 *
 * ⚠️ 移动端无指针 hover —— 规范 §5 表里的「悬停」列在这里统一实现为
 * **press 前 80ms 的 state layer**（[gridPressScale] + [gridStateLayer]），
 * 外接触控笔/键盘的 focus 分支走 [MutableInteractionSource] 的另一路，暂不单独建态。
 */

/** 规范 §5：按压 80ms、缩放 .98。禁用态**不得**有这个反馈（第二编码：无 scale = 不可点）。 */
internal const val PRESS_DURATION_MS = 80

/** 按压反馈：按下时缩到 `pressedScale`，松开回弹。禁用态恒为 1f（第二编码）。 */
@Composable
internal fun gridPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float = 0.98f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (enabled && pressed) pressedScale else 1f
    return animateFloatAsState(
        targetValue = target,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "gridPressScale",
    ).value
}

/**
 * 按压态的 state layer：`color @8%` 蒙层。
 * 禁用态不叠加（规范：禁用不得仅靠颜色表达，须同时**无**按压反馈）。
 */
internal fun Modifier.gridStateLayer(
    pressed: Boolean,
    enabled: Boolean,
    color: Color,
    shape: Shape,
    alpha: Float = 0.08f,
): Modifier = if (pressed && enabled) {
    this.background(color.copy(alpha = alpha), shape)
} else {
    this
}

/** 记住一个 [MutableInteractionSource]，供 clickable 与按压反馈共用同一实例。 */
@Composable
internal fun rememberGridInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/** 描边宽度转 [Stroke]（画环形进度 / 圆环轨道时用）。 */
internal fun strokeOf(width: Dp): Stroke = Stroke(width.value)

/** 画一段圆弧（进度环共用）。 */
internal fun DrawScope.drawArcSweep(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    useCenter: Boolean,
    stroke: Stroke,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = useCenter,
        style = stroke,
    )
}

// ── 色盲模拟（仅 @Preview 用，运行时不得调用） ──

/**
 * 色盲类型。用于 @Preview 矩阵里检查「去掉颜色后状态还认得出吗」。
 *
 * 矩阵取 Brettel/Viénot 体系的常用近似值（LMS 空间投影）。
 */
internal enum class ColorBlindKind(val label: String) {
    /** 绿色弱/盲，最常见（约 6% 男性） */
    Deuteranopia("红绿色盲（乙型）"),

    /** 红色弱/盲 */
    Protanopia("红绿色盲（甲型）"),

    /** 蓝黄色盲，罕见 */
    Tritanopia("蓝黄色盲"),

    /** 全色盲：只看明暗 */
    Achromatopsia("全色盲（灰度）"),
}

/**
 * 把一个颜色按 [kind] 模拟后的结果 —— 纯 Kotlin 运算，不碰 canvas API。
 *
 * 用法只在 @Preview：把 [androidx.compose.material3.ColorScheme] 与
 * [cn.bit101.android.features.theme.GridTokens] 的主要色位过一遍这个变换再重新提供，
 * 就能在预览里看到色盲用户眼里的组件（从而检查第二编码是否够）。
 */
internal fun simulateColorBlind(color: Color, kind: ColorBlindKind): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val (nr, ng, nb) = when (kind) {
        ColorBlindKind.Deuteranopia -> Triple(
            0.625f * r + 0.375f * g + 0.000f * b,
            0.700f * r + 0.300f * g + 0.000f * b,
            0.000f * r + 0.300f * g + 0.700f * b,
        )

        ColorBlindKind.Protanopia -> Triple(
            0.567f * r + 0.433f * g + 0.000f * b,
            0.558f * r + 0.442f * g + 0.000f * b,
            0.000f * r + 0.242f * g + 0.758f * b,
        )

        ColorBlindKind.Tritanopia -> Triple(
            0.950f * r + 0.050f * g + 0.000f * b,
            0.000f * r + 0.433f * g + 0.567f * b,
            0.000f * r + 0.475f * g + 0.525f * b,
        )

        ColorBlindKind.Achromatopsia -> {
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            Triple(l, l, l)
        }
    }
    return Color(
        red = nr.coerceIn(0f, 1f),
        green = ng.coerceIn(0f, 1f),
        blue = nb.coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}
