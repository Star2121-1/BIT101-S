package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RenegeRecord] 的解析单测。
 *
 * 字段名是**从一个静态分包里读出来的**（`h5/assets/contract.*.js` 的渲染代码用到
 * `nameMerge` / `name` / `time` / `status` / `statusname`）—— 这里把它锁住，
 * 免得日后照着猜的字段名写代码。
 */
class RenegeTest {

    @Test
    fun `解析服务端返回的条目`() {
        val json = JSONArray(
            """
            [
              {"nameMerge":"徐特立馆-三层-自然科学图书第一阅览室","name":"018",
               "time":"2026-09-27 11:33","status":"1","statusname":"违约"},
              {"nameMerge":"徐特立馆-三层-视听学习空间","name":"004",
               "time":"2026-09-28 09:10","status":"2","statusname":"已处理"}
            ]
            """.trimIndent()
        )
        val out = parseRenegeRecords(json)
        assertEquals(2, out.size)
        assertEquals("徐特立馆-三层-视听学习空间", out[1].nameMerge)
        assertEquals("004", out[1].name)
        assertEquals("2026-09-28 09:10", out[1].time)
        assertEquals("已处理", out[1].statusName)
    }

    @Test
    fun `空数组解析出空列表`() {
        assertEquals(emptyList<RenegeRecord>(), parseRenegeRecords(JSONArray("[]")))
    }

    @Test
    fun `缺字段不崩`() {
        val out = parseRenegeRecords(JSONArray("""[{"name":"018"}]"""))
        assertEquals(1, out.size)
        assertEquals("018", out[0].name)
        assertEquals("", out[0].time)
    }

    @Test
    fun `非对象元素被跳过`() {
        val out = parseRenegeRecords(JSONArray("""["x", {"name":"018"}]"""))
        assertEquals(1, out.size)
    }

    @Test
    fun `展示文案在缺一半时也不残缺`() {
        assertEquals("徐特立馆 018", RenegeRecord(nameMerge = "徐特立馆", name = "018").label())
        assertEquals("徐特立馆", RenegeRecord(nameMerge = "徐特立馆").label())
        assertEquals("违约记录", RenegeRecord().label())
    }

    @Test
    fun `身份键三段都参与`() {
        val a = RenegeRecord("馆", "018", "t1")
        val b = RenegeRecord("馆", "018", "t2")
        val c = RenegeRecord("馆", "019", "t1")
        assertTrue(setOf(a.key, b.key, c.key).size == 3)
    }
}
