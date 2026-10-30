package cn.bit101.android.features.theme

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.AppSkin
import cn.bit101.android.config.setting.base.CourseColorFamily
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.ThemeSettings
import cn.bit101.android.features.theme.palette.Palettes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * 当前是否深色 —— 由 `BIT101Theme` 注入（与 [LocalGridTokens] 成对提供）。
 *
 * ⚠️ **默认值为 `error("No theme provided")`**：读它而不在 `BIT101Theme` 里，
 * 会在组合期直接抛异常（不是「取到 false」）。已知读它的组件：
 * `GridSnackbar`（暗色是浅色卡，靠它判断）、`features/web` 的 `WebScreen`。
 * （`GridButton` 在 P5-B 起不再读它——按压色档改由 tokens 按明暗取好，见
 * `palette/PaletteScheme.pressed`。）
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

    // 外观三轴（P5-B：palette 已接线生效；skin 只落 VM flow，P5-F 才消费；
    // courseColorFamily 只落 VM flow，P5-D 族表落地后才影响取色）
    val appPalette by vm.appPaletteFlow.collectAsState(initial = vm.appPaletteInitial)
    val courseColorFamily by vm.courseColorFamilyFlow.collectAsState(initial = vm.courseColorFamilyInitial)

    // 应用 Material You 动态颜色
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dynamicColor && useDarkTheme -> dynamicDarkColorScheme(
            LocalContext.current
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dynamicColor && !useDarkTheme -> dynamicLightColorScheme(
            LocalContext.current
        )

        useDarkTheme -> Palettes.scheme(appPalette, isDark = true)
        else -> Palettes.scheme(appPalette, isDark = false)
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        CompositionLocalProvider(LocalThemeIsDark provides useDarkTheme) {
            CompositionLocalProvider(
                LocalGridTokens provides Palettes.tokens(
                    palette = appPalette,
                    isDark = useDarkTheme,
                    family = courseColorFamily,
                )
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
    themeSettings: ThemeSettings,
    courseScheduleSettings: CourseScheduleSettings,
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
     * 外观四项的**首帧初值** —— 取持久化值，合并在**同一个 runBlocking 块**里读
     * （初稿 §5.5：DataStore 首次 get() 已初始化整个 DataStore，多读三个 string 键
     * 增量成本 ≈ 0，不新开第二个 runBlocking）。
     *
     * 为什么值得同步读：`collectAsState(initial = …)` 的 initial 就是用户看到的
     * **第一帧**。写死默认值时，「显式设过深色 / 浅色」的用户会先看到**整个一帧
     * 错的主题**（系统浅色 ⇒ 首帧整套浅色 UI，等 flow 发射持久化值才整屏翻成深色）——
     * 这是**整屏主题翻转**，比动态取色那「一帧配色差异」严重得多。palette 同理：
     * 默认 Warm 与用户选的 Classic 差一整套面色族，首帧不能猜错。
     *
     * 读失败（文件损坏等）退回各轴默认值，与旧行为一致
     * （palette=Warm / skin=Classic / family=Auto，均为 P5-A 定死的显式默认，
     * 不用 `first()`——展示序与默认值不一致，见 AppPalette KDoc）。
     */
    private val initialAppearance: AppearanceInitials = runBlocking {
        AppearanceInitials(
            darkThemeMode = runCatching { themeSettings.darkThemeMode.get() }
                .getOrDefault(DarkThemeMode.System),
            appPalette = runCatching { themeSettings.appPalette.get() }
                .getOrDefault(AppPalette.Brand),
            appSkin = runCatching { themeSettings.appSkin.get() }
                .getOrDefault(AppSkin.Classic),
            courseColorFamily = runCatching { courseScheduleSettings.courseColorFamily.get() }
                .getOrDefault(CourseColorFamily.Auto),
        )
    }

    /** 暗黑模式首帧初值（原 [darkThemeModeInitial] 语义，值来自合并读盘块）。 */
    val darkThemeModeInitial: DarkThemeMode
        get() = initialAppearance.darkThemeMode

    /** palette 首帧初值（默认 Warm）。 */
    val appPaletteInitial: AppPalette
        get() = initialAppearance.appPalette

    /** skin 首帧初值（默认 Classic；P5-F 才消费）。 */
    val appSkinInitial: AppSkin
        get() = initialAppearance.appSkin

    /** 课程色族首帧初值（默认 Auto；P5-D 族表落地后才影响取色）。 */
    val courseColorFamilyInitial: CourseColorFamily
        get() = initialAppearance.courseColorFamily

    val dynamicThemeFlow = themeSettings.dynamicTheme.flow
    val darkThemeModeFlow = themeSettings.darkThemeMode.flow

    /** 配色轴 flow（P5-C 设置页与主题层共用）。 */
    val appPaletteFlow = themeSettings.appPalette.flow

    /** 形态轴 flow（P5-C 设置页 / P5-F 图表重建消费；P5-B 只落位）。 */
    val appSkinFlow = themeSettings.appSkin.flow

    /** 课程色族 flow（存盘原值，可能为 Auto；按 palette 解析属 P5-D）。 */
    val courseColorFamilyFlow = courseScheduleSettings.courseColorFamily.flow
}

/** 外观四项的首帧初值（合并在一个 runBlocking 块里读，见 [ThemeViewModel] KDoc）。 */
private data class AppearanceInitials(
    val darkThemeMode: DarkThemeMode,
    val appPalette: AppPalette,
    val appSkin: AppSkin,
    val courseColorFamily: CourseColorFamily,
)
