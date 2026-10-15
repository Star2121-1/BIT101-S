package cn.bit101.android.data.schedule

import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.CourseColorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseColorResolver] 的规则测试：auto 按 palette 解析、显式选族永远覆盖、
 * 脏值链路不崩、同 palette 下族不同 ⇒ 课程颜料不同。
 *
 * ⚠️ auto 按 palette 不按 skin 是有教训的规则（初稿 §6.2）：alpha 用户默认
 * palette=warm、skin=classic，按 skin 解析会让他们升级后课程色从 12 色掉成统色。
 * 这条规则一旦被「顺手改成按 skin」，[auto按palette解析] 立刻红。
 */
class CourseColorResolverTest {

    /** auto × palette 全组合：Classic⇒Legacy 统色；Warm/Brand⇒Classic 12 色。 */
    @Test
    fun auto按palette解析() {
        assertEquals(
            CourseColorFamily.Legacy,
            CourseColorResolver.resolve(CourseColorFamily.Auto, AppPalette.Classic),
        )
        assertEquals(
            CourseColorFamily.Classic,
            CourseColorResolver.resolve(CourseColorFamily.Auto, AppPalette.Warm),
        )
        assertEquals(
            CourseColorFamily.Classic,
            CourseColorResolver.resolve(CourseColorFamily.Auto, AppPalette.Brand),
        )
    }

    /** 显式选族永远覆盖 auto，且与 palette 无关（一次性覆写语义）。 */
    @Test
    fun 显式选族覆盖auto() {
        val explicit = listOf(
            CourseColorFamily.Legacy,
            CourseColorFamily.Classic,
            CourseColorFamily.Morandi,
            CourseColorFamily.Brand,
        )
        for (family in explicit) {
            for (palette in AppPalette.allPalettes) {
                assertEquals(
                    family,
                    CourseColorResolver.resolve(family, palette),
                )
            }
        }
    }

    /**
     * 脏值链路：存盘字符串脏 ⇒ 枚举回落显式默认（Auto）⇒ 再按 palette 解析，不崩不白屏。
     * 同时锁「默认 palette 链路下 auto ⇒ Classic 12 色」——alpha 用户观感零变化的那条路。
     */
    @Test
    fun 脏值链路回落默认值() {
        val stored = CourseColorFamily.getFamily("not-a-family")
        assertEquals(CourseColorFamily.Auto, stored)

        val palette = AppPalette.getPalette("also-garbage")
        assertEquals(AppPalette.Warm, palette)

        assertEquals(
            CourseColorFamily.Classic,
            CourseColorResolver.resolve(stored, palette),
        )
    }

    /**
     * 同一 palette 下族不同 ⇒ 课程颜料不同。
     *
     * :data 侧断言到**族一级**（Auto≡显式 Classic、Legacy 是统色 ⇒ 不同族）；
     * 色表级「颜色不同」断言属 P5-D——族注册表（CoursePalettes）落 features/theme，
     * :data 看不见色表（依赖方向 theme→data），这是层级边界不是遗漏。
     * 色号（哈希）不随族变：换族只换颜料，课↔色号映射不动。
     */
    @Test
    fun 同palette下族不同_课程颜料不同() {
        val palette = AppPalette.Warm
        val viaAuto = CourseColorResolver.resolve(CourseColorFamily.Auto, palette)
        val viaClassic = CourseColorResolver.resolve(CourseColorFamily.Classic, palette)
        val viaLegacy = CourseColorResolver.resolve(CourseColorFamily.Legacy, palette)

        // warm 下 auto ≡ 显式 Classic（默认用户拿 12 色）
        assertEquals(viaClassic, viaAuto)
        // Legacy 统色与前两者不同族 ⇒ theme 层查不同的表 ⇒ 课程色不同
        assertNotEquals(viaLegacy, viaAuto)

        // 哈希不进 resolver：同一门课的色号在两种族下是同一个槽位
        for (name in listOf("数据结构", "高等数学", "大学英语(三)", "")) {
            val index = CourseColorLogic.of(name)
            assertTrue(index in 0 until CourseColorLogic.PALETTE_SIZE)
            assertEquals(index, CourseColorLogic.of(name)) // 稳定：不随设备/时间/族变
        }
    }
}
