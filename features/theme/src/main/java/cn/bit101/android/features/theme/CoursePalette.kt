package cn.bit101.android.features.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 课程 12 色板（规范 §8.2 权威表）—— **色号 → 颜色**的唯一出处。
 *
 * 与 [GridTokens] 的分工：
 * - `GridTokens.course(i)`：Compose 里用（走 `LocalGridTokens`，自动拿对亮/暗实例）；
 * - [CoursePalette]：**Compose 之外**用 —— 桌面组件（RemoteViews）在宿主进程渲染，
 *   拿不到 CompositionLocal，只能吃 `Int` 色值。
 *
 * ⚠️ 两条入口**必须同源**：都读下面这两份表。`GridTokens` 的亮/暗实例直接引用
 * [light] / [dark]，不存在第二份色值（S2：色值只许住在 token 层）。
 *
 * ⚠️ 色号本身由 `cn.bit101.android.data.schedule.CourseColorLogic.of(课名)` 算出
 * （`:data`，全仓唯一哈希实现）—— 本文件**不含哈希**，只回答「色号 i 显示成什么」。
 * 传进来的 index 越界按 mod 12 收拢，传 -1 也不崩。
 */
object CoursePalette {

    /** 亮色 12 支（fill / ink / mark，§8.2 表列序）。 */
    val light: List<CourseColors> = listOf(
        CourseColors(Color(0xFF94A4E6), Color(0xFF112369), Color(0xFF2143CA)), // 0 靛蓝
        CourseColors(Color(0xFFF4DBC2), Color(0xFF734211), Color(0xFFE18A33)), // 1 赭橘
        CourseColors(Color(0xFFB6ECCF), Color(0xFF17633B), Color(0xFF66D69A)), // 2 松绿
        CourseColors(Color(0xFFF1C5CF), Color(0xFF71142A), Color(0xFFDE6884)), // 3 玫红
        CourseColors(Color(0xFFCBE6F6), Color(0xFF0F5076), Color(0xFF7EC0E7)), // 4 天蓝
        CourseColors(Color(0xFFEBD898), Color(0xFF6A5510), Color(0xFFE8CF7D)), // 5 芥黄
        CourseColors(Color(0xFFDBBFED), Color(0xFF481769), Color(0xFFA151D6)), // 6 紫罗兰
        CourseColors(Color(0xFFDDF2CA), Color(0xFF3D6A16), Color(0xFF80CE3B)), // 7 草绿
        CourseColors(Color(0xFFEB9E98), Color(0xFF6F1711), Color(0xFFA92319)), // 8 朱红
        CourseColors(Color(0xFF90DDE0), Color(0xFF135B5D), Color(0xFF25A3A7)), // 9 青碧
        CourseColors(Color(0xFFE090C5), Color(0xFF63174A), Color(0xFFA7257C)), // 10 品紫
        CourseColors(Color(0xFF92DDA1), Color(0xFF145722), Color(0xFF207932)), // 11 竹青
    )

    /**
     * 暗色 12 支。mark 位按 §8.2 放 ink-d（暗态独立图形不用 mark —— 对比不足）。
     */
    val dark: List<CourseColors> = listOf(
        CourseColors(Color(0xFF2F3D74), Color(0xFFC9D1ED), Color(0xFFC9D1ED)), // 0 靛蓝
        CourseColors(Color(0xFF6D4C2C), Color(0xFFEFDBC8), Color(0xFFEFDBC8)), // 1 赭橘
        CourseColors(Color(0xFF31684B), Color(0xFFCAECDA), Color(0xFFCAECDA)), // 2 松绿
        CourseColors(Color(0xFF491D27), Color(0xFFEEC9D1), Color(0xFFEEC9D1)), // 3 玫红
        CourseColors(Color(0xFF275068), Color(0xFFC9E0EE), Color(0xFFC9E0EE)), // 4 天蓝
        CourseColors(Color(0xFF504521), Color(0xFFEFE6C8), Color(0xFFEFE6C8)), // 5 芥黄
        CourseColors(Color(0xFF422357), Color(0xFFE1CEEE), Color(0xFFE1CEEE)), // 6 紫罗兰
        CourseColors(Color(0xFF46632C), Color(0xFFDAECCA), Color(0xFFDAECCA)), // 7 草绿
        CourseColors(Color(0xFF682B27), Color(0xFFEECBC9), Color(0xFFEECBC9)), // 8 朱红
        CourseColors(Color(0xFF265354), Color(0xFFCAEBEC), Color(0xFFCAEBEC)), // 9 青碧
        CourseColors(Color(0xFF6D2C57), Color(0xFFEDC9E1), Color(0xFFEDC9E1)), // 10 品紫
        CourseColors(Color(0xFF234D2B), Color(0xFFC7EACE), Color(0xFFC7EACE)), // 11 竹青
    )

    /** 按色号取一套课程色。 */
    fun colors(index: Int, isDark: Boolean): CourseColors =
        (if (isDark) dark else light)[Math.floorMod(index, PALETTE_SIZE)]

    /** 块底色（ARGB，给 RemoteViews `setInt(..., "setBackgroundColor", ...)` 用）。 */
    fun fillArgb(index: Int, isDark: Boolean): Int = colors(index, isDark).fill.toArgb()

    /** 块内文字 / 左竖条色（ARGB）。竖条一律用 ink，**不得用 mark**（§8.2）。 */
    fun inkArgb(index: Int, isDark: Boolean): Int = colors(index, isDark).ink.toArgb()

    private const val PALETTE_SIZE = 12
}
