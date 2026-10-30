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

    /**
     * 原版统色 12 支：12 支同值（还原 master 课表「所有课一个颜色」的观感）。
     * 族 = [cn.bit101.android.config.setting.base.CourseColorFamily.Legacy]，也是默认族。
     */
    val legacyLight: List<CourseColors> = List(PALETTE_SIZE) {
        CourseColors(Color(0xFFE7ECF3), Color(0xFF3D5573), Color(0xFF5B7BA6))
    }
    val legacyDark: List<CourseColors> = List(PALETTE_SIZE) {
        CourseColors(Color(0xFF2C3542), Color(0xFFC6D3E3), Color(0xFFC6D3E3))
    }

    /** 莫兰迪 12 支（低饱和柔和）。族 = CourseColorFamily.Morandi。 */
    val morandiLight: List<CourseColors> = listOf(
        CourseColors(Color(0xFFD9DCE1), Color(0xFF4A4F58), Color(0xFF8A929E)), // 灰蓝
        CourseColors(Color(0xFFE3D9CF), Color(0xFF5C4A3A), Color(0xFFA08A72)), // 陶土
        CourseColors(Color(0xFFCFDCD3), Color(0xFF3F5245), Color(0xFF7E9A88)), // 灰绿
        CourseColors(Color(0xFFE2D2D3), Color(0xFF5A3D40), Color(0xFFA27578)), // 灰玫
        CourseColors(Color(0xFFD3DFE4), Color(0xFF3A5058), Color(0xFF77929C)), // 灰青
        CourseColors(Color(0xFFDEDBC6), Color(0xFF54503A), Color(0xFF948E6E)), // 灰黄
        CourseColors(Color(0xFFD9D1DE), Color(0xFF4C3D55), Color(0xFF8A7594)), // 灰紫
        CourseColors(Color(0xFFD5DCC9), Color(0xFF44503A), Color(0xFF869472)), // 灰橄榄
        CourseColors(Color(0xFFE0CFC9), Color(0xFF5A3F38), Color(0xFFA17E74)), // 灰砖
        CourseColors(Color(0xFFC9DCDC), Color(0xFF384F4F), Color(0xFF6E9494)), // 灰青绿
        CourseColors(Color(0xFFDCCBDA), Color(0xFF523A4E), Color(0xFF947289)), // 灰梅
        CourseColors(Color(0xFFCFDACB), Color(0xFF3E5040), Color(0xFF779075)), // 灰苔
    )
    val morandiDark: List<CourseColors> = listOf(
        CourseColors(Color(0xFF353A42), Color(0xFFC3CAD6), Color(0xFFC3CAD6)),
        CourseColors(Color(0xFF453B31), Color(0xFFD8C9B6), Color(0xFFD8C9B6)),
        CourseColors(Color(0xFF33403A), Color(0xFFC2D3C6), Color(0xFFC2D3C6)),
        CourseColors(Color(0xFF423234), Color(0xFFD6C0C2), Color(0xFFD6C0C2)),
        CourseColors(Color(0xFF2F3B40), Color(0xFFBFD0D6), Color(0xFFBFD0D6)),
        CourseColors(Color(0xFF3B382B), Color(0xFFD6D2BE), Color(0xFFD6D2BE)),
        CourseColors(Color(0xFF3A3240), Color(0xFFCDBFD4), Color(0xFFCDBFD4)),
        CourseColors(Color(0xFF353D2F), Color(0xFFC8D4BC), Color(0xFFC8D4BC)),
        CourseColors(Color(0xFF40322D), Color(0xFFD8C4BD), Color(0xFFD8C4BD)),
        CourseColors(Color(0xFF2B3838), Color(0xFFBCD2D2), Color(0xFFBCD2D2)),
        CourseColors(Color(0xFF3B2F3A), Color(0xFFD3BFCD), Color(0xFFD3BFCD)),
        CourseColors(Color(0xFF313B30), Color(0xFFC4D2C1), Color(0xFFC4D2C1)),
    )

    /**
     * 北理蓝 12 支 —— 围绕校色 #1B4F9C 的**蓝色系**，族 = CourseColorFamily.Brand。
     *
     * ⚠️ 这套表不能省：早先 `Palettes.tokens` 把 Brand 直接指到 [light]/[dark]，
     * 结果用户在设置里切「经典 12 色 ⇄ 北理蓝 12 色」**看不出任何差别** —— 选项是假的。
     * 这里 11 支落在 200°~250° 蓝色带内（靠色相 + 明度拉开），第 11 号用品牌金
     * （tertiary #7A5A00）做收尾，避免整屏深浅蓝糊成一片。
     */
    val brandLight: List<CourseColors> = listOf(
        CourseColors(Color(0xFFD6E2F5), Color(0xFF10315E), Color(0xFF1B4F9C)), // 0 校色蓝
        CourseColors(Color(0xFFCCE0F0), Color(0xFF0B3350), Color(0xFF1E6FA6)), // 1 青蓝
        CourseColors(Color(0xFFD3DAF0), Color(0xFF141F52), Color(0xFF2C3D9C)), // 2 靛蓝
        CourseColors(Color(0xFFC9E4EC), Color(0xFF073039), Color(0xFF14788A)), // 3 湖蓝
        CourseColors(Color(0xFFE1E6F7), Color(0xFF1B2255), Color(0xFF3E4FA0)), // 4 雾蓝
        CourseColors(Color(0xFFBFD3EA), Color(0xFF0A2740), Color(0xFF2A5C90)), // 5 钢蓝
        CourseColors(Color(0xFFD8D3EE), Color(0xFF231C55), Color(0xFF5A45A0)), // 6 蓝紫
        CourseColors(Color(0xFFCBE8E6), Color(0xFF06322F), Color(0xFF126F69)), // 7 蓝绿
        CourseColors(Color(0xFFE4EAF2), Color(0xFF1B2530), Color(0xFF46586E)), // 8 灰蓝
        CourseColors(Color(0xFFC7D9F0), Color(0xFF0C2A4E), Color(0xFF245FA8)), // 9 天蓝
        CourseColors(Color(0xFFDDE7F4), Color(0xFF122A45), Color(0xFF356FA8)), // 10 浅钢蓝
        CourseColors(Color(0xFFEDE3C8), Color(0xFF3A2E08), Color(0xFF7A5A00)), // 11 品牌金
    )
    val brandDark: List<CourseColors> = listOf(
        CourseColors(Color(0xFF16305A), Color(0xFFC9D9F2), Color(0xFFC9D9F2)),
        CourseColors(Color(0xFF123A52), Color(0xFFC2DDEE), Color(0xFFC2DDEE)),
        CourseColors(Color(0xFF1B2358), Color(0xFFCCD3F0), Color(0xFFCCD3F0)),
        CourseColors(Color(0xFF0C3540), Color(0xFFBCE3EC), Color(0xFFBCE3EC)),
        CourseColors(Color(0xFF232A62), Color(0xFFD5DBF2), Color(0xFFD5DBF2)),
        CourseColors(Color(0xFF123048), Color(0xFFC0D6E8), Color(0xFFC0D6E8)),
        CourseColors(Color(0xFF2A2662), Color(0xFFD6D0F0), Color(0xFFD6D0F0)),
        CourseColors(Color(0xFF08383A), Color(0xFFBCE6E3), Color(0xFFBCE6E3)),
        CourseColors(Color(0xFF1E2836), Color(0xFFC8D2DE), Color(0xFFC8D2DE)),
        CourseColors(Color(0xFF10314F), Color(0xFFC0D8EC), Color(0xFFC0D8EC)),
        CourseColors(Color(0xFF182C46), Color(0xFFC3D4E6), Color(0xFFC3D4E6)),
        CourseColors(Color(0xFF3A2E0A), Color(0xFFEBDDBA), Color(0xFFEBDDBA)),
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
