package cn.bit101.android.features.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.CourseColorFamily
import cn.bit101.android.features.theme.palette.Palettes

/**
 * **仅供 @Preview 使用**的容器。
 *
 * 为什么不能直接套 [BIT101Theme]：它内部 `hiltViewModel()` 取 ThemeViewModel，
 * 预览环境没有 Hilt，会直接崩。这里绕开 ViewModel，直接从 [Palettes] 取当前
 * palette / 明暗的两套东西装上：
 * - `Palettes.scheme(...)`（M3 角色）
 * - `Palettes.tokens(...)`（[GridTokens] 的语义词 + 12 色板）
 *
 * [palette] 参数（默认 [AppPalette.Warm]）让 @Preview 能直接看三套配色——
 * P5-B 阶段切 palette 的 UI 还没有（P5-C），预览是唯一能"看见" classic / brand 的地方。
 *
 * 与真机渲染的差别**只有**「多包了一层 [Surface] 给背景色」和「没有 LocalThemeIsDark」，
 * 组件层的颜色与真机一致 ⇒ 预览里看到的配色就是真机配色。
 */
@Composable
fun PreviewSurface(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    palette: AppPalette = AppPalette.Brand,
    content: @Composable () -> Unit,
) {
    val scheme = Palettes.scheme(palette, isDark = darkTheme)
    val tokens = Palettes.tokens(
        palette = palette,
        isDark = darkTheme,
        family = CourseColorFamily.Auto,
    )
    CompositionLocalProvider(
        LocalGridTokens provides tokens,
        LocalThemeIsDark provides darkTheme,
    ) {
        MaterialTheme(colorScheme = scheme) {
            Surface(
                modifier = modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
