package cn.bit101.android.features.seat.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓取的服务端响应**验证座位树解析与级联过滤。
 *
 * 资源文件 `tree_2026-09-19.json` 是 2026-09-18 深夜从 `/api/Seat/tree` 原样保存的
 * （徐特立馆 / 三层 下应可见 5 个区域）。
 *
 * 起因：模拟器上「区域下拉为空」的现象排查——最后确认是弹窗渲染在字段**上方**
 * 导致截图目测误判，解析本身没问题。这条测试用来把「解析+过滤必须给出 5 个区域」
 * 固定住，避免以后再被同类现象带偏。
 */
class SeatTreeResourceTest {

    private fun loadTree(): JSONArray {
        val text = javaClass.classLoader!!
            .getResourceAsStream("tree_2026-09-19.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(text).getJSONArray("data")
    }

    @Test
    fun `real tree response flattens to full depth`() {
        val nodes = parseSeatTree(loadTree())

        assertTrue("应解析出 30+ 个节点，实际 ${nodes.size}", nodes.size >= 30)

        val campuses = nodes.filter { it.type == 0 && it.parentId == null }
        assertEquals("顶层应只有 2 个校区", 2, campuses.size)
        assertEquals("徐特立馆", campuses.first().name)
    }

    @Test
    fun `areas under 三层 are non-empty (type and parentId are coerced from strings)`() {
        val nodes = parseSeatTree(loadTree())

        val campus = nodes.first { it.type == 0 && it.parentId == null && it.name == "徐特立馆" }
        val floors = nodes.filter { it.type == 0 && it.parentId == campus.id }
        assertEquals("徐特立馆应有 5 个楼层", 5, floors.size)

        val third = floors.first { it.name == "三层" }
        val areas = nodes.filter { it.type == 1 && it.parentId == third.id }

        // 服务端把 type/parentId 都发成字符串（"1" / "2"），必须显式转换后才能过滤
        assertEquals("三层下应有 5 个区域，实际 ${areas.map { it.name }}", 5, areas.size)
        assertTrue("应包含自然科学图书第一阅览室", areas.any { it.name == "自然科学图书第一阅览室" })
    }
}
