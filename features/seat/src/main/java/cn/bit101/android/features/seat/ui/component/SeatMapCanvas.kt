package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatStatus
import kotlin.math.hypot
import kotlin.math.min

/**
 * 座位底图视图：**服务端真实房间图 + 按坐标叠热区**。
 *
 * 为什么不用等宽方格：方格看不出房间布局，且 80dp 一格在手机上一屏只放得下 10 个座位。
 * 官方前端（`assets/seat-map.js`）的做法就是取 `/api/seat/map` 的底图，
 * 再按 `point_x/point_y/width/height`（**百分比**）定位每个座位 —— 这里用同一套数据。
 *
 * 交互与实现要点：
 * - 初始自动缩放到**座位密集区**（由座位坐标包围盒算出）。整张 16:9 图缩到手机宽度后，
 *   座位号只有几像素高，不缩放根本看不清。
 * - 双指缩放 / 拖动。点按命中用**坐标换算**，而不是给每个座位放一个可点 Box ——
 *   座位视觉方块只有约 2.5%×4.4%（手机上 10dp 量级），做成可点元素既点不中、
 *   又会被相邻座位抢走事件。
 * - 命中带手指宽容半径（[TAP_SLOP_DP]）：先看落点是否落在矩形内，否则取半径内最近的座位。
 */
@Composable
fun SeatMapCanvas(
    seats: List<Seat>,
    imageUrl: String?,
    selectedIds: Set<String>,
    pickable: (Seat) -> Boolean,
    onSeatTap: (Seat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mappable = remember(seats) { seats.filter { it.hasMapPosition } }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        val tapSlopPx = with(density) { TAP_SLOP_DP.dp.toPx() }

        // 底图统一按 16:9 铺满视图宽度（服务端固定 1920×1080）
        val imageH = viewW * IMAGE_BASE_HEIGHT / IMAGE_BASE_WIDTH

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableStateOf(false) }

        LaunchedEffect(mappable, viewW, viewH) {
            if (initialized || mappable.isEmpty() || viewW <= 0f) return@LaunchedEffect
            initialized = true
            val minX = mappable.minOf { it.pointX!! }
            val maxX = mappable.maxOf { it.pointX!! + it.width!! }
            val minY = mappable.minOf { it.pointY!! }
            val maxY = mappable.maxOf { it.pointY!! + it.height!! }

            val boxW = ((maxX - minX) / 100f).coerceAtLeast(0.05f) * viewW
            val boxH = ((maxY - minY) / 100f).coerceAtLeast(0.05f) * imageH
            val fit = min(viewW / boxW, viewH / boxH) * INITIAL_PADDING
            scale = fit.coerceIn(MIN_SCALE, MAX_SCALE)

            val centerX = ((minX + maxX) / 200f) * viewW
            val centerY = ((minY + maxY) / 200f) * imageH
            offsetX = viewW / 2f - centerX * scale
            offsetY = viewH / 2f - centerY * scale
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 选中 / 我订的 标记画在底图之上（底图本身已带状态配色，不需要再画一遍底色）
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width
                val ch = size.width * IMAGE_BASE_HEIGHT / IMAGE_BASE_WIDTH
                mappable.forEach { seat ->
                    val x = seat.pointX!! / 100f * cw
                    val y = seat.pointY!! / 100f * ch
                    val w = seat.width!! / 100f * cw
                    val h = seat.height!! / 100f * ch
                    when {
                        seat.id in selectedIds -> drawRect(
                            color = SeatColors.selected,
                            topLeft = Offset(x - 2f, y - 2f),
                            size = Size(w + 4f, h + 4f),
                            style = Stroke(width = 4f)
                        )
                        seat.status == SeatStatus.MINE -> drawRect(
                            color = SeatColors.mine,
                            topLeft = Offset(x - 2f, y - 2f),
                            size = Size(w + 4f, h + 4f),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 缩放 / 拖动
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
                // 点按选中
                .pointerInput(mappable, scale, offsetX, offsetY) {
                    detectTapGestures { tap ->
                        val pctX = (tap.x - offsetX) / scale / viewW * 100f
                        val pctY = (tap.y - offsetY) / scale / imageH * 100f

                        val inside = mappable.firstOrNull { s ->
                            pctX >= s.pointX!! && pctX <= s.pointX!! + s.width!! &&
                                pctY >= s.pointY!! && pctY <= s.pointY!! + s.height!!
                        }
                        val hit = inside ?: mappable.minByOrNull { s ->
                            distancePx(s, pctX, pctY, viewW, imageH, scale)
                        }?.takeIf { s -> distancePx(s, pctX, pctY, viewW, imageH, scale) <= tapSlopPx }

                        hit?.let { seat -> if (pickable(seat)) onSeatTap(seat) }
                    }
                }
        )

        if (imageUrl == null) {
            Text(
                text = "该区域暂无房间底图，已切换为方格视图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
            )
        }
    }
}

/** 座位中心与触点的屏幕距离（像素）。 */
private fun distancePx(
    seat: Seat,
    pctX: Float,
    pctY: Float,
    viewW: Float,
    imageH: Float,
    scale: Float,
): Float {
    val dx = (pctX - (seat.pointX!! + seat.width!! / 2f)) / 100f * viewW * scale
    val dy = (pctY - (seat.pointY!! + seat.height!! / 2f)) / 100f * imageH * scale
    return hypot(dx, dy)
}

private const val IMAGE_BASE_WIDTH = 1920f
private const val IMAGE_BASE_HEIGHT = 1080f
private const val MIN_SCALE = 0.8f
private const val MAX_SCALE = 4f
private const val INITIAL_PADDING = 0.92f

/** 手指落点与座位中心的宽容半径（超过即视为点空）。 */
private const val TAP_SLOP_DP = 22f
