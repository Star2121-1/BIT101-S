package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 教学安排调整缓存的编解码单测。
 *
 * 这个文件只有两个失败模式值得防：**解析崩掉**与**坏行污染好行**。
 * 缓存坏了顶多重新联网，但如果坏行被当成有效数据，就会出现**错误的调休**。
 */
class TeachingAdjustmentStoreTest {

    private fun entry(
        date: LocalDate,
        plan: DayPlan,
        note: String = "国庆假期",
        title: String = "关于2026年中秋节、国庆节教学安排调整的通知",
        url: String = "https://jxzx.bit.edu.cn/jxyx/kctz/x.htm",
    ) = TeachingAdjustmentEntry(date, plan, note, title, url)

    private val notices = listOf(TeachingNotice("关于2026年中秋节、国庆节教学安排调整的通知", "https://jxzx.bit.edu.cn/a.htm"))

    @Test
    fun `编解码往返一致`() {
        val cached = TeachingAdjustmentStore.Cached(
            fetchedAtMillis = 1738000000000L,
            entries = listOf(
                entry(LocalDate.of(2026, 10, 10), DayPlan.MakeUp(4), "上课 按10月8日（周四）课表上课"),
                entry(LocalDate.of(2026, 10, 1), DayPlan.NoClass, "国庆放假 国庆假期"),
            ),
            notices = notices,
        )
        val back = TeachingAdjustmentStore.parse(TeachingAdjustmentStore.encode(cached))!!
        assertEquals(cached.fetchedAtMillis, back.fetchedAtMillis)
        assertEquals(cached.entries, back.entries)
        assertEquals(cached.notices, back.notices)
    }

    @Test
    fun `补课的目标星期几能被完整存回来`() {
        val cached = TeachingAdjustmentStore.Cached(
            1L, listOf(entry(LocalDate.of(2026, 9, 28), DayPlan.MakeUp(2))), emptyList(),
        )
        val back = TeachingAdjustmentStore.parse(TeachingAdjustmentStore.encode(cached))!!
        assertEquals(DayPlan.MakeUp(2), back.entries.single().plan)
    }

    /** 一行坏掉只丢那一行，不能整份作废 —— 但更不能把坏行当好数据。 */
    @Test
    fun `坏行被跳过，好行保留`() {
        val raw = """
            v1|100
            E|2026-10-10|MAKEUP|4|按周四|通知|x
            E|不是日期|MAKEUP|4|坏行|通知|x
            E|2026-10-11|MAKEUP|99|星期几越界|通知|x
            E|2026-10-12|不认识的类型|1|坏行|通知|x
            E|2026-10-13
            N|某通知|https://a
        """.trimIndent()
        val back = TeachingAdjustmentStore.parse(raw)!!
        assertEquals(1, back.entries.size)
        assertEquals(LocalDate.of(2026, 10, 10), back.entries.single().date)
        assertEquals(1, back.notices.size)
    }

    @Test
    fun `版本或首行不对就整份作废（不猜）`() {
        assertNull(TeachingAdjustmentStore.parse(null))
        assertNull(TeachingAdjustmentStore.parse(""))
        assertNull(TeachingAdjustmentStore.parse("v9|100\nE|2026-10-10|NOCLASS||x|y|z"))
        assertNull(TeachingAdjustmentStore.parse("没有分隔符"))
        assertNull(TeachingAdjustmentStore.parse("v1|不是数字"))
    }

    /** `|` 与换行会破坏行格式，落盘前必须被换掉。 */
    @Test
    fun `分隔符与换行被净化`() {
        val cached = TeachingAdjustmentStore.Cached(
            1L,
            listOf(entry(LocalDate.of(2026, 10, 1), DayPlan.NoClass, note = "放假|注：含换行\n第二行")),
            listOf(TeachingNotice("标题|带竖线", "https://a")),
        )
        val back = TeachingAdjustmentStore.parse(TeachingAdjustmentStore.encode(cached))!!
        // 关键：**仍然只剩一条**，没有被竖线切出新的一行
        assertEquals(1, back.entries.size)
        assertTrue(!back.entries.single().note.contains('\n'))
        assertTrue(back.notices.single().title.contains("标题"))
    }

    @Test
    fun `空缓存也能往返`() {
        val back = TeachingAdjustmentStore.parse(
            TeachingAdjustmentStore.encode(TeachingAdjustmentStore.Cached(5L, emptyList(), emptyList()))
        )!!
        assertEquals(5L, back.fetchedAtMillis)
        assertTrue(back.entries.isEmpty())
        assertTrue(back.notices.isEmpty())
    }
}
