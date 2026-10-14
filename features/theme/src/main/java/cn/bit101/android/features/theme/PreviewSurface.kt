package cn.bit101.android.features.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * **仅供 @Preview 使用**的容器。
 *
 * 为什么不能直接套 [BIT101Theme]：它内部 `hiltViewModel()` 取 ThemeViewModel，
 * 预览环境没有 Hilt，会直接崩。这里绕开 ViewModel，直接把 P1 造好的两套东西装上：
 * - `LightScheme` / [darkScheme]（[Color.kt] 的 M3 角色）
 * - [lightGridTokens] / [darkGridTokens]（[GridTokens] 的语义词 + 12 色板）
 *
 * 与真机渲染的差别**只有**「多包了一层 [Surface] 给背景色」和「没有 LocalThemeIsDark」，
 * 组件层的颜色与真机一致 ⇒ 预览里看到的配色就是真机配色。
 */
@Composable
fun PreviewSurface(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkScheme else lightScheme
    val tokens = if (darkTheme) darkGridTokens else lightGridTokens
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
