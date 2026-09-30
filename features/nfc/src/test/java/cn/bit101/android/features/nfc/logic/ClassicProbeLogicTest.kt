package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClassicProbeLogic] 的单测。
 *
 * 这里钉住的三件事，正好是 Classic 最容易写错、而在**没有 NFC 硬件**的开发机上
 * 又完全没法靠跑一遍发现的：
 * ① 扇区/块的**绝对块号**换算（写错会读到隔壁扇区，看起来还像成功了）；
 * ② 尾块必须跳过（那是密钥区，读它毫无意义，而且是最容易让人手滑去写的地方）；
 * ③ 块的三种读法（hex / ASCII / BCD）各在什么条件下才成立。
 */
class ClassicProbeLogicTest {

    // ------------------------------------------------------------ 布局

    @Test
    fun `扇区数只认 1K 与 4K`() {
        assertEquals(16, ClassicProbeLogic.sectorCount(ClassicProbeLogic.SIZE_1K))
        assertEquals(40, ClassicProbeLogic.sectorCount(ClassicProbeLogic.SIZE_4K))
        // 认不出容量 = 0，上层据此整段跳过，而不是按某个默认值瞎扫
        assertEquals(0, ClassicProbeLogic.sectorCount(0))
        assertEquals(0, ClassicProbeLogic.sectorCount(2048))
    }

    @Test
    fun `4K 卡后半段是 16 块的大扇区`() {
        assertEquals(4, ClassicProbeLogic.blocksInSector(0, ClassicProbeLogic.SIZE_4K))
        assertEquals(4, ClassicProbeLogic.blocksInSector(31, ClassicProbeLogic.SIZE_4K))
        assertEquals(16, ClassicProbeLogic.blocksInSector(32, ClassicProbeLogic.SIZE_4K))
        assertEquals(16, ClassicProbeLogic.blocksInSector(39, ClassicProbeLogic.SIZE_4K))
        // 1K 一律 4 块
        assertEquals(4, ClassicProbeLogic.blocksInSector(15, ClassicProbeLogic.SIZE_1K))
    }

    /**
     * 1K：16 扇区 × 4 块 = 64 块，每扇区跳过尾块 ⇒ 每扇区 3 个待读块 = 48。
     * 绝对块号必须是**累加**出来的，写成 `sector * 4 + i` 就等着读错扇区。
     */
    @Test
    fun `1K 的待读块跳过尾块且块号连续`() {
        val plan = ClassicProbeLogic.plan(ClassicProbeLogic.SIZE_1K)
        assertEquals(48, plan.size)

        // 扇区 0：读块 0/1/2，跳过块 3（尾块）
        assertEquals(listOf(0, 1, 2), plan.filter { it.sector == 0 }.map { it.block })
        // 扇区 1：从块 4 开始，尾块 7 跳过
        assertEquals(listOf(4, 5, 6), plan.filter { it.sector == 1 }.map { it.block })
        // 最后一个扇区（15）:块 60/61/62
        assertEquals(listOf(60, 61, 62), plan.filter { it.sector == 15 }.map { it.block })

        assertTrue(plan.all { it.block < 64 })
        assertEquals(0, plan.first().indexInSector)
        assertEquals(2, plan.last().indexInSector)
    }

    /** 4K：前 32 扇区各 4 块、后 8 扇区各 16 块 —— 块号必须跨过那段不一致继续累加。 */
    @Test
    fun `4K 的块号跨过大扇区继续累加`() {
        val plan = ClassicProbeLogic.plan(ClassicProbeLogic.SIZE_4K)
        // 前 32 扇区：(4-1) × 32 = 96
        // 后 8 扇区：(16-1) × 8 = 120
        assertEquals(216, plan.size)

        // 扇区 32 是大扇区的第一个，起点 = 32 × 4 = 128
        val big = plan.filter { it.sector == 32 }
        assertEquals(15, big.size)
        assertEquals(128, big.first().block)
        assertEquals(142, big.last().block)

        assertTrue(plan.all { it.block < 256 })
    }

    @Test
    fun `认不出容量时不生成任何待读块`() {
        assertTrue(ClassicProbeLogic.plan(0).isEmpty())
        assertTrue(ClassicProbeLogic.plan(2048).isEmpty())
    }

    /** 给 4K 卡减速：`maxSector` 之外的扇区一个都不排。 */
    @Test
    fun `maxSector 能截断扫描范围`() {
        assertEquals(12, ClassicProbeLogic.plan(ClassicProbeLogic.SIZE_1K, maxSector = 4).size)
        assertEquals(3, ClassicProbeLogic.plan(ClassicProbeLogic.SIZE_1K, maxSector = 1).size)
    }

    // ------------------------------------------------------------ 密钥

    @Test
    fun `候选密钥都是 6 字节的合法十六进制`() {
        assertTrue(ClassicProbeLogic.DEFAULT_KEYS.isNotEmpty())
        ClassicProbeLogic.DEFAULT_KEYS.forEach { key ->
            assertEquals("${key.name} 的密钥必须是 6 字节", 12, key.hex.length)
            assertEquals(ClassicProbeLogic.KEY_LENGTH, CardProbeLogic.parseHex(key.hex).size)
        }
    }

    /** 出厂默认排第一：命中越早，贴卡时间越短。 */
    @Test
    fun `出厂默认密钥排在最前`() {
        assertEquals("FFFFFFFFFFFF", ClassicProbeLogic.DEFAULT_KEYS.first().hex)
    }

    // ------------------------------------------------------------ 块的读法

    /** 学号在卡里最可能的存法就是 BCD，这一栏是**最该先看的一栏**。 */
    @Test
    fun `BCD 块直接拼出数字串`() {
        val block = CardProbeLogic.parseHex("11202413550000000000000000000000")
        val view = ClassicProbeLogic.decode(block)
        assertEquals("1120241355", view.bcdDigits?.trimEnd('0'))
        // 整块 32 个半字节，全拼出来
        assertEquals("11202413550000000000000000000000", view.bcdDigits)
        // 高位是 0x11 之类不可打印字符，ASCII 一栏不成立
        assertNull(view.ascii)
    }

    @Test
    fun `含 A 到 F 半字节就不是 BCD`() {
        val view = ClassicProbeLogic.decode(CardProbeLogic.parseHex("11A02413550000000000000000000000"))
        assertNull(view.bcdDigits)
    }

    @Test
    fun `全可打印时给出 ASCII`() {
        val view = ClassicProbeLogic.decode("BIT101 SEAT   ".toByteArray(Charsets.US_ASCII))
        assertEquals("BIT101 SEAT   ", view.ascii)
        // 空格是 0x20，半字节是 2 和 0，都 ≤ 9 ⇒ BCD 也成立（这是对的，不猜哪一种）
        assertTrue(view.bcdDigits != null)
    }

    @Test
    fun `hex 一栏永远有且是大写空格分隔`() {
        val view = ClassicProbeLogic.decode(byteArrayOf(0x00, 0xFF.toByte()))
        assertEquals("00 FF", view.hex)
        assertNull(view.ascii)
        assertNull(view.bcdDigits)
    }

    @Test
    fun `全零块要能被识别出来以便折叠`() {
        assertTrue(ClassicProbeLogic.isBlank(ByteArray(16)))
        assertFalse(ClassicProbeLogic.isBlank(ByteArray(16).also { it[0] = 1 }))
        // 空数组算空 —— 免得 UI 去渲染一个不存在的块
        assertTrue(ClassicProbeLogic.isBlank(ByteArray(0)))
    }

    // ------------------------------------------------------------ 与学号扫描的衔接

    /**
     * 端到端串一遍：块里存 BCD 学号 ⇒ `StudentIdScan` 必须能在这个块里认出来。
     * 这是整条 Classic 路线存在的理由，断开就等于白做。
     */
    @Test
    fun `BCD 学号能被学号扫描认出来`() {
        val block = CardProbeLogic.parseHex("1120241355FFFFFFFFFFFFFFFFFFFFFF")
        assertNull(ClassicProbeLogic.decode(block).ascii)
        assertEquals("BCD 形式，偏移 0", StudentIdScan.find(block, "1120241355"))
        assertNull(StudentIdScan.find(block, "1120241356"))
    }
}
