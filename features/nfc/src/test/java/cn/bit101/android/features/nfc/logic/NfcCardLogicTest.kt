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

    // ------------------------------------------------- 北理工校园卡真卡实测（2026-09-30）

    /**
     * 用户从真校园卡贴回来的 techList：
     * `[IsoDep, NfcA, NfcA, MifareClassic, NdefFormatable]`。
     *
     * 这一条是整个模块最重要的一组断言：上一版把 `MifareClassic` 排在 `IsoDep` 前面，
     * 结果这种**双界面卡**被判成「纯 Classic，要密钥，读不了」——
     * 那条唯一能安全取数的 APDU 通道被我们自己掐了，贴了卡却拿不到任何探测结果。
     */
    @Test
    fun `校园卡双界面必须判成 CPU 卡而不是 MIFARE Classic`() {
        val raw = RawTag(
            id = byteArrayOf(0x77, 0x75, 0xF0.toByte(), 0x7B),
            techs = listOf(
                "android.nfc.tech.IsoDep",
                "android.nfc.tech.NfcA",
                "android.nfc.tech.NfcA",
                "android.nfc.tech.MifareClassic",
                "android.nfc.tech.NdefFormatable",
            ),
        )

        assertEquals(CardKind.CPU_CARD, NfcCardLogic.recognize(raw))
        assertTrue(NfcCardLogic.supportsApdu(raw))
        // 同时要记住它带 Classic 层 —— 写入流程靠这个标记拦住危险操作
        assertTrue(NfcCardLogic.describe(raw).classicCompat)
    }

    @Test
    fun `重复的 tech 在展示列表里只留一个`() {
        val raw = RawTag(
            id = byteArrayOf(0x77),
            techs = listOf("android.nfc.tech.IsoDep", "android.nfc.tech.NfcA", "android.nfc.tech.NfcA"),
        )
        assertEquals(listOf("IsoDep", "NfcA"), NfcCardLogic.techShortNames(raw))
    }

    /** 只有 Classic 没有 IsoDep 的卡，仍然是 Classic —— 别把上面那条顺序改过头。 */
    @Test
    fun `纯 MIFARE Classic 不会被误判成 CPU 卡`() {
        val raw = RawTag(
            id = byteArrayOf(0x01),
            techs = listOf("android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic"),
        )
        assertEquals(CardKind.MIFARE_CLASSIC, NfcCardLogic.recognize(raw))
        assertFalse(NfcCardLogic.supportsApdu(raw))
        assertTrue(NfcCardLogic.describe(raw).classicCompat)
    }

    /**
     * 「整串当无符号整数」是上一版漏掉的一项 —— 用户把真卡的候选全列出来之后发现
     * 9 位与 12 位都对不上卡面。补进来后最可能命中的就是那个 10 位的数字。
     */
    @Test
    fun `卡面号候选含整串整数且列在最前`() {
        val candidates = NfcCardLogic.candidates(byteArrayOf(0x77, 0x75, 0xF0.toByte(), 0x7B))
        val labels = candidates.map { it.label }

        assertEquals("UID 正序当整数（十进制）", labels.first())
        assertEquals("UID 反序当整数（十进制）", labels[1])

        val values = candidates.associate { it.label to it.value }
        // 0x7775F07B 与 0x7BF07577 的十进制值（用 `python -c "print(hex)"` 核过，别手算）
        assertEquals("2004217979", values["UID 正序当整数（十进制）"])
        assertEquals("2079356279", values["UID 反序当整数（十进制）"])
    }

    // ------------------------------------------------------------ 卡内找学号

    @Test
    fun `学号 BCD 编码是两位十进制压一个字节`() {
        val bcd = StudentIdScan.encodingsOf("1120241355").first { it.first == "BCD" }.second
        assertEquals("11 20 24 13 55", CardProbeLogic.toHex(bcd))
    }

    @Test
    fun `学号在返回数据里能被 BCD 形式找到`() {
        // 前面塞几个无关字节，模拟「学号在某个文件的中间」
        val data = byteArrayOf(0x00, 0x6F, 0x11, 0x20, 0x24, 0x13, 0x55, 0x90.toByte(), 0x00)
        val hit = StudentIdScan.find(data, "1120241355")
        assertTrue("应命中 BCD：$hit", hit?.contains("BCD") == true)
        assertTrue("偏移应是 2：$hit", hit?.contains("偏移 2") == true)
    }

    @Test
    fun `学号以 ASCII 形式存放也能找到`() {
        val data = ("\u0000\u0000" + "1120241355" + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val hit = StudentIdScan.find(data, "1120241355")
        assertTrue("应命中 ASCII：$hit", hit?.contains("ASCII") == true)
    }

    @Test
    fun `找不到就返回 null 而不是猜一个`() {
        val data = byteArrayOf(0x11, 0x22, 0x33)
        assertNull(StudentIdScan.find(data, "1120241355"))
        assertNull(StudentIdScan.find(ByteArray(0), "1120241355"))
    }

    /** 学号里混了非数字（比如有人把 `1120241355x` 贴进来）时不该编出半个 BCD。 */
    @Test
    fun `非数字学号不产出任何编码`() {
        assertTrue(StudentIdScan.encodingsOf("1120241355x").isEmpty())
        assertTrue(StudentIdScan.encodingsOf("").isEmpty())
    }

    /** 奇数位学号补 0 在**尾部**：补错位置就永远匹配不上，这里钉住。 */
    @Test
    fun `奇数位学号末尾补零`() {
        val bcd = StudentIdScan.encodingsOf("12345").first { it.first == "BCD" }.second
        assertEquals("12 34 50", CardProbeLogic.toHex(bcd))
    }

    /** 7 字节 UID 转整数会超出 Int/Long 的按位拼装直觉，这里确认没溢出也没丢符号。 */
    @Test
    fun `7 字节 UID 的整串整数用无符号大数算`() {
        val seven = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val value = NfcCardLogic.candidates(seven).first().value
        assertEquals(java.math.BigInteger(1, seven).toString(), value)
        assertTrue(value.length >= 16)
    }
}
