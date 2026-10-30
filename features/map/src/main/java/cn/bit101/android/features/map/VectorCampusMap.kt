package cn.bit101.android.features.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import cn.bit101.android.data.school.CampusFootprints
import cn.bit101.android.data.school.CampusLocations
import cn.bit101.android.data.school.CampusVectors
import kotlin.math.min

/** 按楼名前缀归到「园/书院/组团」，返回一个 pastel 色相（0-360）。 */
private fun zoneHue(name: String?): Float {
    if (name == null) return 210f
    return when {
        name.contains("丹枫") -> 285f
        name.contains("劲松") -> 305f
        name.contains("甘棠") -> 30f
        name.contains("勤园") -> 120f
        name.contains("静园") -> 265f
        name.contains("疏桐") -> 250f
        name.contains("博雅") || name.contains("至善") -> 275f
        name.contains("文萃") -> 215f
        name.contains("综教") || name.contains("理教") || name.contains("理学") ||
            name.contains("理科") || name.contains("物理") || name.contains("化学") -> 190f
        name.contains("文博") || name.contains("文体") -> 170f
        name.contains("食堂") -> 25f
        name.contains("公寓") || name.contains("宿舍") -> 270f
        else -> 210f
    }
}

/**
 * 校区「矢量示意图」，仿官方平面图风格：
 * 分区上色的楼栋面 + 路网 + 水面 + 楼名标签；等比缩放、可捏合。全是矢量，放大不糊。
 * 官方那种 3D 楼图标矢量画不出（也不该），这里用平面填色近似。
 */
@Composable
fun VectorCampusMap(
    campusName: String,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    val footprints = remember(campusName) {
        CampusFootprints.ALL.filter { it.campusName == campusName }
    }
    val dots = remember(campusName) {
        CampusLocations.ALL.filter { it.campusName == campusName }
    }
    val roads = remember(campusName) {
        CampusVectors.ROADS.filter { it.campusName == campusName }
    }
    val waters = remember(campusName) {
        CampusVectors.WATER.filter { it.campusName == campusName }
    }

    if (footprints.isEmpty() && dots.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("该校区暂无矢量示意图", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val measurer = rememberTextMeasurer()
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(0.6f, 8f)
        offset += pan
    }

    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    val gridC = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val strokeC = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val roadC = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val waterC = Color.hsv(205f, 0.35f, 0.90f)
    val hiC = MaterialTheme.colorScheme.tertiary
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)

    // 包围盒（含道路/水面，避免被裁掉）
    val allX = ArrayList<Double>()
    val allY = ArrayList<Double>()
    fun addFlat(flat: FloatArray) {
        var i = 0
        while (i + 1 < flat.size) { allX.add(flat[i].toDouble()); allY.add(flat[i + 1].toDouble()); i += 2 }
    }
    if (footprints.isNotEmpty()) footprints.forEach { addFlat(it.flat) } else dots.forEach { allX.add(it.x); allY.add(it.y) }
    // ⚠️ 包围盒只按「楼栋」定：道路/水面范围远大于校区，纳入会让校区缩成一小块。

    val minX = allX.min(); val maxX = allX.max()
    val minY = allY.min(); val maxY = allY.max()
    val spanX = (maxX - minX).coerceAtLeast(1e-9)
    val spanY = (maxY - minY).coerceAtLeast(1e-9)
    val midX = (minX + maxX) / 2.0
    val midY = (minY + maxY) / 2.0

    BoxWithConstraints(modifier.fillMaxSize().background(bg).transformable(transformState)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pad = 36f
        val s = min(((w - 2 * pad) / spanX), ((h - 2 * pad) / spanY))
        val cx = w / 2.0
        val cy = h / 2.0
        fun mx(x: Double) = (cx + (x - midX) * s).toFloat()
        fun my(y: Double) = (cy + (y - midY) * s).toFloat()
        fun flatPath(flat: FloatArray, close: Boolean): Path {
            val p = Path()
            var i = 0
            while (i + 1 < flat.size) {
                val x = mx(flat[i].toDouble()); val y = my(flat[i + 1].toDouble())
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                i += 2
            }
            if (close) p.close()
            return p
        }

        Canvas(Modifier.fillMaxSize()) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset(this.size.width / 2f, this.size.height / 2f))
            }) {
                // 网格
                var gx = 0f
                while (gx < w) { drawLine(gridC, Offset(gx, 0f), Offset(gx, h), 1f); gx += 56f }
                var gy = 0f
                while (gy < h) { drawLine(gridC, Offset(0f, gy), Offset(w, gy), 1f); gy += 56f }

                // 水面
                waters.forEach { drawPath(flatPath(it.flat, close = true), waterC, style = Fill) }
                // 路网
                roads.forEach { drawPath(flatPath(it.flat, close = false), roadC, style = Stroke(width = 2.2f)) }

                // 楼栋
                if (footprints.isNotEmpty()) {
                    footprints.forEach { fp ->
                        val n = fp.flat.size / 2
                        if (n < 3) return@forEach
                        val nm = fp.name
                        val isHi = highlight != null && nm != null &&
                            (nm == highlight || highlight.contains(nm))
                        val fill = if (isHi) hiC else Color.hsv(zoneHue(nm), 0.22f, 0.93f)
                        val path = flatPath(fp.flat, close = true)
                        drawPath(path, fill, style = Fill)
                        drawPath(path, strokeC, style = Stroke(width = 1f))

                        if (nm != null && scale >= 0.9f) {
                            var ax = 0.0; var ay = 0.0
                            var i = 0
                            while (i + 1 < fp.flat.size) { ax += mx(fp.flat[i].toDouble()); ay += my(fp.flat[i + 1].toDouble()); i += 2 }
                            ax /= n; ay /= n
                            val layout = measurer.measure(AnnotatedString(nm), style = labelStyle)
                            val lw = layout.size.width.toFloat()
                            val lh = layout.size.height.toFloat()
                            drawRoundRect(
                                color = labelBg,
                                topLeft = Offset((ax - lw / 2f - 3f).toFloat(), (ay - lh / 2f - 1f).toFloat()),
                                size = Size(lw + 6f, lh + 2f),
                                cornerRadius = CornerRadius(4f, 4f),
                            )
                            drawText(layout, topLeft = Offset((ax - lw / 2f).toFloat(), (ay - lh / 2f).toFloat()))
                        }
                    }
                } else {
                    dots.forEach { p ->
                        val x = mx(p.x); val y = my(p.y)
                        val isHi = highlight != null && (p.name == highlight || p.aliases.contains(highlight))
                        drawCircle(if (isHi) hiC else Color.hsv(zoneHue(p.name), 0.35f, 0.8f), if (isHi) 6f else 3.5f, Offset(x, y))
                        val layout = measurer.measure(AnnotatedString(p.name), style = labelStyle)
                        drawText(layout, topLeft = Offset(x + 5f, y - layout.size.height / 2f))
                    }
                }
            }
        }
    }
}
