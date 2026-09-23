package cn.bit101.android.features.schedule.ddl

import cn.bit101.android.data.database.entity.DDLScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * DDL 列表分区顺序 + 扁平下标的单测。
 *
 * 重点锁 [DdlListOrder.flatIndexOf]：它是「组件点条目 → App 滚到那一条」的关键换算，
 * 而 `LazyColumn` 的下标里**混着分区标题行** —— 少加一个 +1 就会滚错位置，
 * 而且看代码很难发现（实测才会看到「滚到了隔壁那条」）。
 */
class DdlListOrderTest {

    private val base = LocalDateTime.of(2026, 9, 23, 12, 0)

    private fun ddl(uid: String, hoursFromBase: Long, done: Boolean = false) = DDLScheduleEntity(
        id = 0,
        group = "eclass",
        uid = uid,
        title = uid,
        text = "",
        time = base.plusHours(hoursFromBase),
        done = done,
    )

    @Test
    fun `未完成按时间升序、已完成按时间倒序`() {
        val events = listOf(
            ddl("c", 3),
            ddl("a", 1),
            ddl("b", 2),
            ddl("x", -1, done = true),
            ddl("y", -2, done = true),
        )

        assertEquals(listOf("a", "b", "c"), DdlListOrder.pending(events).map { it.uid })
        assertEquals(listOf("x", "y"), DdlListOrder.done(events).map { it.uid })
    }

    /** 两个分区都在时：`[未完成标题, 未完成…, 已完成标题, 已完成…]`。 */
    @Test
    fun `两个分区的下标要算上标题行`() {
        val events = listOf(
            ddl("a", 1),
            ddl("b", 2),
            ddl("x", -1, done = true),
            ddl("y", -2, done = true),
        )

        // 0 = 未完成标题，1..2 = 未完成条目，3 = 已完成标题，4..5 = 已完成条目
        assertEquals(1, DdlListOrder.flatIndexOf(events, "a"))
        assertEquals(2, DdlListOrder.flatIndexOf(events, "b"))
        assertEquals(4, DdlListOrder.flatIndexOf(events, "x"))
        assertEquals(5, DdlListOrder.flatIndexOf(events, "y"))
    }

    /** 只剩一个分区时，标题行也只剩一个 —— 下标要跟着变。 */
    @Test
    fun `只有未完成时下标不含已完成标题`() {
        val events = listOf(ddl("a", 1), ddl("b", 2))

        // 0 = 未完成标题，1..2 = 条目（没有已完成分区，所以没有第二个标题）
        assertEquals(1, DdlListOrder.flatIndexOf(events, "a"))
        assertEquals(2, DdlListOrder.flatIndexOf(events, "b"))
    }

    @Test
    fun `只有已完成时第一条在标题之后`() {
        val events = listOf(ddl("x", -1, done = true), ddl("y", -2, done = true))

        assertEquals(1, DdlListOrder.flatIndexOf(events, "x"))
        assertEquals(2, DdlListOrder.flatIndexOf(events, "y"))
    }

    @Test
    fun `空列表与被删除的 uid 返回负一`() {
        assertEquals(-1, DdlListOrder.flatIndexOf(emptyList(), "a"))
        assertEquals(-1, DdlListOrder.flatIndexOf(listOf(ddl("a", 1)), "gone"))
    }
}
