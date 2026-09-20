package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatMapImages
import cn.bit101.android.features.seat.model.SeatStatus
import kotlin.math.hypot

/**
 * 座位底图视图：**服务端真实房间图（五张状态图叠加） + 按坐标叠热区**。
 *
 * 为什么不用等宽方格：方格看不出房间布局，且 80dp 一格在手机上一屏只放得下 10 个座位。
 * 官方前端（`assets/seat-map.js`）的做法就是取 `/api/seat/map` 的**五张**底图
 * （free/book/close/leave/use），按座位状态各画一张 —— 这里用同一套数据、同样的叠加顺序。
 *
 * ⚠️ 早期只画了 `free` 一张图，结果**除了空闲座位，其它状态的座位在图上完全不存在**
 * （被预约/在用/暂停的座位在 free 图里就是空白），看起来「整个房间全是空闲」，
 * 丢失了最关键的信息。必须五张全叠：每张图只有对应状态的座位是亮的，叠加后才是完整状态。
 *
 * ✅ 实测确认（2026-09-20 拉取五张原图逐像素比对）：五张图**底部图例区完全一致**
 * （同坐标同像素值），叠加不会产生重影，直接用服务端自带的那条图例即可，无需自绘。
 *
 * 交互与实现要点：
 * - 底图是 **16:9 的横向房间图**。竖屏下宽度铺满后高度只占可视区约六成，上下留白是
 *   图片比例决定的必然结果（不是布局 bug）。初始让**整张图完整可见**并垂直居中，
 *   用户能看到房间轮廓与所选座位的相对位置 —— 底图的核心价值就是「认路」。
 * - 双指缩放**以双指中心为锚点**（不是左上角）：锚点不动、其它位置按比例扩散，
 *   符合「捏合放大看细节」的直觉。
 * - 点按命中用**坐标换算**，而不是给每个座位放一个可点 Box ——
 *   座位视觉方块只有约 2.5%×4.4%（手机上 10dp 量级），做成可点元素既点不中、
 *   又会被相邻座位抢走事件。
 * - 命中带手指宽容半径（[TAP_SLOP_DP]）：先看落点是否落在矩形内，否则取半径内最近的座位。
 */
@Composable
fun SeatMapCanvas(
    seats: List<Seat>,
    images: SeatMapImages?,
    selectedIds: Set<String>,
    pickable: (Seat) -> Boolean,
    onSeatTap: (Seat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mappable = remember(seats) { seats.filter { it.hasMapPosition } }

    // 可视区高度：用 onSizeChanged 实测，不用 BoxWithConstraints.maxHeight
    // （后者受父级约束影响时可能不等于最终布局高度）。
    var viewH by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewH = it.height.toFloat() }
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val tapSlopPx = with(density) { TAP_SLOP_DP.dp.toPx() }

        // 底图 16:9（服务端固定 1920×1080），按宽度铺满时的自然高度
        val imageH = viewW * IMAGE_BASE_HEIGHT / IMAGE_BASE_WIDTH

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableStateOf(false) }

        LaunchedEffect(mappable, viewW, viewH) {
            if (initialized || mappable.isEmpty() || viewW <= 0f || viewH <= 0f) return@LaunchedEffect
            initialized = true

            // 初始缩放：**整张底图完整可见**并垂直居中。
            //
            // 底图是 16:9（1920×1080）横图，竖屏可视区偏竖长，所以只能按**宽度**贴合
            // （即 scale = 1，`imageH` 本就是这个比例下的自然高度），
            // 高度方向必然留下上下留白 —— 这是图片比例决定的，不是布局缺陷。
            // 曾按「纵向铺满、无留白」做过一版（用户先选了这个），实测反而更差：
            // 图被放大到横向只能看到房间约 79%，认路时要一直左右拖，
            // 且一进门就是放大态、失去整体空间感。改回整图可见。
            val init = initialViewport(viewW, viewH)
            scale = init.scale
            offsetX = init.offsetX
            offsetY = init.offsetY
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
            // ── 五张状态底图按固定顺序叠加 ────────────────────────────────
            // 顺序即视觉优先级：暂停使用垫底（它是房间的「固定不可用」部分），
            // 空闲、预约、在用依次盖上，临时离开最后（最需要被看到的临时态）。
            // 服务端每张图里**只有对应状态的座位是亮的**，不叠就看不到那些座位。
            if (images != null) {
                listOfNotNull(
                    images.close,
                    images.free,
                    images.book,
                    images.use,
                    images.leave,
                ).forEach { url ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── 盖掉服务端底图自带的「返回 Back」按钮 ──────────────────────
            // `/api/seat/map` 的五张图里，右下角除了状态图例，还印着官方前端用的
            // 「返回 Back」按钮图形（图片内容，不可点）。我们的页面已有自己的顶栏返回键，
            // 这个假按钮只会误导用户去点。直接裁图会破坏 16:9 比例，
            // 所以只把按钮那一小块（见 SERVER_BACK_BTN_*）涂成底图底色。
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width
                val ch = size.width * IMAGE_BASE_HEIGHT / IMAGE_BASE_WIDTH
                drawRect(
                    color = SeatColors.serverBackButtonMask,
                    topLeft = Offset(
                        SERVER_BACK_BTN_LEFT / 100f * cw,
                        SERVER_BACK_BTN_TOP / 100f * ch,
                    ),
                    size = Size(
                        (SERVER_BACK_BTN_RIGHT - SERVER_BACK_BTN_LEFT) / 100f * cw,
                        (SERVER_BACK_BTN_BOTTOM - SERVER_BACK_BTN_TOP) / 100f * ch,
                    ),
                )
            }

            // 选中 / 我订的 标记画在最上层
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
                // 缩放 / 拖动 —— 以双指中心为锚点
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val next = applyTransform(
                            scale = scale, offsetX = offsetX, offsetY = offsetY,
                            centroidX = centroid.x, centroidY = centroid.y,
                            panX = pan.x, panY = pan.y, zoom = zoom,
                            viewW = viewW, viewH = viewH, imageH = imageH,
                        )
                        scale = next.scale
                        offsetX = next.offsetX
                        offsetY = next.offsetY
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

        if (images == null || images.isEmpty) {
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

/** 视口变换状态：缩放比例 + 平移偏移。抽出来是为了让几何逻辑可被单测覆盖。 */
internal data class ViewportState(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * 初始视口：**整张底图完整可见**（按宽度贴合，故 `scale == 1`），垂直居中、水平贴左。
 *
 * 底图恒为 16:9 横图，竖屏可视区更高，所以高度方向必然留白 —— 这是比例决定的。
 * `offsetY` 取负值（图比视口矮时）表示向下平移去做居中。
 */
internal fun initialViewport(viewW: Float, viewH: Float): ViewportState {
    val imageH = viewW * IMAGE_BASE_HEIGHT / IMAGE_BASE_WIDTH
    return ViewportState(
        scale = 1f,
        offsetX = 0f,
        offsetY = (viewH - imageH) / 2f,
    )
}

/**
 * 应用一次捏合/拖动，返回新的视口变换。
 *
 * **以双指中心（`centroidX/centroidY`）为锚点**缩放：变换前位于该点下方的图像点，
 * 变换后仍停在该点（这是「捏合看细节」的核心不变式；早期只按左上角缩放，手感很怪）。
 *
 * 推导：设变换前手指下的视图坐标为 `p = (centroid - offset) / scale`，
 * 要求 `centroid == offset' + p * scale'`，代入即得
 * `offset' = centroid - (centroid - offset) * (scale' / scale)`。
 * 拖动量 `pan` 直接叠加 —— 单指拖动时 `zoom == 1`，公式退化为纯平移。
 *
 * 结果会做边界钳制：图小于视口时居中，大于视口时不允许拖出边界，
 * 否则会拖出一片空白、露出画布底色。
 *
 * 参数刻意用散开的 Float 而不是 `Offset`：这样纯 JVM 单测无需把 compose-ui
 * 拖进 test classpath，几何逻辑可以被直接断言。
 */
internal fun applyTransform(
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    viewW: Float,
    viewH: Float,
    imageH: Float,
): ViewportState {
    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
    // 缩放被 MIN/MAX 截断时，实际生效的倍率要按截断后的比例算，否则锚点会漂移
    val realZoom = newScale / scale

    val nextX = centroidX - (centroidX - offsetX) * realZoom + panX
    val nextY = centroidY - (centroidY - offsetY) * realZoom + panY

    val w = viewW * newScale
    val h = imageH * newScale
    return ViewportState(
        scale = newScale,
        offsetX = if (w <= viewW) (viewW - w) / 2f else nextX.coerceIn(viewW - w, 0f),
        offsetY = if (h <= viewH) (viewH - h) / 2f else nextY.coerceIn(viewH - h, 0f),
    )
}

private const val IMAGE_BASE_WIDTH = 1920f
private const val IMAGE_BASE_HEIGHT = 1080f
private const val MIN_SCALE = 0.8f
private const val MAX_SCALE = 4f

/**
 * 服务端底图右下角自带「返回 Back」按钮的范围（相对底图的百分比）。
 *
 * `/api/seat/map` 的图是给官方前端用的，右下角同时印了图例和这个按钮。客户端已有
 * 自己的返回键，这个假按钮点了没反应、只会误导，故用底图底色（`(0,98,60)` 深绿）覆盖掉。
 *
 * 数值来自 2026-09-20 对 **1920×1080 原图**（`map_free.jpg`）的逐行像素扫描：
 * 按钮外框在 x 1747~1920、y 960~1056 → 百分比 91.0~100% / 88.9~97.8%。
 * 四边各留 1% 余量，避免残留边框。
 */
private const val SERVER_BACK_BTN_LEFT = 90.0f
private const val SERVER_BACK_BTN_RIGHT = 100.0f
private const val SERVER_BACK_BTN_TOP = 88.0f
private const val SERVER_BACK_BTN_BOTTOM = 100.0f

/** 手指落点与座位中心的宽容半径（超过即视为点空）。 */
private const val TAP_SLOP_DP = 22f
