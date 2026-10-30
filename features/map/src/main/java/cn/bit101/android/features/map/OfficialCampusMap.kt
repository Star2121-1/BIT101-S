package cn.bit101.android.features.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 官方矢量校园平面图。**源**是官方 PDF（矢量），这里用 PyMuPDF 从 PDF 渲染的高清 PNG
 * 放 assets（良乡 3002×3721、中关村 10205×7229）。
 *
 * 为什么不是运行时 SVG：coil-svg 解析不了 MuPDF 导出的这份复杂 SVG（空白）；
 * 高清 PNG 观感与官方 PDF 一致，且 2400 解码宽已足够清晰。
 *
 * 良乡/中关村用官方图；其余校区回退 [VectorCampusMap]（手绘）或提示。
 */
private const val DECODE_WIDTH = 2400

@Composable
fun OfficialCampusMap(
    campusName: String,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    val asset = when {
        campusName.contains("良乡") -> "campus_liangxiang.jpg"
        campusName.contains("中关村") -> "campus_zhongguancun.jpg"
        else -> null
    }
    if (asset == null) {
        VectorCampusMap(campusName, modifier, highlight)
        return
    }

    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val st = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset += pan
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .transformable(st)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data("file:///android_asset/$asset")
                .size(DECODE_WIDTH)
                .build(),
            contentDescription = "官方校园平面图",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
