package cn.bit101.android.features.widget

import cn.bit101.android.features.theme.CoursePalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件配色对比度契约单测（WIDGET-001 回归钉 + 候选 A 暗色契约）。
 *
 * 组件有两个渲染面，两份色板分别配套（§8.2「亮底配亮 ink、暗底配暗 ink」）：
 * - 亮态：卡片 `widget_surface = #FFFBFE`（values/colors.xml）配 12 色板**亮** ink；
 * - 暗态：卡片 `#1C1B1F`（values-night/colors.xml，候选 A / P5-E）配 12 色板**暗** ink。
 * 一致性由 `WidgetViews.bodyColor` 的 isDark（= 系统 uiMode）与宿主对 values-night
 * 的解析同源保证。
 *
 * `WidgetViews.bodyColor` 带 Context 无法直接 JVM 单测，但其唯一取值入口
 * [CoursePalette.inkArgb] 是纯函数 —— 本测试直接钉这份判据。
 * ⚠️ 常量与 colors.xml / values-night/colors.xml 同值（注释即出处），
 * 改色值必须同步本文件。
 *
 * ① 亮 ink × 白卡 ≥ 4.5:1（现状 6.26~13.95，防「亮态被换暗板」）；
 * ② 暗 ink × 白卡 < 2:1（固化「暗板画白卡 = 白底白字」的事实，WIDGET-001 的坑）；
 * ③ 暗 ink × 暗卡 ≥ 4.5:1（A 的主契约，实测 11.28~13.x）；
 * ④ 亮 ink × 暗卡 < 3:1（对称钉：暗卡绝不能配亮板 ink，实测最大 2.67）；
 * ⑤ chrome 文字色对各自底色 ≥ 4.5:1。
 */
class WidgetCourseInkTest {

    /** `features/widget/res/values/colors.xml` 的 `widget_surface`。 */
    private val widgetSurface = 0xFFFFFBFE.toInt()

    /** `features/widget/res/values-night/colors.xml` 的 `widget_surface`。 */
    private val widgetSurfaceDark = 0xFF1C1B1F.toInt()

    @Test
    fun `亮色板 12 支 ink 对组件白卡均达 WCAG AA 4_5_1`() {
        for (i in 0 until 12) {
            val ink = CoursePalette.inkArgb(i, isDark = false)
            val ratio = contrastRatio(ink, widgetSurface)
            assertTrue(
                "色号 $i ink=${hex(ink)} 对白卡对比度 $ratio < 4.5",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `暗色板 12 支 ink 对组件白卡全部不可读 - 锁定亮色板判据`() {
        for (i in 0 until 12) {
            val ink = CoursePalette.inkArgb(i, isDark = true)
            val ratio = contrastRatio(ink, widgetSurface)
            assertTrue(
                "色号 $i ink=${hex(ink)} 对白卡对比度 $ratio 应 < 2（防 isDark 跟随回归）",
                ratio < 2.0,
            )
        }
    }

    @Test
    fun `暗色板 12 支 ink 对组件暗卡均达 WCAG AA 4_5_1`() {
        for (i in 0 until 12) {
            val ink = CoursePalette.inkArgb(i, isDark = true)
            val ratio = contrastRatio(ink, widgetSurfaceDark)
            assertTrue(
                "色号 $i ink=${hex(ink)} 对暗卡对比度 $ratio < 4.5（候选 A 契约）",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `亮色板 12 支 ink 对组件暗卡全部不可读 - 对称钉`() {
        for (i in 0 until 12) {
            val ink = CoursePalette.inkArgb(i, isDark = false)
            val ratio = contrastRatio(ink, widgetSurfaceDark)
            assertTrue(
                "色号 $i ink=${hex(ink)} 对暗卡对比度 $ratio 应 < 3（防暗卡配亮板回归）",
                ratio < 3.0,
            )
        }
    }

    @Test
    fun `chrome 文字色对卡片底的对比度契约`() {
        // 亮态（values/colors.xml）——a2 真机验收后把两状态色纳入 AA
        // （原 #1E8E3E=4.10 / #E8710A=3.01 不达标，调整为 #1A7F37 / #B45309）。
        assertRatio(0xFF1C1B1F.toInt(), widgetSurface, "亮态正文")
        assertRatio(0xFF49454F.toInt(), widgetSurface, "亮态次要文字/未选中页签")
        assertRatio(0xFF6750A4.toInt(), widgetSurface, "亮态强调色")
        assertRatio(0xFFD3302F.toInt(), widgetSurface, "亮态紧急")
        assertRatio(0xFF1A7F37.toInt(), widgetSurface, "亮态正在上课")
        assertRatio(0xFFB45309.toInt(), widgetSurface, "亮态即将上课")
        assertRatio(0xFF21005D.toInt(), 0xFFEADDFF.toInt(), "亮态选中页签文字×选中底")

        // 暗态（values-night/colors.xml）——A 新增，全量 ≥ 4.5。
        assertRatio(0xFFE6E1E5.toInt(), widgetSurfaceDark, "暗态正文")
        assertRatio(0xFFCAC4D0.toInt(), widgetSurfaceDark, "暗态次要文字/未选中页签")
        assertRatio(0xFFD0BCFF.toInt(), widgetSurfaceDark, "暗态强调色")
        assertRatio(0xFFFFB4AB.toInt(), widgetSurfaceDark, "暗态紧急")
        assertRatio(0xFF6DD58C.toInt(), widgetSurfaceDark, "暗态正在上课")
        assertRatio(0xFFFFB86B.toInt(), widgetSurfaceDark, "暗态即将上课")
        assertRatio(0xFFEADDFF.toInt(), 0xFF4F378B.toInt(), "暗态选中页签文字×选中底")
    }

    private fun assertRatio(fg: Int, bg: Int, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "$label：${hex(fg)}×${hex(bg)} 对比度 ${"%.2f".format(ratio)} < 4.5",
            ratio >= 4.5,
        )
    }

    // ---------------------------------------------------------------- WCAG 2.1 对比度

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
}
