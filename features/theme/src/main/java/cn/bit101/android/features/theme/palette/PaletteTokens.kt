package cn.bit101.android.features.theme.palette

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 一套 palette 里**随 palette 变**的那部分 token（初稿 §5.1 / §5.2）。
 *
 * 分工（§5.2 表）：
 * - [scheme]：M3 角色全表（primary/secondary/tertiary 族、background/surface 族、
 *   surfaceContainer 五档、outline/scrim/inverse 族…）。
 *   ⚠️ **error 族不随 palette**（§5.2 共享）：三张表引用同一组共享 error 值
 *   （定义在 `cn.bit101.android.features.theme` 的 GridTokens.kt）——
 *   「同一语义跨 Tab 同色」的既有规则，error 是语义色不是品牌色。
 * - 面色族：surface2 / surface3 / disabledBg / skeleton（M3 装不下的面）
 * - 文字档：textPlaceholder（三级可读）/ textDisabled（仅禁用态）
 * - 按压档：pressed / pressedHeld（规范 §5.2 两段式；每 palette 手写，
 *   **不由 primary 派生**——实测 blend 复现不出设计值，见初稿 §5.3）
 *
 * **不在这里**（跨 palette 共享，见 GridTokens.kt 的 SharedGridTokens）：
 * numLg/Md/Sm、stateCalm/Attention/Urgent、success/accent/info 族。
 */
@Immutable
data class PaletteScheme(
    val scheme: ColorScheme,
    val surface2: Color,
    val surface3: Color,
    val disabledBg: Color,
    val skeleton: Color,
    val textPlaceholder: Color,
    val textDisabled: Color,
    val pressed: Color,
    val pressedHeld: Color,
)

/** 一套 palette 的明暗两档。 */
@Immutable
data class PaletteTokens(
    val light: PaletteScheme,
    val dark: PaletteScheme,
)
