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
import javax.inject.Inject



private val lightScheme = lightColorScheme(
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

private val darkScheme = darkColorScheme(
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

val LocalThemeIsDark = staticCompositionLocalOf<Boolean> { error("No theme provided") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BIT101Theme(
    content: @Composable () -> Unit
) {
    val vm: ThemeViewModel = hiltViewModel()

    val dynamicColor =
        if (vm.dynamicThemeFlow.collectAsState(initial = false).value)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else false

    val darkThemeMode by vm.darkThemeModeFlow.collectAsState(initial = DarkThemeMode.System)
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
            MaterialTheme(
                colorScheme = colors,
                content = content
            )
        }
    }
}

@HiltViewModel
internal class ThemeViewModel @Inject constructor(
    themeSettings: ThemeSettings
) : ViewModel() {
    val dynamicThemeFlow = themeSettings.dynamicTheme.flow
    val darkThemeModeFlow = themeSettings.darkThemeMode.flow
}