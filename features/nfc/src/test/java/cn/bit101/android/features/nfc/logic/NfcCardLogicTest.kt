package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NfcCardLogic] 的单测。
 *
 * 这些例子全部**不需要 NFC 硬件** —— 输入是从 `Tag` 抄下来的字节，输出是字符串。
 * 之所以要写得这么细：眼下唯一的机器没有 NFC，这块翻译层对不对只能靠这里守住。
 */
class NfcCardLogicTest {

    private fun tag(vararg techs: String, id: ByteArray = ByteArray(0)) = RawTag(id = id, techs = techs.toList())

    // ---------------------------------------------------------------- 卡类型

    @Test
    fun `MifareClassic 被认成 MIFARE_CLASSIC`() {
        assertEquals(
            CardKind.MIFARE_CLASSIC,
            NfcCardLogic.recognize(tag("android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic")),
        )
    }

    /**
     * Type 4 标签同时报 `IsoDep` 和 `Ndef`，要判成 CPU 卡 ——
     * 判成贴纸的话，唯一的取数路（APDU）就被我们自己掐了。这是整个识别逻辑里
     * 顺序最要紧的一条，所以单独钉住。
     */
    @Test
    fun `IsoDep 与 Ndef 同时出现时判成 CPU 卡`() {
        val raw = tag("android.nfc.tech.NfcA", "android.nfc.tech.IsoDep", "android.nfc.tech.Ndef")
        assertEquals(CardKind.CPU_CARD, NfcCardLogic.recognize(raw))
        assertTrue(NfcCardLogic.supportsApdu(raw))
    }

    @Test
    fun `只有 Ndef 时才是普通贴纸`() {
        assertEquals(CardKind.NFC_FORUM_TAG, NfcCardLogic.recognize(tag("android.nfc.tech.Ndef")))
        assertEquals(
            CardKind.NFC_FORUM_TAG,
            NfcCardLogic.recognize(tag("android.nfc.tech.NdefFormatable")),
        )
    }

    @Test
    fun `NfcV 与未知都不会被当成可量子操作的卡`() {
        assertEquals(CardKind.NFC_V, NfcCardLogic.recognize(tag("android.nfc.tech.NfcV")))
        assertEquals(CardKind.UNKNOWN, NfcCardLogic.recognize(tag()))
        assertFalse(NfcCardLogic.supportsApdu(tag("android.nfc.tech.NfcV")))
    }

    // ---------------------------------------------------------------- UID 换算

    @Test
    fun `UID 十六进制是大写无分隔符`() {
        assertEquals("04A23FC1", NfcCardLogic.uidHex(byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0xC1.toByte())))
    }

    /**
     * Kotlin 的 `Byte` 是有符号的：不还原成无符号就会得到 `FFA23FC1` 这类结果，
     * 而这个 bug 在日志里很难被发现（看着也像合法的十六进制）。这里用一个最高位为 1 的
     * 字节把它钉住。
     */
    @Test
    fun `高位字节不会被当成负数`() {
        assertEquals("C1", NfcCardLogic.uidHex(byteArrayOf(0xC1.toByte())))
        assertEquals("FF", NfcCardLogic.uidHex(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `反转字节序`() {
        assertEquals("C13FA204", NfcCardLogic.uidHex(NfcCardLogic.reversed(byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0xC1.toByte()))))
    }

    /** 卡面号候选：值要和 RPA 上对得上，所以逐个断言、不用「非空」糊弄过去。 */
    @Test
    fun `卡面号候选包含常见的几种换算`() {
        val candidates = NfcCardLogic.candidates(byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0xC1.toByte()))
            .associate { it.label to it.value }

        assertEquals("04A23FC1", candidates["UID 正序（十六进制）"])
        assertEquals("C13FA204", candidates["UID 反序（十六进制）"])
        // 每个字节按三位十进制补齐再拼
        assertEquals("004162063193", candidates["UID 正序（十进制拼接）"])
        assertEquals("193063162004", candidates["UID 反序（十进制拼接）"])
        assertEquals("193063162", candidates["反序取前 3 字节（十进制）"])
        // 4 字节 UID 不该产出「取前 4 字节」这一项（和整串重复）
        assertFalse(candidates.containsKey("反序取前 4 字节（十进制）"))
    }

    @Test
    fun `7 字节 UID 才有取前 4 字节这一项`() {
        val id = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val labels = NfcCardLogic.candidates(id).map { it.label }
        assertTrue(labels.contains("反序取前 4 字节（十进制）"))
        assertTrue(labels.contains("反序取前 3 字节（十进制）"))
    }

    // ---------------------------------------------------------------- describe

    @Test
    fun `describe 给出尺寸 是否可发指令 短 tech 名与历史字节`() {
        val desc = NfcCardLogic.describe(
            RawTag(
                id = byteArrayOf(0x04, 0xA2.toByte()),
                techs = listOf("android.nfc.tech.NfcA", "android.nfc.tech.IsoDep"),
                historicalBytes = byteArrayOf(0x31, 0x80.toByte(), 0x8F.toByte()),
            )
        )

        assertEquals(CardKind.CPU_CARD, desc.kind)
        assertEquals(2, desc.uidSize)
        assertTrue(desc.supportsApdu)
        assertEquals(listOf("NfcA", "IsoDep"), desc.techShortNames)
        assertEquals("31808F", desc.historicalHex)
    }

    @Test
    fun `没有历史字节时 describe 里是 null 而不是空串`() {
        val desc = NfcCardLogic.describe(RawTag(id = byteArrayOf(0x04), techs = listOf("android.nfc.tech.NfcA")))
        assertNull(desc.historicalHex)
    }
}
