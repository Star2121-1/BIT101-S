package cn.bit101.android.features.theme

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.ThemeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.runBlocking
import javax.inject.Inject



internal val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

internal val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

/**
 * 当前是否深色 —— 由 `BIT101Theme` 注入（与 [LocalGridTokens] 成对提供）。
 *
 * ⚠️ **默认值为 `error("No theme provided")`**：读它而不在 `BIT101Theme` 里，
 * 会在组合期直接抛异常（不是「取到 false」）。已知读它的组件：
 * `GridButton`（按压色档要分深浅）、`GridSnackbar`（暗色是浅色卡，靠它判断）、
 * `features/web` 的 `WebScreen`。
 *
 * ⇒ **这些组件必须在 `BIT101Theme` 内使用**。App 内没问题；
 * 新入口（独立 Activity / 独立预览 / RemoteViews 之外的自绘）若直接渲染它们会崩，
 * 用 `PreviewSurface`（预览）或包一层 `BIT101Theme` 解决。
 */
val LocalThemeIsDark = staticCompositionLocalOf<Boolean> { error("No theme provided") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BIT101Theme(
    content: @Composable () -> Unit
) {
    val vm: ThemeViewModel = hiltViewModel()

    val dynamicColor =
        if (vm.dynamicThemeFlow.collectAsState(initial = vm.dynamicThemeInitial).value)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else false

    val darkThemeMode by vm.darkThemeModeFlow.collectAsState(initial = vm.darkThemeModeInitial)
    val useDarkTheme = when (darkThemeMode) {
        is DarkThemeMode.Dark -> true
        is DarkThemeMode.Light -> false
        else -> isSystemInDarkTheme()
    }

    // 应用 Material You 动态颜色
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dynamicColor && useDarkTheme -> dynamicDarkColorScheme(
            LocalContext.current
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dynamicColor && !useDarkTheme -> dynamicLightColorScheme(
            LocalContext.current
        )

        useDarkTheme -> darkScheme
        else -> lightScheme
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        CompositionLocalProvider(LocalThemeIsDark provides useDarkTheme) {
            CompositionLocalProvider(
                LocalGridTokens provides if (useDarkTheme) darkGridTokens else lightGridTokens
            ) {
                MaterialTheme(
                    colorScheme = colors,
                    content = content
                )
            }
        }
    }
}

@HiltViewModel
internal class ThemeViewModel @Inject constructor(
    themeSettings: ThemeSettings
) : ViewModel() {
    /**
     * 动态取色的**首帧初值** —— 写死 false，**不**为它阻塞主线程读盘。
     *
     * 真机验证（PDNP05J000120402，v1.12.0-alpha.1 冷启动录屏 ×5 + 无此读盘的探针包 ×5）：
     * 深色会话下**确实存在一段约 0.4s 的白色窗口**，但根因是 `Theme.BIT101` 的 parent 为
     * `android:Theme.Material.Light.NoActionBar`（**没有 values-night 变体**）⇒ 窗口背景不随
     * 主题走，深色内容画出来之前就是白的。这段白窗与 runBlocking 无关（探针包一样有），
     * 真正的修法是补 `values-night/themes.xml`，不在此处。
     *
     * 既然白窗无论如何都在，就不该再为「省一帧动态取色」往首帧多塞一次同步读盘
     * （DataStore 首次 get() 会顺带初始化整个 DataStore，成本不止一个布尔）。
     * 代价只有「主动开启动态取色」的用户首帧看到静态配色再跳成壁纸色一帧；
     * 且默认值已改 false（R3），这类用户本来就少。
     * ⇒ 保持 false。若日后补上 values-night 主题，可再改回取持久化值。
     */
    val dynamicThemeInitial: Boolean = false

    /**
     * 暗黑模式的**首帧初值** —— 取持久化值，**值得**这一次同步读盘（与 [dynamicThemeInitial] 相反）。
     *
     * 为什么这里值得、动态取色那里不值得：`collectAsState(initial = …)` 的 initial 就是用户看到的
     * **第一帧**。写死 `DarkThemeMode.System` 时，「显式设过深色 / 浅色」的用户会先看到**整个一帧
     * 错的主题**（系统浅色 ⇒ 首帧整套浅色 UI，等 flow 发射持久化值才整屏翻成深色）——
     * 这是**整屏主题翻转**，比动态取色那「一帧配色差异」严重得多。
     *
     * 读失败（文件损坏等）退回 System，与旧行为一致。
     */
    val darkThemeModeInitial: DarkThemeMode = runBlocking {
        runCatching { themeSettings.darkThemeMode.get() }.getOrDefault(DarkThemeMode.System)
    }

    val dynamicThemeFlow = themeSettings.dynamicTheme.flow
    val darkThemeModeFlow = themeSettings.darkThemeMode.flow
}