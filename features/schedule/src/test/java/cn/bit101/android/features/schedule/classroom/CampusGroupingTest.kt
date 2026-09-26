package cn.bit101.android.features.schedule.classroom

import cn.bit101.api.model.common.BuildingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CampusGrouping] 测试 —— 空教室页三级折叠的最外层（校区）分组规则。
 */
class CampusGroupingTest {

    private fun building(name: String, code: String, campusName: String, campusCode: String) =
        BuildingInfo(
            buildingName = name,
            buildingIndex = code,
            campusName = campusName,
            campusCode = campusCode,
        )

    private val liangxiang1 = building("综合教学楼", "A", "良乡校区", "LX")
    private val liangxiang2 = building("理科教学楼", "B", "良乡校区", "LX")
    private val zhongguancun = building("主楼", "C", "中关村校区", "ZG")

    @Test
    fun `按校区分组并保持服务端顺序`() {
        val groups = CampusGrouping.group(listOf(liangxiang1, zhongguancun, liangxiang2))

        assertEquals(2, groups.size)
        assertEquals("良乡校区", groups[0].campusName)
        assertEquals(listOf("综合教学楼", "理科教学楼"), groups[0].buildings.map { it.buildingName })
        assertEquals("中关村校区", groups[1].campusName)
        assertEquals(1, groups[1].buildings.size)
    }

    /** 默认校区要排最前（并会被自动展开）—— 顺序不能靠运气。 */
    @Test
    fun `当前校区排最前`() {
        val groups = CampusGrouping.group(
            buildings = listOf(liangxiang1, zhongguancun),
            currentCampusCode = "ZG",
            currentCampusName = "中关村校区",
        )

        assertEquals("中关村校区", groups[0].campusName)
        assertTrue(groups[0].isCurrent)
        assertFalse(groups[1].isCurrent)
    }

    /** 老版本可能只存了校区名没存代码 → 退化成按名字判定。 */
    @Test
    fun `代码为空时按名字判定当前校区`() {
        val groups = CampusGrouping.group(
            buildings = listOf(liangxiang1, zhongguancun),
            currentCampusCode = "",
            currentCampusName = "中关村校区",
        )

        assertTrue(groups[0].isCurrent)
        assertEquals("中关村校区", groups[0].campusName)
    }

    /** 没设置过校区 → 谁也不标成当前，保持服务端顺序。 */
    @Test
    fun `未设置校区时无当前校区`() {
        val groups = CampusGrouping.group(listOf(liangxiang1, zhongguancun))

        assertTrue(groups.none { it.isCurrent })
        assertEquals("良乡校区", groups[0].campusName)
    }

    /**
     * 楼里连校区信息都没有时，**不能把不同的校区合成一个桶** ——
     * 退化成用校区名当键，名字也空才丢进兜底桶。
     */
    @Test
    fun `校区代码缺失时退化成校区名`() {
        val a = building("一号楼", "A", "", "")
        val b = building("一号楼", "B", "沙河校区", "")

        val groups = CampusGrouping.group(listOf(a, b))

        assertEquals(2, groups.size)
        assertEquals(CampusGrouping.UNKNOWN_CODE, groups[0].campusCode)
        assertEquals(CampusGrouping.UNKNOWN_CODE, groups[0].campusName)
        assertEquals("沙河校区", groups[1].campusCode)
    }

    @Test
    fun `空输入得到空分组`() {
        assertEquals(emptyList<CampusGroup>(), CampusGrouping.group(emptyList()))
    }
}
