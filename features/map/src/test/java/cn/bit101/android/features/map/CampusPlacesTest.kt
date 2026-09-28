package cn.bit101.android.features.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CampusPlaces] 的纯逻辑单测。
 *
 * 锁住的是**教务自由文本 → 具体一栋楼**这段匹配里最容易出错的地方：
 * - 带区号的必须落到那一区（`文萃楼I404` → 文萃楼 I，不是楼群中心）
 * - 不带区号的才退回中心点
 * - 全角 / 空格 / 小写字母都要能匹配（教务数据里真有）
 * - 匹配不到必须返回 null（UI 靠它决定要不要给按钮，**指错楼比不指更糟**）
 */
class CampusPlacesTest {

    @Test
    fun `带区号的教室落到那一区`() {
        assertEquals("文萃楼I", CampusPlaces.match("文萃楼I404")?.name)
        assertEquals("文萃楼A", CampusPlaces.match("文萃楼A305")?.name)
        assertEquals("文萃楼M", CampusPlaces.match("文萃楼M101")?.name)
        assertEquals("理学楼A", CampusPlaces.match("理学楼A305")?.name)
        assertEquals("理学楼C", CampusPlaces.match("理学楼C201")?.name)
    }

    @Test
    fun `不带区号时退回楼群中心`() {
        assertEquals("文萃楼", CampusPlaces.match("文萃楼404")?.name)
        assertEquals("理学楼", CampusPlaces.match("理学楼305")?.name)
    }

    @Test
    fun `简写也能匹配`() {
        assertEquals("文萃楼I", CampusPlaces.match("文萃I404")?.name)
        assertEquals("综合教学大楼", CampusPlaces.match("综教B301")?.name)
        assertEquals("综合教学大楼", CampusPlaces.match("综教楼301")?.name)
        assertEquals("中心教学楼", CampusPlaces.match("中教401")?.name)
    }

    @Test
    fun `全角与空格不影响匹配`() {
        // 教务数据里混着全角字母数字与空格，不归一化就全漏
        assertEquals("文萃楼I", CampusPlaces.match("文萃楼Ｉ４０４")?.name)
        assertEquals("文萃楼I", CampusPlaces.match("文萃楼 I 404")?.name)
        assertEquals("文萃楼I", CampusPlaces.match("文萃楼i404")?.name)
    }

    @Test
    fun `中关村的教学楼能匹配到`() {
        assertEquals("主楼", CampusPlaces.match("主楼301")?.name)
        assertEquals("信息教学楼", CampusPlaces.match("信息教学楼201")?.name)
        assertEquals("1#教学楼", CampusPlaces.match("1#教学楼305")?.name)
        assertEquals("7#教学楼", CampusPlaces.match("七号教学楼101")?.name)
    }

    @Test
    fun `匹配不到就返回 null —— 不给假按钮`() {
        assertNull(CampusPlaces.match(""))
        assertNull(CampusPlaces.match("   "))
        assertNull(CampusPlaces.match("未知楼101"))
        assertNull(CampusPlaces.match("线上"))
    }

    @Test
    fun `校区名都能在校区表里找到`() {
        val known = MapCampus.ALL.map { it.name }.toSet()
        CampusPlaces.ALL.forEach { place ->
            assertTrue("「${place.name}」的校区 ${place.campus} 不在 MapCampus.ALL 里", place.campus in known)
        }
    }

    @Test
    fun `坐标都落在归一化范围内`() {
        CampusPlaces.ALL.forEach { place ->
            assertTrue("${place.name} 的 x 越界: ${place.x}", place.x in 0.0..1.0)
            assertTrue("${place.name} 的 y 越界: ${place.y}", place.y in 0.0..1.0)
        }
    }

    @Test
    fun `每个地点都有别名且别名非空`() {
        CampusPlaces.ALL.forEach { place ->
            assertTrue("${place.name} 没有别名", place.aliases.isNotEmpty())
            place.aliases.forEach { assertTrue(it.isNotBlank()) }
        }
    }

    @Test
    fun `别名不重复 —— 重复会让匹配结果取决于表顺序`() {
        val all = CampusPlaces.ALL.flatMap { p -> p.aliases.map { CampusPlaces.normalize(it) to p.name } }
        val dup = all.groupBy { it.first }.filter { it.value.size > 1 }
        assertEquals("重复别名：$dup", emptyMap<String, List<Pair<String, String>>>(), dup)
    }

    @Test
    fun `待跳转目标取一次就没了`() {
        val place = CampusPlaces.match("文萃楼I404")
        assertNotNull(place)
        MapTargetHolder.request(MapTargetHolder.from(place!!))
        val first = MapTargetHolder.consume()
        assertEquals("文萃楼I", first?.name)
        assertNull("consume 必须是幂等的一次性操作", MapTargetHolder.consume())
    }

    @Test
    fun `图页路由与底栏一致`() {
        assertEquals("map", CampusPlaces.route)
    }
}
