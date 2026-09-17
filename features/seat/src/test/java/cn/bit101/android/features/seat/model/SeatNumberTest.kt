package cn.bit101.android.features.seat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 座位号比较与排序。
 *
 * 这层修的是一个静默失效：实测服务端返回的座位号是**补零字符串**（`"001"`），
 * 而用户习惯输入 `"1"`。早期用 `it.no == seatNo` 直接比较，
 * 填 `"1"` 时永远匹配不上 —— 监控任务不会报错，只会一直显示「未找到座位」。
 */
class SeatNumberTest {

    @Test
    fun `bare number matches zero padded seat number`() {
        assertTrue(seatNumberEquals("001", "1"))
        assertTrue(seatNumberEquals("1", "001"))
        assertTrue(seatNumberEquals("012", "12"))
    }

    @Test
    fun `identical numbers match`() {
        assertTrue(seatNumberEquals("001", "001"))
        assertTrue(seatNumberEquals("A12", "A12"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertTrue(seatNumberEquals("001", " 1 "))
    }

    @Test
    fun `different numbers do not match`() {
        assertFalse(seatNumberEquals("001", "2"))
        assertFalse(seatNumberEquals("A12", "A13"))
        assertFalse(seatNumberEquals("001", ""))
    }

    @Test
    fun `non numeric values are compared as strings`() {
        assertTrue(seatNumberEquals("A1", "A1"))
        assertFalse(seatNumberEquals("A1", "1"))
    }

    @Test
    fun `comparator sorts numerically not lexicographically`() {
        // 字符串排序会得到 1,10,2,9；数值排序必须是 1,2,9,10
        val sorted = listOf("10", "9", "2", "1").sortedWith(SeatNumberComparator)

        assertEquals(listOf("1", "2", "9", "10"), sorted)
    }

    @Test
    fun `comparator keeps zero padded numbers in numeric order`() {
        val sorted = listOf("010", "002", "001").sortedWith(SeatNumberComparator)

        assertEquals(listOf("001", "002", "010"), sorted)
    }

    @Test
    fun `comparator puts non numeric seat numbers last`() {
        val sorted = listOf("A1", "2", "1").sortedWith(SeatNumberComparator)

        assertEquals(listOf("1", "2", "A1"), sorted)
    }
}
