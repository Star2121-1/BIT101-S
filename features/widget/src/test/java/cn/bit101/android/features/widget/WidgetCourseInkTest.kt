package cn.bit101.android.features.widget

import cn.bit101.android.features.theme.CoursePalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件课程行正文色的「白卡契约」单测（WIDGET-001 回归钉）。
 *
 * 组件卡片 chrome 是固定亮色（`widget_surface = #FFFBFE`，模块无 values-night），
 * 所以课程行正文**必须**取 12 色板的**亮色** ink；取暗色板 ink（浅色）画在白卡上
 * 就是「白底白字」（实测 1.22~1.48:1）。
 *
 * `WidgetViews.bodyColor` 带 Context 无法直接 JVM 单测，但其唯一取值入口
 * [CoursePalette.inkArgb] 是纯函数 —— 本测试直接钉这份判据：
 * ① 亮色板 12 支 ink 对白卡全部 ≥ 4.5:1（WCAG AA）；
 * ② 暗色板 12 支 ink 对白卡全部 < 2:1（固化「暗色板白卡不可读」的事实，
 *    防未来某天把 `isDark` 跟随悄悄接回来复现本 bug）。
 */
class WidgetCourseInkTest {

    /** `features/widget/res/values/colors.xml` 的 `widget_surface`。 */
    private val widgetSurface = 0xFFFFFBFE.toInt()

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
