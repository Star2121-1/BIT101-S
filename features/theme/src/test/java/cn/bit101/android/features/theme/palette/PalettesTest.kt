package cn.bit101.android.features.theme.palette

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.CourseColorFamily
import cn.bit101.android.features.theme.GridTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [Palettes] 分派点的契约测试（P5-B）。
 *
 * 固化五条，缺一条都会在未来某次改动里静默坏掉：
 * ① 三 palette × 明暗 = 6 组合都能取到实表（不抛、关键角色非 Unspecified）；
 * ② **共享字段跨 palette 相等**——「同一语义跨 Tab 同色」既有规则的守卫
 *    （有人给某个 palette「定制」语义色时这里必须红）；
 * ③ 随 palette 变的字段确实两两不同——否则「三套配色」是复制粘贴的假象；
 * ④ 缺档 **fail-fast**：查不到登记项直接抛，消息含 palette 名与明暗
 *    （绝不静默回退——「选了没效果」会变幽灵 bug）；
 * ⑤ Classic 与 Warm 的 background 必须不同——证明 master 回迁表真的生效。
 */
class PalettesTest {

    private fun tokens(palette: AppPalette, isDark: Boolean): GridTokens =
        Palettes.tokens(palette, isDark, CourseColorFamily.Auto)

    // ── ① 6 组合可取 ──────────────────────────────────────────────────

    @Test
    fun 三palette乘明暗_六组合都能取到实表() {
        for (palette in AppPalette.entries) {
            for (isDark in listOf(false, true)) {
                val scheme = Palettes.scheme(palette, isDark)
                val tk = tokens(palette, isDark)

                // M3 关键角色不能是 Unspecified（空表 / 漏填的典型症状）
                assertNotEquals(
                    "scheme.primary 未填：palette=$palette isDark=$isDark",
                    Color.Unspecified,
                    scheme.primary,
                )
                assertNotEquals("scheme.background 未填：$palette/$isDark", Color.Unspecified, scheme.background)
                assertNotEquals("scheme.onSurface 未填：$palette/$isDark", Color.Unspecified, scheme.onSurface)
                assertNotEquals(
                    "scheme.secondaryContainer 未填：$palette/$isDark",
                    Color.Unspecified,
                    scheme.secondaryContainer,
                )
                assertNotEquals(
                    "scheme.onSecondaryContainer 未填：$palette/$isDark",
                    Color.Unspecified,
                    scheme.onSecondaryContainer,
                )
                assertNotEquals("scheme.error 未填：$palette/$isDark", Color.Unspecified, scheme.error)

                // 随 palette 的 tokens 字段同样要有值
                assertNotEquals("tokens.surface2 未填：$palette/$isDark", Color.Unspecified, tk.surface2)
                assertNotEquals("tokens.pressedHeld 未填：$palette/$isDark", Color.Unspecified, tk.pressedHeld)

                // 课程色板本轮占位返回现有 CoursePalette（12 色）
                assertEquals("coursePalette 应为 12 色：$palette/$isDark", 12, tk.coursePalette.size)
            }
        }
    }

    // ── ② 共享字段跨 palette 相等 ──────────────────────────────────────

    @Test
    fun 共享字段_跨palette相等_亮暗两档分别验() {
        for (isDark in listOf(false, true)) {
            val classic = tokens(AppPalette.Classic, isDark)
            val warm = tokens(AppPalette.Warm, isDark)
            val brand = tokens(AppPalette.Brand, isDark)

            // success 族
            assertEquals("success：$isDark", warm.success, classic.success)
            assertEquals("success：$isDark", warm.success, brand.success)
            assertEquals("successContainer：$isDark", warm.successContainer, classic.successContainer)
            assertEquals("successContainer：$isDark", warm.successContainer, brand.successContainer)
            assertEquals("onSuccessContainer：$isDark", warm.onSuccessContainer, classic.onSuccessContainer)
            assertEquals("onSuccessContainer：$isDark", warm.onSuccessContainer, brand.onSuccessContainer)

            // accent 族
            assertEquals("accent：$isDark", warm.accent, classic.accent)
            assertEquals("accent：$isDark", warm.accent, brand.accent)
            assertEquals("accentText：$isDark", warm.accentText, classic.accentText)
            assertEquals("accentText：$isDark", warm.accentText, brand.accentText)
            assertEquals("accentContainer：$isDark", warm.accentContainer, classic.accentContainer)
            assertEquals("accentContainer：$isDark", warm.accentContainer, brand.accentContainer)
            assertEquals("onAccentContainer：$isDark", warm.onAccentContainer, classic.onAccentContainer)
            assertEquals("onAccentContainer：$isDark", warm.onAccentContainer, brand.onAccentContainer)

            // info 族
            assertEquals("infoContainer：$isDark", warm.infoContainer, classic.infoContainer)
            assertEquals("infoContainer：$isDark", warm.infoContainer, brand.infoContainer)
            assertEquals("onInfoContainer：$isDark", warm.onInfoContainer, classic.onInfoContainer)
            assertEquals("onInfoContainer：$isDark", warm.onInfoContainer, brand.onInfoContainer)

            // state 三档
            assertEquals("stateCalm：$isDark", warm.stateCalm, classic.stateCalm)
            assertEquals("stateCalm：$isDark", warm.stateCalm, brand.stateCalm)
            assertEquals("stateAttention：$isDark", warm.stateAttention, classic.stateAttention)
            assertEquals("stateAttention：$isDark", warm.stateAttention, brand.stateAttention)
            assertEquals("stateUrgent：$isDark", warm.stateUrgent, classic.stateUrgent)
            assertEquals("stateUrgent：$isDark", warm.stateUrgent, brand.stateUrgent)

            // num 三档（TextStyle 整体相等：字号/行高/字重/tnum 全在内）
            assertEquals("numLg：$isDark", warm.numLg, classic.numLg)
            assertEquals("numLg：$isDark", warm.numLg, brand.numLg)
            assertEquals("numMd：$isDark", warm.numMd, classic.numMd)
            assertEquals("numMd：$isDark", warm.numMd, brand.numMd)
            assertEquals("numSm：$isDark", warm.numSm, classic.numSm)
            assertEquals("numSm：$isDark", warm.numSm, brand.numSm)
        }
    }

    // ── ③ 随 palette 变的字段确实不同 ─────────────────────────────────

    @Test
    fun 随palette变的字段_三套两两不同() {
        for (isDark in listOf(false, true)) {
            val primaries = AppPalette.entries.map { Palettes.scheme(it, isDark).primary }
            assertNotEquals("primary 亮暗=$isDark：Classic 与 Warm 相同", primaries[0], primaries[1])
            assertNotEquals("primary 亮暗=$isDark：Warm 与 Brand 相同", primaries[1], primaries[2])
            assertNotEquals("primary 亮暗=$isDark：Classic 与 Brand 相同", primaries[0], primaries[2])

            val surface2s = AppPalette.entries.map { tokens(it, isDark).surface2 }
            assertNotEquals("surface2 亮暗=$isDark：Classic 与 Warm 相同", surface2s[0], surface2s[1])
            assertNotEquals("surface2 亮暗=$isDark：Warm 与 Brand 相同", surface2s[1], surface2s[2])
            assertNotEquals("surface2 亮暗=$isDark：Classic 与 Brand 相同", surface2s[0], surface2s[2])
        }
    }

    // ── ④ 缺档 fail-fast ──────────────────────────────────────────────

    @Test
    fun 缺档_failFast_消息含palette名与明暗() {
        // scheme()/tokens() 都经 paletteSchemeByName(name, isDark) 这一条路查表；
        // 「新增 AppPalette 枚举但忘登记」炸的就是这里——用不存在的名字模拟漏登记。
        val missing = "NoSuchPalette"
        try {
            Palettes.paletteSchemeByName(missing, isDark = false)
            fail("缺档应当抛异常（fail-fast），实际静默返回了")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            assertTrue("异常消息应含 palette 名「$missing」，实际：$msg", msg.contains(missing))
            assertTrue("异常消息应含明暗「isDark=false」，实际：$msg", msg.contains("isDark=false"))
        }

        // 暗色档同理（两条分支都要有）
        try {
            Palettes.paletteSchemeByName(missing, isDark = true)
            fail("缺档应当抛异常（fail-fast），实际静默返回了")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("isDark=true"))
        }
    }

    // ── ⑤ Classic 与 Warm 背景必须不同 ────────────────────────────────

    @Test
    fun classic与warm_背景必须不同() {
        // 证明 master 回迁表真的生效，不是把 warm 表复制粘贴成 classic
        assertNotEquals(
            "亮色 background：Classic 与 Warm 相同（回迁表没生效？）",
            Palettes.scheme(AppPalette.Warm, isDark = false).background,
            Palettes.scheme(AppPalette.Classic, isDark = false).background,
        )
        assertNotEquals(
            "暗色 background：Classic 与 Warm 相同（回迁表没生效？）",
            Palettes.scheme(AppPalette.Warm, isDark = true).background,
            Palettes.scheme(AppPalette.Classic, isDark = true).background,
        )
    }
}
