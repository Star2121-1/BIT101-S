package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BCD 日期扫描的回归测试。
 *
 * 向量**全部取自真卡 dump**（北理工校园卡，2026-09-30 实测），
 * 不是编出来的漂亮数据 —— 包括那条「不该被认出来」的 SFI=7，
 * 它才是这个类最重要的一个测试。
 */
class BcdScanTest {

    private fun scan(hex: String) = BcdScan.scan(CardProbeLogic.parseHex(hex))

    /**
     * 真卡 `SELECT 0018` → `READ RECORD 1` 的 23 字节原文。
     *
     * 期望它认出**一处**日期 + 紧跟着的时间。这条是整个功能的动机：
     * 不认的话，人和我们都只看到一串十六进制。
     */
    @Test
    fun `真卡记录里的日期时间要认出来`() {
        val found = scan("0001000000000013880200000000000120260322110843")

        assertEquals(1, found.size)
        assertEquals("2026-03-22 11:08:43", found[0].text)
        // 偏移 16 起，占 4 字节（YYYYMMDD）；时间不单独报，只作后缀
        assertEquals(16, found[0].at)
        assertEquals(4, found[0].length)
        assertEquals("偏移 16：2026-03-22 11:08:43", found[0].display)
    }

    /** 真卡 `SFI=5`：`26 03 22` 是 `YYMMDD`；后面三个零**不是** `00:00:00`，不附。 */
    @Test
    fun `三字节 YYMMDD 也认 且全零时间不附上去`() {
        val found = scan("2603220000000000000000000000000000000000000000000000000000000000")

        assertEquals(1, found.size)
        assertEquals("2026-03-22", found[0].text)
        assertEquals(0, found[0].at)
        assertEquals(3, found[0].length)
    }

    /**
     * ⚠️ 这个类**最重要的一个测试**：真卡 `SFI=7` 那 32 字节不该报出任何日期。
     *
     * 它的内容 `00 00 01 14 | 01 03 05 | 00 00 02 76 …` 里，`01 03 05` 是完全合法的
     * BCD 时间 `01:03:05`、`00 01 14` 也是合法日期（2000-01-14）。要是扫描不设限，
     * 这里就会冒出两三条假日期，把真线索淹掉。
     *
     * 两道闸门拦住了它：年份窗口（2000 < 2020）+ 时间只作后缀（不单独报）。
     */
    @Test
    fun `业务流水段落里的数字不该被误报成日期`() {
        val found = scan(
            "00000114" + "0103050000027600010000" +
                "68" + "00000115" + "0103050000027700010000" + "66"
        )
        assertTrue("不该误报任何日期，实际：${found.map { it.text }}", found.isEmpty())
    }

    /** 真卡 `0015` 里的有效期：`20 28 08 30` = 2028-08-30。 */
    @Test
    fun `四字节 YYYYMMDD 认有效期`() {
        val found = scan("000000000000000000000000000000000044543800000000202808300002")
        assertEquals(1, found.size)
        assertEquals("2028-08-30", found[0].text)
    }

    /** 4 字节与它内部那 3 字节指的是同一天 —— 只能报一处，不能让人以为卡里有两个日期。 */
    @Test
    fun `四字节与重叠的三字节只报一处`() {
        val found = scan("20260322")
        assertEquals(1, found.size)
        assertEquals("2026-03-22", found[0].text)
        assertEquals(4, found[0].length)
    }

    /** 全零是空文件，不是「1900-00-00」这种鬼日期。 */
    @Test
    fun `全零不报`() {
        assertTrue(BcdScan.scan(ByteArray(32)).isEmpty())
    }

    /** 年份窗口：1999 年的事不在校园卡的业务范围内，多半是我们自己在数错位。 */
    @Test
    fun `窗口外的年份不报`() {
        assertTrue(scan("19990101").isEmpty())
        // 2099 也在窗口外
        assertTrue(scan("990101").isEmpty())
    }

    /** 二月要按闰年算 —— 否则 2028-02-29 这种合法有效期会被自己丢掉。 */
    @Test
    fun `闰年的二月二十九要认 平年的不认`() {
        assertEquals("2028-02-29", scan("20280229").single().text)
        assertTrue(scan("20260229").isEmpty())
    }

    /** 月份的合法性也要管：`20 26 13 01` 不是「2026 年 13 月」。 */
    @Test
    fun `月份和日期越界的不报`() {
        assertTrue(scan("20261301").isEmpty())
        assertTrue(scan("20260100").isEmpty())
        assertTrue(scan("20260431").isEmpty()) // 四月没有 31 号
    }

    /** 半字节 > 9 就不是 BCD（那是二进制字段），一个都不该认。 */
    @Test
    fun `非 BCD 字节不报`() {
        // 0xAB / 0xCD / 0xEF 都不是 BCD
        assertTrue(scan("ABCDEF").isEmpty())
        assertTrue(scan("20AB0322").isEmpty())
    }

    /** 年份窗口可以调：默认收不到的老日期，放宽后要能收到。 */
    @Test
    fun `年份窗口可调`() {
        assertTrue(scan("20150305").isEmpty())
        assertEquals("2015-03-05", BcdScan.scan(CardProbeLogic.parseHex("20150305"), minYear = 2010).single().text)
    }

    /** 短数据不能越界读崩 —— 卡回一个字节也是可能的。 */
    @Test
    fun `数据太短不崩`() {
        assertTrue(BcdScan.scan(ByteArray(0)).isEmpty())
        assertTrue(BcdScan.scan(CardProbeLogic.parseHex("20")).isEmpty())
        assertTrue(BcdScan.scan(CardProbeLogic.parseHex("2026")).isEmpty())
    }
}
