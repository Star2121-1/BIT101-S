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
 * 北理工配色 —— **占位表，全部未验证**。
 *
 * ⚠️⚠️ **无官方 VI 手册可引，禁止在任何交付物（代码注释 / 提交说明 / 对用户文案）中
 * 表述为「北理工官方色」**（初稿 §5.4）。[cn.bit101.android.config.setting.base.AppPalette.Brand]
 * 带 `experimental = true`，UI 层必须标「实验性」。
 *
 * 取值方法（§5.4）：沿用原版配色的 tonal 关系结构（Material Theme Builder 输出、
 * 角色间对比关系已验证），只把色相族从暖棕替换为深蓝 ⇒ 官方值到位后**逐格替换、
 * 结构不动**。起点色（§5.4 给定）：亮 primary #1B4F9C / background #F7F9FC /
 * secondary #4A5B75 / tertiary #7A5A00；暗 primary #A8C6F5 / background #101418。
 * 明暗全套由实现补齐；error 族按 §5.2 共享，不占位。
 *
 * 对比度验收（WCAG 2.1，正文/背景 ≥4.5:1，逐格跑过，结果见提交说明）：
 * 关键对 secondaryContainer/onSecondaryContainer 亮 8.98:1 / 暗 6.33:1；
 * 全部正文·容器对达标；唯一低于 4.5 的是 textDisabled/disabledBg **禁用档**
 * （亮 3.04:1 / 暗 4.37:1）——禁用态 WCAG 豁免，且 warm 现网同档更低（2.86/3.67），
 * 三套保持同一档位行为，不单独拉高。
 *
 * 面色族 / 文字档 / 按压档同为占位提案（非官方、待设计确认）：取法 = warm 同名
 * token 的相对步长搬到冷白中性梯上。
 */
internal object BrandColor {

    // ── 亮色 M3 角色（占位）──

    private val primaryLight = Color(0xFF1B4F9C)
    private val onPrimaryLight = Color(0xFFFFFFFF)
    private val primaryContainerLight = Color(0xFFD6E2F5)
    private val onPrimaryContainerLight = Color(0xFF10315E)
    private val secondaryLight = Color(0xFF4A5B75)
    private val onSecondaryLight = Color(0xFFFFFFFF)
    private val secondaryContainerLight = Color(0xFFDEE4EE)
    private val onSecondaryContainerLight = Color(0xFF2E3A4E)
    private val tertiaryLight = Color(0xFF7A5A00)
    private val onTertiaryLight = Color(0xFFFFFFFF)
    private val tertiaryContainerLight = Color(0xFFF5E7C6)
    private val onTertiaryContainerLight = Color(0xFF4A3800)
    private val backgroundLight = Color(0xFFF7F9FC)
    private val onBackgroundLight = Color(0xFF191C21)
    private val surfaceLight = Color(0xFFFFFFFF)
    private val onSurfaceLight = Color(0xFF191C21)
    private val surfaceVariantLight = Color(0xFFE4E9F1)
    private val onSurfaceVariantLight = Color(0xFF454C58)
    private val outlineLight = Color(0xFF757D8B)
    private val outlineVariantLight = Color(0xFFC6CEDA)
    private val scrimLight = Color(0x99000000)
    private val inverseSurfaceLight = Color(0xFF2C3138)
    private val inverseOnSurfaceLight = Color(0xFFF0F3F8)
    private val inversePrimaryLight = Color(0xFFA8C6F5)
    private val surfaceDimLight = Color(0xFFDDE3EC)
    private val surfaceBrightLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowLight = Color(0xFFF7F9FC)
    private val surfaceContainerLight = Color(0xFFF7F9FC)
    private val surfaceContainerHighLight = Color(0xFFE4E9F1)
    private val surfaceContainerHighestLight = Color(0xFFDDE3EC)

    // ── 暗色 M3 角色（占位）──

    private val primaryDark = Color(0xFFA8C6F5)
    private val onPrimaryDark = Color(0xFF10315E)
    private val primaryContainerDark = Color(0xFF2C4E7C)
    private val onPrimaryContainerDark = Color(0xFFD6E2F5)
    private val secondaryDark = Color(0xFFA9B7CC)
    private val onSecondaryDark = Color(0xFF25303F)
    private val secondaryContainerDark = Color(0xFF3A4657)
    private val onSecondaryContainerDark = Color(0xFFC7D3E5)
    private val tertiaryDark = Color(0xFFE3BC63)
    private val onTertiaryDark = Color(0xFF3D2E00)
    private val tertiaryContainerDark = Color(0xFF4F3D14)
    private val onTertiaryContainerDark = Color(0xFFE3BC63)
    private val backgroundDark = Color(0xFF101418)
    private val onBackgroundDark = Color(0xFFE2E6EC)
    private val surfaceDark = Color(0xFF161B21)
    private val onSurfaceDark = Color(0xFFE2E6EC)
    private val surfaceVariantDark = Color(0xFF262D37)
    private val onSurfaceVariantDark = Color(0xFFBEC5D0)
    private val outlineDark = Color(0xFF8B93A0)
    private val outlineVariantDark = Color(0xFF39414C)
    private val scrimDark = Color(0x99000000)
    private val inverseSurfaceDark = Color(0xFFE2E6EC)
    private val inverseOnSurfaceDark = Color(0xFF161B21)
    private val inversePrimaryDark = Color(0xFF1B4F9C)
    private val surfaceDimDark = Color(0xFF101418)
    private val surfaceBrightDark = Color(0xFF333A44)
    private val surfaceContainerLowestDark = Color(0xFF0B0E12)
    private val surfaceContainerLowDark = Color(0xFF12161C)
    private val surfaceContainerDark = Color(0xFF161B21)
    private val surfaceContainerHighDark = Color(0xFF262D37)
    private val surfaceContainerHighestDark = Color(0xFF313842)

    /** 亮色 M3 角色全表（占位；error 族用共享值）。 */
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

    /** 暗色 M3 角色全表（占位；error 族用共享值）。 */
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

    /** 亮色面色族 / 文字档 / 按压档（占位提案）。 */
    val light = PaletteScheme(
        scheme = lightScheme,
        surface2 = Color(0xFFE4E9F1),
        surface3 = Color(0xFFC6CEDA),
        disabledBg = Color(0xFFD3DAE6),
        skeleton = Color(0xFFDCE3EE),

        textPlaceholder = Color(0xFF5A626E),
        textDisabled = Color(0xFF737B88),

        pressed = Color(0xFF173F7E),
        pressedHeld = Color(0xFF123466),
    )

    /** 暗色面色族 / 文字档 / 按压档（占位提案）。 */
    val dark = PaletteScheme(
        scheme = darkScheme,
        surface2 = Color(0xFF262D37),
        surface3 = Color(0xFF39414C),
        disabledBg = Color(0xFF1F252D),
        skeleton = Color(0xFF1A2027),

        textPlaceholder = Color(0xFFA6AEBA),
        textDisabled = Color(0xFF818995),

        pressed = Color(0xFFC2D8F9),
        pressedHeld = Color(0xFFD6E5FC),
    )

    /** 明暗两档合体。 */
    val tokens = PaletteTokens(light = light, dark = dark)
}
