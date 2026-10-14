package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface

/**
 * 进度环的两种「数据不可用」态 —— **文案与出口都不同，不得共用**（§5.9 最贵的一条）。
 */
enum class GridRingState {
    /** 正常（有数据） */
    Normal,

    /** 人在校内，但还没在认证页登录过 ⇒ 「去认证页登录」 */
    NotOnline,

    /** 多半不在校内 / 请求失败 ⇒ 「仅校园网环境可查」+ 重试 */
    Failed,
}

/**
 * 进度环（规范 §5.9：流量圆环 / 提交按钮内）。
 *
 * - 直径 96（大）/ 20（小），轨道 8dp（大）/ 1.5dp（小），轨道底 `tokens.surface3`
 * - 静态展示组件，**不响应按压**
 * - 数据不可用（[GridRingState.NotOnline] / [GridRingState.Failed]）⇒ **满环**（只画轨道）+ 中心「—」
 * - 加载（[progress] = null）⇒ 不确定态：1.4s 循环扫掠，中心数字换成 16dp 占位条
 * - **空态不存在**（H3）：阈值是 `CampusNetLogic.LIMIT_BYTES` = 300GB **固定常量**，恒存在
 *   ⇒ **禁止**出现「未设阈值 / 前往设置限速阈值」这类引导
 *
 * @param progress 0f..1f；传 null = 不确定态。
 */
@Composable
fun GridProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    state: GridRingState = GridRingState.Normal,
    strokeWidth: Dp = 8.dp,
    diameter: Dp = 96.dp,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = GridTheme.tokens.surface3,
    center: (@Composable () -> Unit)? = null,
) {
    val animated by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "ringProgress",
    )
    val indeterminate = progress == null && state == GridRingState.Normal

    // 不确定态的扫掠角在**组合层**算好，再传给 drawBehind（draw lambda 里不能起 Compose 状态）
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "ringSweep")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ringSweepAngle",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                // 轨道：始终满环
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                when {
                    // 数据不可用：只剩满环（中心「—」由 content 给）
                    state != GridRingState.Normal -> Unit

                    indeterminate -> rotate(degrees = sweepAngle) {
                        drawArc(
                            color = progressColor,
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = stroke,
                        )
                    }

                    else -> drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        style = stroke,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (center != null) center()
    }
}

/**
 * 流量圆环（§5.9 的「大环」用法，含**两种不可用的不同文案**）。
 *
 * @param usedBytes 已用流量；`null` = 加载中
 * @param limitBytes 阈值 —— **固定常量** 300GB，不是用户设置项，故**无「未设阈值」态**
 */
@Composable
fun GridTrafficRing(
    usedBytes: Long?,
    limitBytes: Long,
    state: GridRingState,
    modifier: Modifier = Modifier,
    centerText: (@Composable () -> Unit)? = null,
    notOnlineAction: (@Composable () -> Unit)? = null,
    retryAction: (@Composable () -> Unit)? = null,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    val progress = usedBytes?.let { (it.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f) }
    // §8.8：>90% 琥珀（attention，必须配非颜色线索「已用 N%」）、>100% 红（urgent）
    val color = when {
        state != GridRingState.Normal -> tokens.surface3
        progress == null -> cs.primary
        progress > 1f -> tokens.stateUrgent
        progress > 0.9f -> tokens.stateAttention
        else -> cs.primary
    }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GridProgressRing(
            progress = progress,
            state = state,
            progressColor = color,
            center = {
                if (state == GridRingState.Normal) {
                    centerText?.invoke()
                } else {
                    Text(
                        text = "—",
                        style = tokens.numMd,
                        color = tokens.textPlaceholder,
                    )
                }
            },
        )
        when (state) {
            GridRingState.NotOnline -> {
                Text(
                    text = "你在校内，但还没在认证页登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (notOnlineAction != null) {
                    Box(modifier = Modifier.padding(top = 8.dp)) { notOnlineAction() }
                }
            }

            GridRingState.Failed -> {
                Text(
                    text = "仅校园网环境可查",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (retryAction != null) {
                    Box(modifier = Modifier.padding(top = 8.dp)) { retryAction() }
                }
            }

            GridRingState.Normal -> Unit
        }
    }
}

/** 环中心大数字（§8.7 num-lg，自带 tabular）。 */
@Composable
fun GridRingValue(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text = text, style = GridTheme.tokens.numLg, color = color)
}

@Preview(name = "进度环 · 三态 + 小环", showBackground = true)
@Composable
private fun PreviewGridProgressRing() {
    val limit = 300L * 1024 * 1024 * 1024
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridTrafficRing(
                usedBytes = (limit * 0.62f).toLong(),
                limitBytes = limit,
                state = GridRingState.Normal,
                modifier = Modifier.weight(1f),
                centerText = { GridRingValue("62%") },
            )
            GridTrafficRing(
                usedBytes = null,
                limitBytes = limit,
                state = GridRingState.Normal,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GridTrafficRing(
                usedBytes = null,
                limitBytes = limit,
                state = GridRingState.NotOnline,
                modifier = Modifier.weight(1f),
                notOnlineAction = { GridButton(text = "去认证页登录", onClick = {}) },
            )
            GridTrafficRing(
                usedBytes = null,
                limitBytes = limit,
                state = GridRingState.Failed,
                modifier = Modifier.weight(1f),
                retryAction = { GridButton(text = "重试", onClick = {}, variant = GridButtonVariant.Outlined) },
            )
        }
        GridProgressRing(
            progress = null,
            diameter = 20.dp,
            strokeWidth = 1.5.dp,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
