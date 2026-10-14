package cn.bit101.android.features.theme

import androidx.compose.ui.graphics.Color

/**
 * 《一格 GRID》token 重映射（P1，规范 §0）。
 *
 * 结构不动（val 名 / 顺序 / 亮暗两组），只把色值换成规范值：
 * - `--bg/--surface/--text/--text-2` → background/surface/onSurface/onSurfaceVariant
 * - `--primary` 一族 → primary/onPrimary/primaryContainer/onPrimaryContainer
 * - `--secondary` 松墨绿 → secondary 一族
 * - 银杏金（Accent）没有独立 M3 角色，落 tertiary 一族（文字/图标一律安全档 #8A5A00）
 * - `--surface-2` → surfaceVariant（暖中性）；`--surface-3` → outlineVariant（§2 分割线）
 * - surfaceContainer* 五档按 §0 的四个面色拉成单调梯度（最低=surface，最高=surface-3）
 * - inverse* = Snackbar 三件套（规范 --surface-inv/--text-inv + Action 色）
 *
 * ⚠️ M3 装不下的语义词（surface-2/3、disabled-bg、state 三档、Success/Accent、12 色板）
 *    不在这里塞，见同目录 [GridTokens.kt]（A3-c 混合方案）。
 */

// ── Primary 按压档（规范 §5.2：移动端无 hover ⇒「悬停/按压」= 按压前 80ms 与按压保持） ──

/** §5.2 填充按钮按压 80ms 过渡底 */
val primaryPressedLight = Color(0xFF73401E)
/** §5.2 填充按钮按压保持底 */
val primaryPressedHeldLight = Color(0xFF6A3A1B)
/** §5.2 填充按钮按压档（暗色：往亮处提，暗底上「更暗」看不出按压） */
val primaryPressedDark = Color(0xFFD6A98A)
val primaryPressedHeldDark = Color(0xFFE6C3A8)

// ── 亮色 ──

val primaryLight = Color(0xFF8A4E26)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFF9DECC)
val onPrimaryContainerLight = Color(0xFF5C3216)
val secondaryLight = Color(0xFF1E6B55)
val onSecondaryLight = Color(0xFFFFFFFF)
// 规范未给 secondary 容器色：按 secondary 同族提亮推导（只作图片查看器控件底之类的大面积底）
val secondaryContainerLight = Color(0xFFD5E9DF)
val onSecondaryContainerLight = Color(0xFF0F4A38)
// 银杏金 #E8A317 仅允许填充；文字与 <24px 图标一律 #8A5A00（§0）⇒ tertiary 文字档取安全色
val tertiaryLight = Color(0xFF8A5A00)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFFBEBCC)
val onTertiaryContainerLight = Color(0xFF8A5A00)
val errorLight = Color(0xFFB3261E)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFBE0DC)
val onErrorContainerLight = Color(0xFF8C1D18)
val backgroundLight = Color(0xFFFBF8F4)
val onBackgroundLight = Color(0xFF1E1A16)
val surfaceLight = Color(0xFFFFFFFF)
val onSurfaceLight = Color(0xFF1E1A16)
val surfaceVariantLight = Color(0xFFF4EFE8)
val onSurfaceVariantLight = Color(0xFF5F564E)
val outlineLight = Color(0xFF9C8F82)
val outlineVariantLight = Color(0xFFEAE3DA)
val scrimLight = Color(0x7014100C)
val inverseSurfaceLight = Color(0xFF2E2822)
val inverseOnSurfaceLight = Color(0xFFF7F1EA)
val inversePrimaryLight = Color(0xFFEFB392)
val surfaceDimLight = Color(0xFFEAE3DA)
val surfaceBrightLight = Color(0xFFFFFFFF)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFFBF8F4)
val surfaceContainerLight = Color(0xFFFBF8F4)
val surfaceContainerHighLight = Color(0xFFF4EFE8)
val surfaceContainerHighestLight = Color(0xFFEAE3DA)

// ── 暗色 ──

val primaryDark = Color(0xFFEFB392)
val onPrimaryDark = Color(0xFF3D1F08)
val primaryContainerDark = Color(0xFF4A2A12)
val onPrimaryContainerDark = Color(0xFFEFB392)
val secondaryDark = Color(0xFF86D9C0)
val onSecondaryDark = Color(0xFF06352A)
val secondaryContainerDark = Color(0xFF1F4A3D)
val onSecondaryContainerDark = Color(0xFFB5E8D8)
val tertiaryDark = Color(0xFFF2BE55)
val onTertiaryDark = Color(0xFF3D2A00)
val tertiaryContainerDark = Color(0xFF3A2E12)
val onTertiaryContainerDark = Color(0xFFF2BE55)
val errorDark = Color(0xFFFFB3A7)
val onErrorDark = Color(0xFF5C0E0A)
val errorContainerDark = Color(0xFF571310)
val onErrorContainerDark = Color(0xFFFFB3A7)
val backgroundDark = Color(0xFF16130F)
val onBackgroundDark = Color(0xFFF3EDE6)
val surfaceDark = Color(0xFF1E1A16)
val onSurfaceDark = Color(0xFFF3EDE6)
val surfaceVariantDark = Color(0xFF272219)
val onSurfaceVariantDark = Color(0xFFC4B8AA)
val outlineDark = Color(0xFF7A7069)
val outlineVariantDark = Color(0xFF322C26)
val scrimDark = Color(0x99000000)
val inverseSurfaceDark = Color(0xFFE8DFD4)
val inverseOnSurfaceDark = Color(0xFF1E1A16)
val inversePrimaryDark = Color(0xFF8A4E26)
val surfaceDimDark = Color(0xFF16130F)
val surfaceBrightDark = Color(0xFF332C24)
val surfaceContainerLowestDark = Color(0xFF16130F)
val surfaceContainerLowDark = Color(0xFF16130F)
val surfaceContainerDark = Color(0xFF1E1A16)
val surfaceContainerHighDark = Color(0xFF272219)
val surfaceContainerHighestDark = Color(0xFF332C24)
