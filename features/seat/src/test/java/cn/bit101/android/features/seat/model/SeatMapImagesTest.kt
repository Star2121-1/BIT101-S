package cn.bit101.android.features.seat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 座位底图选取的单测。
 *
 * 背景（真实 bug，2026-09-20 由用户发现）：`/api/seat/map` 返回的五张图**不是互补图层**，
 * 而是**五张各自完整的房间图 —— 同一位置在不同图里画的是不同图标**
 * （free=绿座位、book=文件、use=人、leave=时钟、close=黄座位）。
 *
 * 当时的实现以为「每张图只有对应状态的座位是亮的」，于是把五张**全叠**在一起。
 * 结果后叠的盖住先叠的，`leave` 在最上层 → **所有座位都显示成时钟（临时离开）**，
 * 用户看到的就是「怎么全部变成临时离开图标了」。
 *
 * 正确做法是**每个座位按自己的 status 只挑一张图**（官方前端 `seat-map.js` 即如此）。
 * 这个测试锁住「一张座位图只能有一个 URL」这个不变式 —— 它正是防叠图的护栏。
 */
class SeatMapImagesTest {

    private val all = SeatMapImages(
        free = "free.jpg",
        book = "book.jpg",
        close = "close.jpg",
        leave = "leave.jpg",
        use = "use.jpg",
    )

    // ── 状态 → 图片：与官方前端 seat-map.js 的分支完全一致 ────────────────
    // 官方原文：status==1 → free；status==7 → leave；[2,10,11] → book；
    //          [6,8,9] → use；[3,4,5] → close

    @Test
    fun `空闲取 free 图`() {
        assertEquals("free.jpg", all.forStatus(SeatStatus.AVAILABLE))
    }

    @Test
    fun `已预约与我订的都取 book 图`() {
        assertEquals("book.jpg", all.forStatus(SeatStatus.RESERVED))
        assertEquals("book.jpg", all.forStatus(SeatStatus.MINE))
    }

    @Test
    fun `在用取 use 图`() {
        assertEquals("use.jpg", all.forStatus(SeatStatus.IN_USE))
    }

    @Test
    fun `临时离开取 leave 图`() {
        assertEquals("leave.jpg", all.forStatus(SeatStatus.LEAVE))
    }

    @Test
    fun `暂停使用取 close 图`() {
        assertEquals("close.jpg", all.forStatus(SeatStatus.UNAVAILABLE))
    }

    // ── byStatus()：座位渲染实际走的入口 ─────────────────────────────────

    @Test
    fun `byStatus 覆盖全部座位状态`() {
        val map = all.byStatus()
        // 六种 SeatStatus 一个都不能漏：漏掉的那个状态座位会画不出图（变成透明洞）
        SeatStatus.entries.forEach { status ->
            assertEquals("状态 $status 缺少对应底图", true, map.containsKey(status))
        }
        assertEquals(6, map.size)
    }

    @Test
    fun `byStatus 不含状态之外的键`() {
        // MINE 与 RESERVED 共用 book，但必须是两个键而不是合并 → 大小恰好 6
        assertEquals(SeatStatus.entries.size, all.byStatus().size)
    }

    @Test
    fun `缺失的图不会出现在映射里 —— 防止画出 null 瓦片`() {
        val partial = SeatMapImages(free = "free.jpg", book = "book.jpg")
        val map = partial.byStatus()
        assertEquals("free.jpg", map[SeatStatus.AVAILABLE])
        assertEquals("book.jpg", map[SeatStatus.RESERVED])
        // 未提供的图不应凭空出现
        assertNull(map[SeatStatus.IN_USE])
        assertNull(map[SeatStatus.LEAVE])
        assertNull(map[SeatStatus.UNAVAILABLE])
    }

    @Test
    fun `空图集 byStatus 为空且 isEmpty 为真`() {
        val empty = SeatMapImages()
        assertEquals(0, empty.byStatus().size)
        assertEquals(true, empty.isEmpty)
    }

    /**
     * 核心护栏：**每个状态只能指向一张图**。
     *
     * 如果哪天有人加回「同一状态叠多张图」的逻辑（比如为了「更完整」而叠加），
     * 这个用例会失败 —— 叠加正是导致「全部变成临时离开」的根因。
     */
    @Test
    fun `每个座位状态只对应一张图 —— 叠图会重现全变临时离开的 bug`() {
        val map = all.byStatus()
        // Map 的键唯一，天然保证「一个状态一个 URL」。
        // 这里进一步断言：五个不同状态指向的是**五个不同的 URL**（没有误配成同一张）。
        val distinct = map.values.toSet()
        assertEquals("五个状态应指向五张不同的图，否则说明映射写错了", 5, distinct.size)
    }

    @Test
    fun `五个状态覆盖五张图 无遗漏无重复`() {
        val map = all.byStatus()
        assertEquals(setOf("free.jpg", "book.jpg", "use.jpg", "leave.jpg", "close.jpg"), map.values.toSet())
    }
}
