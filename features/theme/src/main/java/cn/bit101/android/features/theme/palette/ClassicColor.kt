package cn.bit101.android.features.theme.palette

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import cn.bit101.android.features.theme.errorContainerDark
import cn.bit101.android.features.theme.errorContainerLight
import cn.bit101.android.features.theme.errorDark
import cn.bit101.android.features.theme.errorLight
import cn.bit101.android.features.theme.onErrorContainerDark
import cn.bit101.android.features.theme.onErrorContainerLight
import cn.bit101.android.features.theme.onErrorDark
import cn.bit101.android.features.theme.onErrorLight

/**
 * 暖棕配色（展示名，P5-C 起；master `Color.kt` 回迁 + object 包裹）。
 *
 * M3 角色 = `git show master:…/Color.kt` **逐格回迁**（master 表现已由 Material Theme
 * Builder 产出、角色间对比关系经验证；对比度验收见提交说明）。error 族按 §5.2 跨
 * palette 共享，取共享值（warm 的 #B3261E 一族）而非 master 的 #BA1A1A 一族——
 * 「error 是语义色不是品牌色」，这是**已知的忠实度偏差**，报给「像不像原版」评审。
 *
 * ⚠️⚠️ **非 M3 token（面色族 / 文字档 / 按压档）是新增设计，不是「回到原版」**
 * （初稿 §5.2 / G-3 / §10-N3）：master 的 Color.kt/Theme.kt 里**从来没有**
 * surface-2/3、disabledBg、skeleton、pressed 这些概念，而 P2 组件库全部依赖
 * LocalGridTokens ⇒「GRID 组件 × 原版配色」需要凭空定一套非 M3 语义色。
 * 下表带【提案】标记的值均为**非官方、待设计确认**的提案：
 * 取法 = 把 warm 同名 token 与其参照色的**相对步长**搬到 master 暖棕中性梯上
 * （如 disabledBg = surface-3 暗一档；pressed = primary 按 warm 相对步长暗一档）。
 * surface2/surface3 亮色两档不是提案——按 `--surface-2 → surfaceVariant`、
 * `--surface-3 → outlineVariant` 的既有约定直接取 master 对应角色值。
 * P5-F（GRID skin 启用）前需设计评审拍板，别临场改。
 */
internal object ClassicColor {

    // ── 亮色 M3 角色（master 回迁，值不动）──

    private val primaryLight = Color(0xFF8B4F26)
    private val onPrimaryLight = Color(0xFFFFFFFF)
    private val primaryContainerLight = Color(0xFFFFDBC8)
    private val onPrimaryContainerLight = Color(0xFF6F3810)
    private val secondaryLight = Color(0xFF755847)
    private val onSecondaryLight = Color(0xFFFFFFFF)
    private val secondaryContainerLight = Color(0xFFFFDBC8)
    private val onSecondaryContainerLight = Color(0xFF5C4131)
    private val tertiaryLight = Color(0xFF616033)
    private val onTertiaryLight = Color(0xFFFFFFFF)
    private val tertiaryContainerLight = Color(0xFFE8E5AC)
    private val onTertiaryContainerLight = Color(0xFF49491E)
    private val backgroundLight = Color(0xFFFFF8F5)
    private val onBackgroundLight = Color(0xFF221A15)
    private val surfaceLight = Color(0xFFFFF8F5)
    private val onSurfaceLight = Color(0xFF221A15)
    private val surfaceVariantLight = Color(0xFFF4DED3)
    private val onSurfaceVariantLight = Color(0xFF52443C)
    private val outlineLight = Color(0xFF85746B)
    private val outlineVariantLight = Color(0xFFD7C2B8)
    private val scrimLight = Color(0xFF000000)
    private val inverseSurfaceLight = Color(0xFF382E29)
    private val inverseOnSurfaceLight = Color(0xFFFFEDE5)
    private val inversePrimaryLight = Color(0xFFFFB68A)
    private val surfaceDimLight = Color(0xFFE7D7CF)
    private val surfaceBrightLight = Color(0xFFFFF8F5)
    private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowLight = Color(0xFFFFF1EA)
    private val surfaceContainerLight = Color(0xFFFCEAE2)
    private val surfaceContainerHighLight = Color(0xFFF6E5DD)
    private val surfaceContainerHighestLight = Color(0xFFF0DFD7)

    // ── 暗色 M3 角色（master 回迁，值不动）──

    private val primaryDark = Color(0xFFFFB68A)
    private val onPrimaryDark = Color(0xFF522300)
    private val primaryContainerDark = Color(0xFF6F3810)
    private val onPrimaryContainerDark = Color(0xFFFFDBC8)
    private val secondaryDark = Color(0xFFE5BFA9)
    private val onSecondaryDark = Color(0xFF432B1C)
    private val secondaryContainerDark = Color(0xFF5C4131)
    private val onSecondaryContainerDark = Color(0xFFFFDBC8)
    private val tertiaryDark = Color(0xFFCBC992)
    private val onTertiaryDark = Color(0xFF323209)
    private val tertiaryContainerDark = Color(0xFF49491E)
    private val onTertiaryContainerDark = Color(0xFFE8E5AC)
    private val backgroundDark = Color(0xFF1A120D)
    private val onBackgroundDark = Color(0xFFF0DFD7)
    private val surfaceDark = Color(0xFF1A120D)
    private val onSurfaceDark = Color(0xFFF0DFD7)
    private val surfaceVariantDark = Color(0xFF52443C)
    private val onSurfaceVariantDark = Color(0xFFD7C2B8)
    private val outlineDark = Color(0xFF9F8D84)
    private val outlineVariantDark = Color(0xFF52443C)
    private val scrimDark = Color(0xFF000000)
    private val inverseSurfaceDark = Color(0xFFF0DFD7)
    private val inverseOnSurfaceDark = Color(0xFF382E29)
    private val inversePrimaryDark = Color(0xFF8B4F26)
    private val surfaceDimDark = Color(0xFF1A120D)
    private val surfaceBrightDark = Color(0xFF413732)
    private val surfaceContainerLowestDark = Color(0xFF140D08)
    private val surfaceContainerLowDark = Color(0xFF221A15)
    private val surfaceContainerDark = Color(0xFF261E19)
    private val surfaceContainerHighDark = Color(0xFF312823)
    private val surfaceContainerHighestDark = Color(0xFF3D332D)

    /** 亮色 M3 角色全表（master 回迁；error 族用共享值）。 */
    val lightScheme = lightColorScheme(
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

    /** 暗色 M3 角色全表（master 回迁；error 族用共享值）。 */
    val darkScheme = darkColorScheme(
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
     * 亮色非 M3 token。
     * surface2/surface3 = master surfaceVariant/outlineVariant（既有约定，非提案）；
     * 其余四项【提案】。
     */
    val light = PaletteScheme(
        scheme = lightScheme,
        surface2 = Color(0xFFF4DED3),
        surface3 = Color(0xFFD7C2B8),
        disabledBg = Color(0xFFCDB7A7), // 【提案】surface-3 暗一档（warm 同名 token 相对步长搬来）
        skeleton = Color(0xFFE5D0C5), // 【提案】surface-2 与 surface-3 之间

        textPlaceholder = Color(0xFF61524A), // 【提案】onSurfaceVariant→outline 间 ~30%
        textDisabled = Color(0xFF74645C), // 【提案】placeholder→outline 间 ~55%

        pressed = Color(0xFF74411E), // 【提案】primary 按 warm 相对步长暗一档
        pressedHeld = Color(0xFF6B3B1B), // 【提案】held 档
    )

    /**
     * 暗色非 M3 token。
     * surface2 = master surfaceVariant（非提案）；其余【提案】——
     * master 暗色的 surfaceVariant 与 outlineVariant **同值**（#52443C），没有第三级，
     * surface3 按亮色表的相对关系提一档。
     */
    val dark = PaletteScheme(
        scheme = darkScheme,
        surface2 = Color(0xFF52443C),
        surface3 = Color(0xFF5F5049), // 【提案】master 暗色无第三级，提一档
        disabledBg = Color(0xFF3D332D), // 【提案】取 master surfaceContainerHighest 作禁用容器底
        skeleton = Color(0xFF2A211B), // 【提案】surface 与 disabledBg 之间

        textPlaceholder = Color(0xFFC1ADA4), // 【提案】outline→onSurfaceVariant 间 ~60%
        textDisabled = Color(0xFFA7958C), // 【提案】outline→placeholder 间 ~25%

        pressed = Color(0xFFE6AC82), // 【提案】primary 按 warm 相对步长暗一档
        pressedHeld = Color(0xFFF6C6A0), // 【提案】held 档
    )

    /** 明暗两档合体。 */
    val tokens = PaletteTokens(light = light, dark = dark)
}
