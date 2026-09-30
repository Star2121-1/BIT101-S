package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardProbeLogic] 的单测。
 *
 * ## ⚠️ 这个文件里最重要的一条测试
 *
 * [CardProbeLogicTest.`写指令一律被闸门拦下`] ——
 * 我们手边**没有 NFC 硬件**，所以这条一旦写错，第一次事故不是「功能坏了」，
 * 而是**用户的校园卡被弄坏或自锁**。它必须比其它所有测试都优先通过。
 *
 * 其余测试钉住的是字节级正确性：差一个字节，对面返回的就是 `6A87` 而不是想要的数据。
 */
class CardProbeLogicTest {

    // ------------------------------------------------------- 安全闸门（最重要）

    @Test
    fun `只读指令能通过闸门`() {
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.selectPpse()))
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.selectMasterFile()))
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readBinary(sfi = 1)))
    }

    /** 见文件头注释：这是本模块最要命的一条断言。 */
    @Test
    fun `写指令一律被闸门拦下`() {
        val writeCommands = mapOf(
            "UPDATE BINARY" to byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05),
            "UPDATE RECORD" to byteArrayOf(0x00, 0xDC.toByte(), 0x01, 0x05, 0x02, 0x01, 0x02),
            "WRITE RECORD" to byteArrayOf(0x00, 0xD2.toByte(), 0x01, 0x05, 0x00),
            "CREATE FILE" to byteArrayOf(0x00, 0xE0.toByte(), 0x00, 0x00, 0x00),
            "DELETE FILE" to byteArrayOf(0x00, 0xE4.toByte(), 0x00, 0x00, 0x02, 0x3F, 0x00),
            "RESET RETRY COUNTER" to byteArrayOf(0x00, 0x2C, 0x00, 0x01, 0x00),
        )
        writeCommands.forEach { (name, apdu) ->
            assertFalse("$name 必须被拦下", CardProbeLogic.isReadOnly(apdu))
        }
    }

    @Test
    fun `长度不足与非基本 CLA 也一律不许发`() {
        assertFalse(CardProbeLogic.isReadOnly(byteArrayOf(0x00, 0xA4.toByte())))
        assertFalse(CardProbeLogic.isReadOnly(byteArrayOf()))
        // CLA 带安全报文 / 非基本通道的语义，宁可不与放行
        assertFalse(CardProbeLogic.isReadOnly(byteArrayOf(0x04, 0xB0.toByte(), 0x0C, 0x00, 0x20)))
        assertFalse(CardProbeLogic.isReadOnly(byteArrayOf(0x84.toByte(), 0xB0.toByte(), 0x0C, 0x00, 0x20)))
    }

    // ---------------------------------------------------------- APDU 字节级正确

    @Test
    fun `SELECT NAME 的五个字段一个都不能错位`() {
        assertEquals(
            // `2PAY.SYS.DDF01` 是 **14** 字节，所以 Lc 必须是 0E（写成 0F 的话整条指令就废了）
            "00A404000E325041592E5359532E444446303100",
            CardProbeLogic.toHex(CardProbeLogic.selectPpse()).replace(" ", ""),
        )
    }

    @Test
    fun `SELECT MF 不按 AID 走`() {
        assertEquals("00 A4 00 00 02 3F 00", CardProbeLogic.toHex(CardProbeLogic.selectMasterFile()))
    }

    @Test
    fun `READ BINARY 把 SFI 编进 P1 的最高五位`() {
        // SFI=1 → P1 = 00001_100 = 0x0C
        assertEquals("00 B0 0C 00 20", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 1, length = 32)))
        // SFI=30 → P1 = 11110_100 = 0xF4
        assertEquals("00 B0 F4 00 10", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 30, length = 16)))
    }

    @Test
    fun `长度 256 在 Le 位置编码为 0`() {
        assertEquals("00 B0 0C 00 00", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 1, length = 256)))
    }

    @Test
    fun `越界参数早失败`() {
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readBinary(sfi = 31) }
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readBinary(sfi = 1, length = 0) }
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readBinary(sfi = 1, length = 257) }
        // AID 少于 5 字节不符合 ISO 7816-5
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.selectByName("3F00") }
    }

    // ------------------------------------------------------------ 回答的解析

    @Test
    fun `9000 表示成功且没有附带数据`() {
        val r = CardProbeLogic.parseResponse(byteArrayOf(0x90.toByte(), 0x00))
        assertTrue(r.success)
        assertEquals("9000", r.sw)
        assertEquals("成功", r.swText)
        assertEquals(0, r.data.size)
        assertFalse(r.hasMore)
    }

    @Test
    fun `带数据的回答要把状态字剥离干净`() {
        val r = CardProbeLogic.parseResponse(
            byteArrayOf(0x6F.toByte(), 0x0A, 0x01, 0x02, 0x90.toByte(), 0x00)
        )
        assertTrue(r.success)
        assertEquals("6F 0A 01 02", CardProbeLogic.toHex(r.data))
    }

    @Test
    fun `6A82 表示这个文件不存在`() {
        val r = CardProbeLogic.parseResponse(byteArrayOf(0x6A.toByte(), 0x82.toByte()))
        assertFalse(r.success)
        assertEquals("找不到这个文件/应用", r.swText)
    }

    @Test
    fun `61 开头表示还要再取一次`() {
        val r = CardProbeLogic.parseResponse(byteArrayOf(0x61.toByte(), 0x08))
        assertTrue(r.hasMore)
        assertFalse(r.success)
    }

    /** 不认识的状态字必须报「不认识」，不许解释成成功。 */
    @Test
    fun `未知状态字返回 null 而不是猜一个`() {
        assertNull(CardProbeLogic.statusText("6F01"))
        assertEquals("未指明的错误", CardProbeLogic.statusText("6F00"))
    }

    /** 短于两字节是异常回答：不猜、不补零，原样交出去让诊断页显示。 */
    @Test
    fun `短于两字节的回答被判失败但不篡改内容`() {
        val r = CardProbeLogic.parseResponse(byteArrayOf(0x00))
        assertFalse(r.success)
        assertEquals("", r.sw)
        assertNull(r.swText)
        assertEquals(1, r.data.size)
    }

    // -------------------------------------------------------------- 十六进制工具

    @Test
    fun `十六进制解析容忍空格与小写`() {
        assertEquals("3F 00", CardProbeLogic.toHex(CardProbeLogic.parseHex("3f 00")))
        assertEquals(0, CardProbeLogic.parseHex("").size)
    }

    @Test
    fun `奇数长度与非法字符一律报错`() {
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.parseHex("3F0") }
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.parseHex("3FG0") }
    }

    // ------------------------------------------------------------ 两轮探测的构成

    @Test
    fun `第一轮先确认通道再问卡里有什么`() {
        val steps = CardProbeLogic.firstRound()
        assertEquals(2, steps.size)
        assertTrue(steps[0].label.contains("主文件"))
        assertTrue(steps[1].label.contains("PPSE"))
    }

    /** 第二轮是探索性的，但每一条都必须过只读闸门 —— 一次误写就是事故。 */
    @Test
    fun `SFI 扫描的每一条都是只读的`() {
        val steps = CardProbeLogic.sfiScan()
        assertEquals(30, steps.size)
        assertTrue(steps.all { CardProbeLogic.isReadOnly(it.apdu) })
        assertEquals("READ BINARY SFI=1", steps.first().label)
        assertEquals("READ BINARY SFI=30", steps.last().label)
    }
}
