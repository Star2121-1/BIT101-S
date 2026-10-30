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
    fun appPalette_脏值回落显式默认值() {
        // 默认配色 = Brand（用户拍板）。兜底写显式 Brand，不依赖 allPalettes.first()。
        assertEquals(AppPalette.Brand, AppPalette.getPalette(""))
        assertEquals(AppPalette.Brand, AppPalette.getPalette("不存在的配色"))
        assertEquals(AppPalette.Brand, AppPalette.getPalette("WARM")) // 大小写不匹配即脏值
    }

    @Test
    fun appPalette_value字符串冻结() {
        assertEquals("classic", AppPalette.Classic.value)
        assertEquals("warm", AppPalette.Warm.value)
        assertEquals("brand", AppPalette.Brand.value)
        // 展示名契约（P5-C + 后续两轮用户拍板正名）：
        // value 字符串（持久化键）不变，展示名是设置页直接显示给用户的字。
        // Brand = 深蓝占位 ⇒「BIT101经典配色」，展示首位 + 默认（官方校色经章程查证为绿+墨绿+褐，
        //   深蓝占位不蹭学校名故去「北理工」冠名；用户拍板设默认，不再标 experimental）；
        // Classic = master 回迁（桃粉+暖橙）⇒「暖棕配色」，不冠「原版」；
        // Warm = P1 分支现状（白底+青绿）⇒「清新青绿配色」。旧名「一格 GRID 暖棕配色」
        // 名不副实已废（初稿 §119 意图暖棕、实现出来青绿）；新名刻意不含「一格 GRID」
        // （与 AppSkin.Grid 展示名撞词，N1 警示）。
        assertEquals("BIT101经典配色", AppPalette.Brand.toAppPaletteData().name)
        assertEquals("暖棕配色", AppPalette.Classic.toAppPaletteData().name)
        assertEquals("清新青绿配色", AppPalette.Warm.toAppPaletteData().name)
    }

    @Test
    fun appPalette_展示序_Brand首位() {
        // P5-C 后续用户拍板：BIT101经典配色（Brand）提至展示首位，Classic/Warm 顺延。
        // 默认值已改为 Brand（与首位一致，用户拍板）。
        assertEquals(
            listOf(AppPalette.Brand, AppPalette.Classic, AppPalette.Warm),
            AppPalette.allPalettes,
        )
    }

    @Test
    fun appPalette_available与experimental标记() {
        // Classic / Warm：可直接选、不标实验性
        assertTrue(AppPalette.Classic.available)
        assertFalse(AppPalette.Classic.experimental)
        assertTrue(AppPalette.Warm.available)
        assertFalse(AppPalette.Warm.experimental)
        // Brand：默认配色，不再标实验性（占位色值仍在，但用户拍板设为默认）
        assertTrue(AppPalette.Brand.available)
        assertFalse(AppPalette.Brand.experimental)
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
    fun courseColorFamily_脏值回落显式默认值() {
        // 默认族 = Legacy（原版统色，用户拍板）。兜底写显式 Legacy，不依赖 first()（first=Auto）。
        assertEquals(CourseColorFamily.Legacy, CourseColorFamily.getFamily(""))
        assertEquals(CourseColorFamily.Legacy, CourseColorFamily.getFamily("不存在的族"))
        assertEquals(CourseColorFamily.Legacy, CourseColorFamily.getFamily("LEGACY")) // 大小写不匹配即脏值
        assertNotEquals(CourseColorFamily.allFamilies.first(), CourseColorFamily.getFamily("脏值"))
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
        // 五族全部可直接选、且都不再打「实验性」标：Brand 已成为默认 App 配色，
        // 再把它自己的课程族标成实验性会自相矛盾（CoursePalette.brandLight 已落真表）。
        listOf(
            CourseColorFamily.Auto,
            CourseColorFamily.Legacy,
            CourseColorFamily.Classic,
            CourseColorFamily.Morandi,
            CourseColorFamily.Brand,
        ).forEach {
            assertTrue(it.available)
            assertFalse(it.experimental)
        }
    }
}
