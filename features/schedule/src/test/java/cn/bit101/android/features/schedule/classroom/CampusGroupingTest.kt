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
        // ⚠️ 分组键那串下划线只用于分组，**不能直接显示** —— 显示的是「未归类」
        assertEquals(CampusGrouping.UNKNOWN_NAME, groups[0].campusName)
        assertEquals("沙河校区", groups[1].campusCode)
    }

    /**
     * ⚠️⚠️ **实机发现的 bug**（2026-10-09 空教室页真机走查）：
     * 页面上出现过一个**名字叫 `null` 的校区**。
     *
     * 根因：学校接口把「没有校区」原样返回成**字符串 `"null"`** ——
     * 它既不是空串也不 blank，`ifBlank` 兜不住，于是被当成真校区名显示了出来。
     */
    @Test
    fun `校区名字面量是 null 字符串时当作没有`() {
        val weird = building("某栋楼", "A", "null", "null")

        val groups = CampusGrouping.group(listOf(liangxiang1, weird))

        assertEquals(2, groups.size)
        val unknown = groups.first { it.buildingNames() == listOf("某栋楼") }
        assertEquals(CampusGrouping.UNKNOWN_NAME, unknown.campusName)
        assertTrue(
            "不该出现字面量 null：${groups.map { it.campusName }}",
            groups.none { it.campusName.contains("null", ignoreCase = true) },
        )
    }

    private fun CampusGroup.buildingNames() = buildings.map { it.buildingName }

    @Test
    fun `空输入得到空分组`() {
        assertEquals(emptyList<CampusGroup>(), CampusGrouping.group(emptyList()))
    }
}
