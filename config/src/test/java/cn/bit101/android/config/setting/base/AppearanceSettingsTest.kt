package cn.bit101.android.config.setting.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观切换三轴枚举（[AppSkin] / [AppPalette] / [CourseColorFamily]）的数据契约测试。
 *
 * 固化四条，缺一条都会在未来某次改动里静默坏掉：
 * ① 枚举往返（value ⇄ 枚举，含展示名）；
 * ② 脏值回落**到显式默认值**（不是 `first()`——展示序与默认不一致时 first() 会错）；
 * ③ value 字符串冻结（发布后改名 = 老用户设置静默回默认，这是数据兼容契约）；
 * ④ available/experimental 标记（置灰与实验性标注的唯一来源）。
 */
class AppearanceSettingsTest {

    // ── AppSkin ────────────────────────────────────────────────────────

    @Test
    fun appSkin_往返() {
        AppSkin.allSkins.forEach { skin ->
            assertEquals(skin, AppSkin.getSkin(skin.value))
            assertEquals(skin, AppSkin.getSkin(skin.toAppSkinData().value))
            assertEquals(skin.value, skin.toAppSkinData().value)
        }
    }

    @Test
    fun appSkin_脏值回落显式默认值() {
        assertEquals(AppSkin.Classic, AppSkin.getSkin(""))
        assertEquals(AppSkin.Classic, AppSkin.getSkin("不存在的形态"))
        assertEquals(AppSkin.Classic, AppSkin.getSkin("GRID")) // value 冻结为小写，大小写不匹配即脏值
    }

    @Test
    fun appSkin_value字符串冻结() {
        assertEquals("classic", AppSkin.Classic.value)
        assertEquals("grid", AppSkin.Grid.value)
        assertEquals("原版界面", AppSkin.Classic.toAppSkinData().name)
        assertEquals("一格 GRID", AppSkin.Grid.toAppSkinData().name)
    }

    @Test
    fun appSkin_available标记() {
        assertTrue(AppSkin.Classic.available)
        assertFalse(AppSkin.Grid.available) // GRID 建设中：UI 置灰，P4 落地后翻 true
    }

    // ── AppPalette ─────────────────────────────────────────────────────

    @Test
    fun appPalette_往返() {
        AppPalette.allPalettes.forEach { palette ->
            assertEquals(palette, AppPalette.getPalette(palette.value))
            assertEquals(palette, AppPalette.getPalette(palette.toAppPaletteData().value))
            assertEquals(palette.value, palette.toAppPaletteData().value)
        }
    }

    @Test
    fun appPalette_脏值回落显式默认值_不是展示序首个() {
        // ⚠️ 这条是「不能用 first()」的哨兵：展示序第一个是 Classic，默认却是 Warm。
        // 若兜底写成 allPalettes.first()，脏值用户会莫名变回原版配色——本测试立刻红。
        assertEquals(AppPalette.Warm, AppPalette.getPalette(""))
        assertEquals(AppPalette.Warm, AppPalette.getPalette("不存在的配色"))
        assertEquals(AppPalette.Warm, AppPalette.getPalette("WARM")) // 大小写不匹配即脏值
        assertNotEquals(AppPalette.allPalettes.first(), AppPalette.getPalette("脏值"))
    }

    @Test
    fun appPalette_value字符串冻结() {
        assertEquals("classic", AppPalette.Classic.value)
        assertEquals("warm", AppPalette.Warm.value)
        assertEquals("brand", AppPalette.Brand.value)
        // 展示名契约（P5-B·F2 口径①）：与初稿 §1.3 权威表 / 用户原话对齐——
        // value 字符串（持久化键）不变，展示名是 P5-C 直接显示给用户的字
        assertEquals("原版配色", AppPalette.Classic.toAppPaletteData().name)
        assertEquals("一格 GRID 暖棕配色", AppPalette.Warm.toAppPaletteData().name)
        assertEquals("北理工配色", AppPalette.Brand.toAppPaletteData().name)
    }

    @Test
    fun appPalette_available与experimental标记() {
        // Classic / Warm：可直接选、不标实验性
        assertTrue(AppPalette.Classic.available)
        assertFalse(AppPalette.Classic.experimental)
        assertTrue(AppPalette.Warm.available)
        assertFalse(AppPalette.Warm.experimental)
        // Brand：可选但标「实验性」（占位值待官方 VI；若 Q2 改置灰只翻 available）
        assertTrue(AppPalette.Brand.available)
        assertTrue(AppPalette.Brand.experimental)
    }

    // ── CourseColorFamily ──────────────────────────────────────────────

    @Test
    fun courseColorFamily_往返() {
        CourseColorFamily.allFamilies.forEach { family ->
            assertEquals(family, CourseColorFamily.getFamily(family.value))
            assertEquals(family, CourseColorFamily.getFamily(family.toCourseColorFamilyData().value))
            assertEquals(family.value, family.toCourseColorFamilyData().value)
        }
    }

    @Test
    fun courseColorFamily_脏值回落显式默认值_不是展示序首个() {
        // Auto 恰好在展示序首位且是默认值；这条断言同时锁「回落值 == Auto」与「== first()」
        // 两个性质，将来若调整展示序（如把 Classic 提前），first() 实现会立刻红。
        assertEquals(CourseColorFamily.Auto, CourseColorFamily.getFamily(""))
        assertEquals(CourseColorFamily.Auto, CourseColorFamily.getFamily("不存在的族"))
        assertEquals(CourseColorFamily.Auto, CourseColorFamily.getFamily("LEGACY"))
        assertNotEquals(CourseColorFamily.allFamilies.firstOrNull { it != CourseColorFamily.Auto },
            CourseColorFamily.getFamily("脏值"))
    }

    @Test
    fun courseColorFamily_value字符串冻结() {
        assertEquals("auto", CourseColorFamily.Auto.value)
        assertEquals("legacy", CourseColorFamily.Legacy.value)
        assertEquals("classic", CourseColorFamily.Classic.value)
        assertEquals("morandi", CourseColorFamily.Morandi.value)
        assertEquals("brand", CourseColorFamily.Brand.value)
        assertEquals("跟随外观", CourseColorFamily.Auto.toCourseColorFamilyData().name)
        assertEquals("原版统色", CourseColorFamily.Legacy.toCourseColorFamilyData().name)
        assertEquals("经典 12 色", CourseColorFamily.Classic.toCourseColorFamilyData().name)
        assertEquals("莫兰迪 12 色", CourseColorFamily.Morandi.toCourseColorFamilyData().name)
        assertEquals("北理蓝 12 色", CourseColorFamily.Brand.toCourseColorFamilyData().name)
    }

    @Test
    fun courseColorFamily_available与experimental标记() {
        // Auto / Legacy / Classic / Morandi 可直接选；Brand 占位待官方 VI ⇒ 可选但标实验性
        listOf(
            CourseColorFamily.Auto,
            CourseColorFamily.Legacy,
            CourseColorFamily.Classic,
            CourseColorFamily.Morandi,
        ).forEach {
            assertTrue(it.available)
            assertFalse(it.experimental)
        }
        assertTrue(CourseColorFamily.Brand.available)
        assertTrue(CourseColorFamily.Brand.experimental)
    }
}
