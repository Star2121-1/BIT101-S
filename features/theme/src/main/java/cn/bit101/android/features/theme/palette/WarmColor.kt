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
 * 清新青绿配色（展示名，P5-C 起；**默认 palette**，alpha 用户观感零变化的那条路）。
 *
 * ⚠️ 旧展示名「一格 GRID 暖棕配色」名不副实已废：初稿 §119 意图暖棕，但本表实际是
 * P1 分支落地事实（surface 纯白 #FFFFFF + secondaryContainer 青绿 #D5E9DF），观感清新。
 * 用户 P5-C 拍板按实际观感改名；datastore value `warm` 不变。
 *
 * 现 Color.kt 整体搬入 + `object` 包裹（撞名解法：master 与现 Color.kt 顶层 val
 * 同名 70/70，同包两个文件同名顶层 val = 重声明编译错，三张表统一 object 形态）。
 *
 * ⚠️ error 族按 §5.2 **跨 palette 共享**，不随本表搬入——引用 GridTokens.kt 的
 * 共享 error 值（import 见上）。其余角色与原 Color.kt 逐一对应，色值不动。
 *
 * 按压档 4 值：pressed（按压 80ms 档）/ pressedHeld（保持档） × 明暗。
 * 原 `primaryPressedLight/Dark` 在旧 GridButton 里是死字面量（运行时只用 Held 对），
 * 现收进 [PaletteScheme] 成为结构化 token（编译期要求每 palette 填全）。
 */
internal object WarmColor {

    // ── 亮色 M3 角色（原 Color.kt 亮色组，值不动）──

    private val primaryLight = Color(0xFF8A4E26)
    private val onPrimaryLight = Color(0xFFFFFFFF)
    private val primaryContainerLight = Color(0xFFF9DECC)
    private val onPrimaryContainerLight = Color(0xFF5C3216)
    private val secondaryLight = Color(0xFF1E6B55)
    private val onSecondaryLight = Color(0xFFFFFFFF)
    // 规范未给 secondary 容器色：按 secondary 同族提亮推导（只作图片查看器控件底之类的大面积底）
    private val secondaryContainerLight = Color(0xFFD5E9DF)
    private val onSecondaryContainerLight = Color(0xFF0F4A38)
    // 银杏金 #E8A317 仅允许填充；文字与 <24px 图标一律 #8A5A00（§0）⇒ tertiary 文字档取安全色
    private val tertiaryLight = Color(0xFF8A5A00)
    private val onTertiaryLight = Color(0xFFFFFFFF)
    private val tertiaryContainerLight = Color(0xFFFBEBCC)
    private val onTertiaryContainerLight = Color(0xFF8A5A00)
    private val backgroundLight = Color(0xFFFBF8F4)
    private val onBackgroundLight = Color(0xFF1E1A16)
    private val surfaceLight = Color(0xFFFFFFFF)
    private val onSurfaceLight = Color(0xFF1E1A16)
    private val surfaceVariantLight = Color(0xFFF4EFE8)
    private val onSurfaceVariantLight = Color(0xFF5F564E)
    private val outlineLight = Color(0xFF9C8F82)
    private val outlineVariantLight = Color(0xFFEAE3DA)
    private val scrimLight = Color(0x7014100C)
    private val inverseSurfaceLight = Color(0xFF2E2822)
    private val inverseOnSurfaceLight = Color(0xFFF7F1EA)
    private val inversePrimaryLight = Color(0xFFEFB392)
    private val surfaceDimLight = Color(0xFFEAE3DA)
    private val surfaceBrightLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowLight = Color(0xFFFBF8F4)
    private val surfaceContainerLight = Color(0xFFFBF8F4)
    private val surfaceContainerHighLight = Color(0xFFF4EFE8)
    private val surfaceContainerHighestLight = Color(0xFFEAE3DA)

    // ── 暗色 M3 角色（原 Color.kt 暗色组，值不动）──

    private val primaryDark = Color(0xFFEFB392)
    private val onPrimaryDark = Color(0xFF3D1F08)
    private val primaryContainerDark = Color(0xFF4A2A12)
    private val onPrimaryContainerDark = Color(0xFFEFB392)
    private val secondaryDark = Color(0xFF86D9C0)
    private val onSecondaryDark = Color(0xFF06352A)
    private val secondaryContainerDark = Color(0xFF1F4A3D)
    private val onSecondaryContainerDark = Color(0xFFB5E8D8)
    private val tertiaryDark = Color(0xFFF2BE55)
    private val onTertiaryDark = Color(0xFF3D2A00)
    private val tertiaryContainerDark = Color(0xFF3A2E12)
    private val onTertiaryContainerDark = Color(0xFFF2BE55)
    private val backgroundDark = Color(0xFF16130F)
    private val onBackgroundDark = Color(0xFFF3EDE6)
    private val surfaceDark = Color(0xFF1E1A16)
    private val onSurfaceDark = Color(0xFFF3EDE6)
    private val surfaceVariantDark = Color(0xFF272219)
    private val onSurfaceVariantDark = Color(0xFFC4B8AA)
    private val outlineDark = Color(0xFF7A7069)
    private val outlineVariantDark = Color(0xFF322C26)
    private val scrimDark = Color(0x99000000)
    private val inverseSurfaceDark = Color(0xFFE8DFD4)
    private val inverseOnSurfaceDark = Color(0xFF1E1A16)
    private val inversePrimaryDark = Color(0xFF8A4E26)
    private val surfaceDimDark = Color(0xFF16130F)
    private val surfaceBrightDark = Color(0xFF332C24)
    private val surfaceContainerLowestDark = Color(0xFF16130F)
    private val surfaceContainerLowDark = Color(0xFF16130F)
    private val surfaceContainerDark = Color(0xFF1E1A16)
    private val surfaceContainerHighDark = Color(0xFF272219)
    private val surfaceContainerHighestDark = Color(0xFF332C24)

    /** 亮色 M3 角色全表（error 族用共享值）。 */
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

    /** 暗色 M3 角色全表（error 族用共享值）。 */
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

    /** 面色族 + 文字档 + 按压档（亮）。surface2/surface3 与 scheme 的 surfaceVariant/outlineVariant 同值（--surface-2/3 → 该二角色的约定）。 */
    val light = PaletteScheme(
        scheme = lightScheme,
        surface2 = Color(0xFFF4EFE8),
        surface3 = Color(0xFFEAE3DA),
        disabledBg = Color(0xFFE3DBD1),
        skeleton = Color(0xFFE7E0D7),

        textPlaceholder = Color(0xFF726759),
        textDisabled = Color(0xFF8A7F72),

        pressed = Color(0xFF73401E),
        pressedHeld = Color(0xFF6A3A1B),
    )

    /** 面色族 + 文字档 + 按压档（暗）。 */
    val dark = PaletteScheme(
        scheme = darkScheme,
        surface2 = Color(0xFF272219),
        surface3 = Color(0xFF332C24),
        disabledBg = Color(0xFF2A251F),
        skeleton = Color(0xFF231F1A),

        // 规范未给暗色占位符档：按亮色 #726759 同族提亮推导（对 surface ≈6:1，仍属「三级可读」）
        textPlaceholder = Color(0xFFA79A8C),
        textDisabled = Color(0xFF857B72),

        pressed = Color(0xFFD6A98A),
        pressedHeld = Color(0xFFE6C3A8),
    )

    /** 明暗两档合体。 */
    val tokens = PaletteTokens(light = light, dark = dark)
}
