package cn.bit101.android.features.theme.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.PreviewSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 抽屉（规范 §5.6）。
 *
 * - 半展开：内容 **≤55% 屏高**，支持下滑关闭
 * - `surface` 底 + 顶部 24 圆角 + scrim 32% 黑 + Handle 32×4
 * - 拖拽：跟手；拖动中 scrim 不变；松手按**位置或速度**判定
 *   （位移 >120dp 或 下滑速度 >1500 ⇒ 关闭，320ms 出场）
 * - 禁用：抽屉**整体不可禁用**；内部按钮按 §5.2 禁用态
 * - 加载：元信息区出骨架 3 行（[GridSheetMetaRow] 的 `loading`），操作按钮由调用方置禁用
 * - 空态：缺失字段行显示「—」`tokens.textPlaceholder`，**不隐藏行**
 *
 * @param onDismiss 关闭回调 —— 抽屉播完 320ms 出场动画后才调，避免动画被中途掐掉。
 */
@Composable
fun GridBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screenHeightPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val maxSheetHeight = with(density) { (screenHeightPx * 0.55f).roundToInt().toDp() }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(320)
            onDismiss()
        }
    }

    val offsetY by animateFloatAsState(
        targetValue = if (visible) dragOffset else sheetHeightPx,
        animationSpec = tween(320),
        label = "sheetOffset",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // scrim 32% 黑（拖动过程中不变）
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, offsetY.roundToInt()) }
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .background(cs.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    },
                    onDragStopped = { velocity: Float ->
                        if (dragOffset > dismissThresholdPx || velocity > 1500f) {
                            visible = false
                        } else {
                            dragOffset = 0f
                        }
                    },
                )
                .onSheetHeightChanged { sheetHeightPx = it },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Handle 32×4
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .background(cs.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Box(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** 量抽屉真实高度（出场动画要按它滑出去）。 */
private fun Modifier.onSheetHeightChanged(onHeight: (Float) -> Unit): Modifier =
    this.onSizeChanged { size -> onHeight(size.height.toFloat()) }

/**
 * 抽屉元信息行（§5.6 / §3.7）：整行 44dp（43 内容 + 1dp 分隔线）。
 *
 * 左 label body-3 `onSurfaceVariant`（宽 72），右值 body-1 `onSurface`；
 * **缺值显示「—」`tokens.textPlaceholder`，不隐藏行**（J1：课程性质 / 课程类别是两个字段、两行）。
 */
@Composable
fun GridSheetMetaRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val tokens = GridTheme.tokens
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        if (loading) {
            GridSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(21.dp)
                    .padding(vertical = 11.dp, horizontal = 16.dp),
                cornerRadius = 8.dp,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(43.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = value?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isNullOrBlank()) tokens.textPlaceholder else cs.onSurface,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(cs.outlineVariant),
        )
    }
}

@Preview(name = "抽屉元信息 · 含缺值", showBackground = true)
@Composable
private fun PreviewGridSheetMeta() {
    PreviewSurface {
        Column {
            GridSheetMetaRow(label = "教师", value = "李明轩")
            GridSheetMetaRow(label = "课程性质", value = "必修")
            GridSheetMetaRow(label = "课程类别", value = "理论课")
            GridSheetMetaRow(label = "周次", value = "1-16")
            GridSheetMetaRow(label = "备注", value = null)
            GridSheetMetaRow(label = "教室", value = null, loading = true)
        }
    }
}
