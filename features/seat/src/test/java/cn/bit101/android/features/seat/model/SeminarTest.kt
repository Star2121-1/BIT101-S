package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SeminarRecord] 的解析单测。
 *
 * 字段名与「`status == "2"` = 还能取消」这条判据，都是从 h5 研讨间列表页
 * （`assets/seminar.*.js`）的渲染代码里读出来的 —— 这里把它们锁住，
 * 免得日后照着猜的字段名写代码。
 */
class SeminarTest {

    @Test
    fun `解析服务端返回的条目`() {
        val json = JSONArray(
            """
            [{"id":"8801","nameMerge":"徐特立图书馆-三层-研讨间A","day":"2026-09-29",
              "start":"14:00:00","end":"16:00:00","status":"2","statusname":"已预约"},
             {"id":"8802","nameMerge":"徐特立图书馆-四层-研讨间B","day":"2026-09-28",
              "start":"09:00:00","end":"11:00:00","status":"4","statusname":"已使用"}]
            """.trimIndent()
        )
        val out = parseSeminarRecords(json)
        assertEquals(2, out.size)
        assertEquals("8801", out[0].id)
        assertEquals("徐特立图书馆-三层-研讨间A", out[0].nameMerge)
        assertEquals("2026-09-29", out[0].day)
        assertTrue(out[0].cancellable)
        assertFalse(out[1].cancellable)
        assertEquals("已使用", out[1].statusName)
    }

    @Test
    fun `状态 2 才是可取消`() {
        assertTrue(SeminarRecord(status = "2").cancellable)
        listOf("1", "3", "4", "6", "8", "").forEach {
            assertFalse("status=$it 不该可取消", SeminarRecord(status = it).cancellable)
        }
    }

    @Test
    fun `空数组解析出空列表`() {
        assertEquals(emptyList<SeminarRecord>(), parseSeminarRecords(JSONArray("[]")))
    }

    @Test
    fun `缺字段不崩`() {
        val out = parseSeminarRecords(JSONArray("""[{"id":"1"}]"""))
        assertEquals(1, out.size)
        assertEquals("1", out[0].id)
        assertEquals("", out[0].day)
    }

    @Test
    fun `非对象元素被跳过`() {
        assertEquals(1, parseSeminarRecords(JSONArray("""["x",{"id":"1"}]""")).size)
    }

    @Test
    fun `时间文案裁到分钟并拼在一行`() {
        // 服务端给的是 "14:00:00"，展示用 "14:00-16:00"
        val r = SeminarRecord(day = "2026-09-29", start = "14:00:00", end = "16:00:00")
        assertEquals("2026-09-29 14:00-16:00", r.timeText())
    }

    @Test
    fun `时间缺一半也不残缺`() {
        assertEquals("2026-09-29", SeminarRecord(day = "2026-09-29").timeText())
        assertEquals("14:00-16:00", SeminarRecord(start = "14:00", end = "16:00").timeText())
        assertEquals("", SeminarRecord().timeText())
    }
}
