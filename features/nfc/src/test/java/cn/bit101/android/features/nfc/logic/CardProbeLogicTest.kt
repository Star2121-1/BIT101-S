package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * ⚠️ READ BINARY 的 P1 = `0x80 | SFI`（最高位置 1，低 5 位是 SFI），
     * **不是** `(SFI<<3)|0x04` —— 后者是 READ RECORD 的 P2 编码，上一版就是抄混了，
     * 结果扫什么都是「找不到」。这条断言专门钉住这个坑。
     */
    @Test
    fun `READ BINARY 的 P1 是 80 或上 SFI`() {
        // SFI=1 → P1 = 0x80 | 1 = 0x81
        assertEquals("00 B0 81 00 20", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 1, length = 32)))
        // SFI=30 → P1 = 0x80 | 30 = 0x9E
        assertEquals("00 B0 9E 00 10", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 30, length = 16)))
        // 偏移量进 P2
        assertEquals("00 B0 81 10 10", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 1, offset = 16, length = 16)))
    }

    @Test
    fun `长度 256 在 Le 位置编码为 0`() {
        assertEquals("00 B0 81 00 00", CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 1, length = 256)))
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

    /**
     * 第一轮的顺序是**真卡实测**排出来的，别随手整理：
     * ① SELECT MF 拿卡自称的名字；② SELECT PSE（这张卡认的是 1PAY，不是 2PAY）；
     * ③ 紧跟三条 READ RECORD —— 它们依赖「当前选中目录」，中间不能被别的 SELECT 打断；
     * ④ 最后才拿 PPSE 做对照。
     */
    @Test
    fun `第一轮先 MF 再 PSE 再读记录 最后才是 PPSE 对照`() {
        val steps = CardProbeLogic.firstRound()
        assertEquals(6, steps.size)
        assertTrue(steps[0].label.contains("主文件"))
        assertTrue(steps[1].label.contains("1PAY"))
        assertTrue(steps[2].label.contains("READ RECORD"))
        assertTrue(steps[4].label.contains("READ RECORD"))
        assertTrue(steps[5].label.contains("2PAY"))
    }

    /** PSE 与 PPSE 只差一个字符（1/2），写错就是 6A82。用 ASCII 反查钉住。 */
    @Test
    fun `PSE 与 PPSE 的 AID 分别是 1PAY 与 2PAY`() {
        assertEquals("1PAY.SYS.DDF01", asciiOf(CardProbeLogic.parseHex(CardProbeLogic.PSE_AID)))
        assertEquals("2PAY.SYS.DDF01", asciiOf(CardProbeLogic.parseHex(CardProbeLogic.PPSE_AID)))
    }

    @Test
    fun `READ RECORD 把 SFI 编进 P2 且记录号进 P1`() {
        // 记录 1、SFI=1、整条记录（Le=00）→ 00 B2 01 0C 00
        assertEquals("00 B2 01 0C 00", CardProbeLogic.toHex(CardProbeLogic.readRecord(record = 1, sfi = 1)))
        // 记录 3、SFI=30 → P2 = 11110_100 = F4
        assertEquals("00 B2 03 F4 10", CardProbeLogic.toHex(CardProbeLogic.readRecord(record = 3, sfi = 30, length = 16)))
    }

    @Test
    fun `READ RECORD 的越界参数早失败`() {
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readRecord(record = 0, sfi = 1) }
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readRecord(record = 1, sfi = 31) }
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readRecord(record = 1, sfi = 1, length = 257) }
    }

    // ------------------------------------------------------------ TLV（真卡向量）

    /**
     * 北理工校园卡 `SELECT MF` 的真实回答：
     * `6F 15 84 0E 31 50 41 59 2E 53 59 53 2E 44 44 46 30 31 A5 03 88 01 01`。
     *
     * 这条断言的价值：卡自称 `1PAY.SYS.DDF01`、目录在 SFI=1 ——
     * 全是**从这一串字节里读出来的**，不是猜的。下一轮探测就是照这两个值往下走。
     */
    @Test
    fun `真卡 FCI 能解出卡名与目录 SFI`() {
        val data = CardProbeLogic.parseHex("6F15840E315041592E5359532E4444463031A503880101")
        val tlvs = CardProbeLogic.parseTlvs(data)

        val name = tlvs.first { it.tag == "84" }.value
        assertEquals("1PAY.SYS.DDF01", asciiOf(name))

        val sfi = tlvs.first { it.tag == "88" }.value
        assertEquals("01", CardProbeLogic.toHexCompact(sfi))
    }

    /** 构造型（0x20 置位）必须递归，否则埋在 `A5` 里的 `88` 就拿不到。 */
    @Test
    fun `嵌套在构造型里的 tag 也要解析出来`() {
        // 6F(5) { A5(3) { 88 01 01 } } —— 外层长度必须把内层**全部**算进去
        val data = CardProbeLogic.parseHex("6F05A503880101")
        val tags = CardProbeLogic.parseTlvs(data).map { it.tag }
        assertTrue("应递归出 88：$tags", tags.contains("88"))
        assertTrue(CardProbeLogic.parseTlvs(data).first { it.tag == "A5" }.constructed)
        assertFalse(CardProbeLogic.parseTlvs(data).first { it.tag == "88" }.constructed)
    }

    /** 长格式长度（81 xx / 82 xx xx）也要能读，否则大记录会整条解析失败。 */
    @Test
    fun `长格式长度能解析`() {
        // 70 81 03 84 01 41  → 长度 0x81 后面 1 字节 = 3
        val tlvs = CardProbeLogic.parseTlvs(CardProbeLogic.parseHex("708103840141"))
        assertEquals(listOf("70", "84"), tlvs.map { it.tag })
    }

    @Test
    fun `挑 AID 只收 4F 且长度合规`() {
        // 61(10) { 4F 05 A0 00 00 00 01（合法 AID）, 4F 01 FF（过短，应被筛掉） }
        val tlvs = CardProbeLogic.parseTlvs(CardProbeLogic.parseHex("610A4F05A0000000014F01FF"))
        val aids = CardProbeLogic.aidsOf(tlvs)
        assertEquals(1, aids.size)
        assertEquals("A000000001", CardProbeLogic.toHexCompact(aids.first()))
    }

    /** 字节被截断/不合法时，宁可少解几条也不要抛异常 —— 探测是在真卡上跑的。 */
    @Test
    fun `残缺 TLV 不抛异常`() {
        CardProbeLogic.parseTlvs(CardProbeLogic.parseHex("6F05"))
        CardProbeLogic.parseTlvs(CardProbeLogic.parseHex("6F83FF01"))
        CardProbeLogic.parseTlvs(ByteArray(0))
    }

    private fun asciiOf(bytes: ByteArray) = String(bytes, Charsets.US_ASCII)

    /** 第二轮是探索性的，但每一条都必须过只读闸门 —— 一次误写就是事故。 */
    @Test
    fun `SFI 扫描的每一条都是只读的`() {
        val steps = CardProbeLogic.sfiScan()
        assertEquals(30, steps.size)
        assertTrue(steps.all { CardProbeLogic.isReadOnly(it.apdu) })
        assertEquals("READ BINARY SFI=1", steps.first().label)
        assertEquals("READ BINARY SFI=30", steps.last().label)
    }

    /**
     * SFI 扫描的每条都必须**记住自己扫的是哪个 SFI**。
     *
     * 为什么这条要有测试：`READ BINARY SFI=n` 回 `6981`（记录文件）时要改发
     * `READ RECORD`，而那时「当前 EF」根本不存在 —— 这条命令从没 SELECT 过任何文件。
     * 不带 SFI 编号，发出去的记录命令就是在读**别人的**文件。
     * 真卡上 `SFI=2` 与 `SFI=24` 两个记录文件都会因此拿不到。
     */
    @Test
    fun `SFI 扫描的每条都带上了自己的 SFI 编号`() {
        assertEquals(listOf(1, 2, 3), CardProbeLogic.sfiScan(1..3).map { it.sfi })
        // 反过来也别串台：按 FID 扫的那几条不该被安上 SFI
        assertTrue(CardProbeLogic.fidScan(0x0010..0x0012).all { it.sfi == null })
    }

    /**
     * 命中的 SFI 要**沿文件往下读** —— 而且**不因首段全零就停下**。
     *
     * ⚠️ 这一条在第十次实测被改过：原先 `sfiScan` 带了个「首段全零就不再往下读」
     * 的开关，而 `fidScan` 没有 ⇒ 目录内 `0008` 与 `SFI=8` 是**同一个文件**，
     * 却走 FID 路读出 48 字节、走 SFI 路只有 32 字节。
     * 当时还有一条 `assertTrue(step.followUpIfData)` 把这个不一致**钉成了期望值** ——
     * 与「把错编码写成期望值」是同一类错误：测试在替 bug 背书。
     */
    @Test
    fun `SFI 扫描命中后沿文件往下读`() {
        val step = CardProbeLogic.sfiScan(1..1).single()

        assertEquals("SFI=1 第 2 段", step.followUpLabel)
        // 第 2 段是同一 SFI 的 offset 0x20，第 3 段是 0x40 —— P1 仍是 0x80|SFI
        assertEquals("00 B0 81 20 20", CardProbeLogic.toHex(step.followUp!!))
        assertEquals(listOf("00 B0 81 40 20"), step.followUpMore.map { CardProbeLogic.toHex(it) })

        // 续读的每一条也都要过只读闸门
        assertTrue(CardProbeLogic.isReadOnly(step.followUp!!))
        assertTrue(step.followUpMore.all { CardProbeLogic.isReadOnly(it) })
    }

    /**
     * **两条路必须对同一个文件读到同样的深度** —— 第十次实测就栽在它们不一致上。
     *
     * 真卡上目录内的 `0008` 与 `SFI=8` 是同一份数据的两种编号方式
     * （实测 `0005`/`0006`/`0007` 与 `SFI=5/6/7` 内容完全一致）。
     * 一边读到 offset 0x40、另一边停在 0x20，诊断页就会出现两份互相打架的说法。
     *
     * 这条守的不是「各读几段」这个数字，而是**两边最后一跳落在同一处**。
     */
    @Test
    fun `SFI 路与 FID 路的续读深度一致`() {
        val bySfi = CardProbeLogic.sfiScan(1..1).single()
        val byFid = CardProbeLogic.fidScan(0x0001..0x0001).single()

        assertEquals("00 B0 81 40 20", CardProbeLogic.toHex(bySfi.followUpMore.last()))
        assertEquals("00 B0 00 40 20", CardProbeLogic.toHex(byFid.followUpMore.last()))
        // 两边都不能退化成「读一段就收工」
        assertTrue(bySfi.followUpMore.isNotEmpty())
        assertTrue(byFid.followUpMore.isNotEmpty())
    }

    /**
     * ⚠️ 真卡实测（第五次）的教训：**`6981` 只在「记录文件」这一种情况下回**。
     *
     * `6986`（当前选中的是目录）看起来也是「命令不对」，但对它发 `READ RECORD`
     * 是**纯浪费** —— 真卡目录 `0010` 上就这么白白发了 5 条，全回 `6986`。
     * 目录的正确处置是「进去」（`looksLikeDirectory`），两条路必须分开。
     */
    @Test
    fun `只有 6981 触发改读记录 6986 不触发`() {
        assertTrue(CardProbeLogic.shouldTryRecords("6981"))
        // 这两条曾经被混在一个判断里，结果目录被当成记录文件白读 5 次
        assertFalse(CardProbeLogic.shouldTryRecords("6986"))
        assertFalse(CardProbeLogic.shouldTryRecords("6A82"))
        assertFalse(CardProbeLogic.shouldTryRecords("9000"))
        assertFalse(CardProbeLogic.shouldTryRecords("6982"))

        // 反过来，目录那一支只认6986
        assertTrue(CardProbeLogic.looksLikeDirectory("6986"))
        assertFalse(CardProbeLogic.looksLikeDirectory("6981"))
    }

    /**
     * ⚠️⚠️ **主命令自己**回 `6981` 时，`SFI` 扫描的步骤必须带得上回退所需的一切。
     *
     * 第六次实测的 bug：`runStep` 里的回退判断原本**只挂在 followUp 之后**，
     * 而 followUp 只在首段 `9000` 时才发。`READ BINARY SFI=2` 回的正是 `6981` ⇒
     * 首段不成功 ⇒ 提前 return ⇒ `shouldTryRecords` **永远够不着**。
     * 表现：dump 里 `SFI=2` 后面**一条回退命令都没有**。
     *
     * 这条测试守不住接线层的控制流（那要跑真机），但它守得住
     * **「带 SFI 的步骤有没有把回退需要的信息带全」**——
     * 接线层改成「先判主命令、再判 followUp」之后，两处都能拿到。
     */
    @Test
    fun `SFI 步骤带齐回退所需的 SFI 编号`() {
        val step = CardProbeLogic.sfiScan(1..2).first { it.sfi == 2 }
        assertEquals(2, step.sfi)
        // 回退要发READ RECORD，而 P2 必须按这个 SFI 编码成 (2<<3)|0x04 = 0x14。
        // 少了 sfi 就只能发 P2=0x04（按当前 EF），实测那 5 条全回 6981。
        assertEquals(
            "00 B2 01 14 00",
            CardProbeLogic.toHex(CardProbeLogic.readRecord(record = 1, sfi = step.sfi!!)),
        )
        // 两种寻址方式都在只读闸门内
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readRecord(record = 1, sfi = 2)))
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readRecordCurrentEf(1)))
        // 两条命令确实不同 —— 混了就会读错文件
        assertNotEquals(
            CardProbeLogic.toHex(CardProbeLogic.readRecordCurrentEf(1)),
            CardProbeLogic.toHex(CardProbeLogic.readRecord(record = 1, sfi = 2)),
        )
    }

    /**
     * 目录内的 FID 扫描也要带够后续动作 —— 这一条是被真卡实测逼出来的。
     *
     * 第五次实测：目录下钻的代码**自己重写了一遍发命令的循环**，
     * 于是漏掉了「`6981` → 改读记录」这条回退。真卡目录 `0010` 内的 `0002`
     *（等价于 `SFI=2`）也是定长记录文件，回 `6981` —— 记录一条都没读到。
     *
     * 这条测试守不住「接线层有没有重写循环」（那要跑真机），
     * 但它守得住**扫描步骤本身带没带 `followUp`**：
     * 接线层一旦改成调`runStep`，命中后的连读与回退就自动都有了。
     */
    @Test
    fun `FID 扫描的每一条都带 followUp 以便命中后接着读`() {
        val step = CardProbeLogic.fidScan(0x0002..0x0002).single()

        assertEquals(2, step.fid)
        assertTrue(CardProbeLogic.isReadOnly(step.apdu))
        // 选中之后要读的那一条必须在（这正是目录下钻漏掉的东西）
        assertTrue(step.followUp != null)
        assertTrue(CardProbeLogic.isReadOnly(step.followUp!!))
    }

    /**
     * 「不带 SFI」的 READ BINARY：P1/P2 是**偏移量的高/低字节**，不是 SFI。
     *
     * ⚠️⚠️ 这条测试以前把**错的编码写成了期望值**（`offset=16` 期望 `00 B0 10 00 10`），
     * 于是 bug 顺顺当当地过了全部测试。教训：**测试也得对着真卡实测核对** ——
     * 一条自己都没验证过的期望值，等于给 bug 发了通行证。
     *
     * 真卡实证（第六次）：目录内`0006`
     * - 走 FID 路续读（`00 B0 20 00 20`）⇒ **`6B00`** 偏移超界
     * - 走 `SFI=6` 路续读（`00 B0 86 20 20`）⇒ **`9000`**，读到了东西
     *
     * 同一个文件、两条路、不同结果 ⇒ 只能是命令发错了。错的形态就是
     * 「offset 全进了 P1、P2 恒为 0」—— 于是本意 offset=32 发成了 offset=0x2000=8192。
     */
    @Test
    fun `READ BINARY 不带 SFI 时 P1P2 是偏移量`() {
        assertEquals("00 B0 00 00 20", CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain()))
        // ✅ 正确的编码：P1=高字节、P2=低字节
        assertEquals("00 B0 00 10 10", CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain(offset = 16, length = 16)))
        assertEquals("00 B0 00 20 20", CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain(offset = 32, length = 32)))
        assertEquals("00 B0 01 00 20", CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain(offset = 256, length = 32)))
        // ⚠️ 绝不能出现「offset 全进 P1、P2 恒为 0」—— 那会把 offset=32 发成 8192
        assertFalse(
            CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain(offset = 32))
                .startsWith("00 B0 20 00"),
        )
        // 越界的偏移要早失败，别等到真卡上回 6B00 才发现
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readBinaryPlain(offset = 0x8000) }
    }

    /**
     * 两条路的偏移量编码**完全不同**，必须分别断言 —— 这是第四次同型 bug 的来源。
     *
     * | | P1 | P2 |
     * |---|---|---|
     * | 带 SFI [readBinary] | `0x80 \| SFI` | **offset**（单字节） |
     * | 不带 SFI [readBinaryPlain] | offset 高字节 | offset 低字节 |
     *
     * 真卡实证：`SFI=6` 读 offset 32 发的是 `00 B0 86 20 20`（P1 有SFI、P2 是 0x20），
     * 而不是 `00 B0 00 20 20`。两者含义完全不同。
     */
    @Test
    fun `带 SFI 与不带 SFI 的偏移量编码互不通用`() {
        val offset = 32
        val withSfi = CardProbeLogic.toHex(CardProbeLogic.readBinary(sfi = 6, offset = offset, length = 32))
        val plain = CardProbeLogic.toHex(CardProbeLogic.readBinaryPlain(offset = offset, length = 32))

        assertEquals("00 B0 86 20 20", withSfi)
        assertEquals("00 B0 00 20 20", plain)
        // P1 完全不同：一个带 SFI 位，一个不带
        assertNotEquals(withSfi.substring(6, 8), plain.substring(6, 8))
        // 但都过只读闸门
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readBinary(sfi = 6, offset = offset)))
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readBinaryPlain(offset = offset)))
    }

    /**
     * 按 FID 选文件。
     *
     * 这条是真卡上**唯一**能发现「MF 下到底有哪些文件」的手段：
     * AID 那条路实测只摸到一个借来的支付目录名，文件里的东西一概看不到。
     */
    @Test
    fun `SELECT 按文件标识`() {
        assertEquals("00 A4 02 00 02 00 01", CardProbeLogic.toHex(CardProbeLogic.selectByFileId(0x0001)))
        assertEquals("00 A4 02 00 02 00 15", CardProbeLogic.toHex(CardProbeLogic.selectByFileId(0x0015)))
        assertEquals("00 A4 02 00 02 10 01", CardProbeLogic.toHex(CardProbeLogic.selectByFileId(0x1001)))
        assertEquals("00 A4 02 00 02 FF FF", CardProbeLogic.toHex(CardProbeLogic.selectByFileId(0xFFFF)))
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.selectByFileId(0x10000) }
        // 与按 AID 选（P1=04）必须区分开：两者选的不是同一类东西
        assertNotEquals(
            CardProbeLogic.toHex(CardProbeLogic.selectByFileId(0x3F00)),
            CardProbeLogic.toHex(CardProbeLogic.selectMasterFile()),
        )
    }

    /**
     * `6C xx` 的长度协商 —— 真卡实测栽过的那一次。
     *
     * 校园卡 `READ 文件 0015` 回 `6C1E`（这个文件只有 30 字节）。不按它说的重发，
     * 文件里一个字节都拿不到，而我们还会以为「读不出来」。
     */
    @Test
    fun `卡回 6C 时 Le 要按它说的换掉`() {
        val r = CardProbeLogic.parseResponse(CardProbeLogic.parseHex("6C1E"))
        assertEquals("6C1E", r.sw)
        assertEquals(0x1E, r.correctLe!!)
        assertFalse(r.success)
        assertNull(r.swText) // 6C 是长度协商，不是错误码表里的那个

        // 按它说的重发：只动最后一个字节
        assertEquals(
            "00 B0 81 00 1E",
            CardProbeLogic.toHex(CardProbeLogic.withLe(CardProbeLogic.readBinary(sfi = 1), 0x1E)),
        )
        // 00 按规范代表 256，不是 0
        assertEquals(256, CardProbeLogic.parseResponse(CardProbeLogic.parseHex("6C00")).correctLe!!)
    }

    /** ⚠️ 只许对读指令协商 Le —— 别的命令末尾那个字节根本就不是 Le。 */
    @Test
    fun `Le 协商不许碰非读指令`() {
        assertThrows(IllegalArgumentException::class.java) {
            CardProbeLogic.withLe(CardProbeLogic.selectByFileId(0x0010), 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CardProbeLogic.withLe(CardProbeLogic.selectMasterFile(), 30)
        }
        // 读指令一律放行，且指令字节没被动过
        val fixed = CardProbeLogic.withLe(CardProbeLogic.readRecordCurrentEf(1), 30)
        assertEquals("00 B2 01 04 1E", CardProbeLogic.toHex(fixed))
    }

    @Test
    fun `61 表示还要取一次且 0 代表 256`() {
        val r = CardProbeLogic.parseResponse(CardProbeLogic.parseHex("6A5BF5E66110"))
        assertEquals("6110", r.sw)
        assertTrue(r.hasMore)
        assertEquals("6A 5B F5 E6", CardProbeLogic.toHex(r.data))
        assertEquals(0x10, CardProbeLogic.remainingOf("6110")!!)
        assertEquals(256, CardProbeLogic.remainingOf("6100")!!)
        assertNull(CardProbeLogic.remainingOf("9000"))
        assertEquals("00 C0 00 00 10", CardProbeLogic.toHex(CardProbeLogic.getResponse(0x10)))
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.getResponse(0) }
    }

    /** 定长记录文件不吃 READ BINARY（真卡 0018 回 6981），要改走「当前文件的第几条记录」。 */
    @Test
    fun `记录文件走当前 EF 的 READ RECORD`() {
        assertEquals("00 B2 01 04 00", CardProbeLogic.toHex(CardProbeLogic.readRecordCurrentEf(1)))
        assertEquals("00 B2 03 04 00", CardProbeLogic.toHex(CardProbeLogic.readRecordCurrentEf(3)))
        // 与按 SFI 的那条必须区分开：P2 高 3 位为 0 才是「当前文件」
        assertNotEquals(
            CardProbeLogic.toHex(CardProbeLogic.readRecordCurrentEf(1)),
            CardProbeLogic.toHex(CardProbeLogic.readRecord(record = 1, sfi = 1)),
        )
        assertTrue(CardProbeLogic.isReadOnly(CardProbeLogic.readRecordCurrentEf(1)))
        assertThrows(IllegalArgumentException::class.java) { CardProbeLogic.readRecordCurrentEf(0) }
    }

    /** `6981` / `6986` 的说明必须是「命令用错了」，不能写成「没这个文件」。 */
    @Test
    fun `文件在但命令不对的状态字要说清楚`() {
        assertTrue(CardProbeLogic.statusText("6981")!!.contains("READ RECORD"))
        assertTrue(CardProbeLogic.statusText("6986")!!.contains("目录"))
    }

    /**
     * 该不该改读记录 / 该不该进目录。
     *
     * ⚠️ 这两条判据必须**互斥**：`6981` 是**定长记录文件**（改 READ RECORD 就好），
     * `6986` 是**目录**（要进去再扫一层）。混成一个就会出现「对目录发 READ RECORD」
     * 这种白费力气的事 —— 真卡 `0010`（目录）上就这么白发了 5 条，全回 `6986`。
     * 真卡 `0018` 回 `6981`、`0010` 回 `6986`，正好各占一边。
     */
    @Test
    fun `记录文件与目录要用不同的判据`() {
        assertTrue(CardProbeLogic.shouldTryRecords("6981"))
        assertTrue(CardProbeLogic.looksLikeDirectory("6986"))
        // ⚠️ 下面两条是这次抓到的 bug 的回归线：6986 归目录，**不再**触发改读记录。
        assertFalse(CardProbeLogic.shouldTryRecords("6986"))
        assertFalse(CardProbeLogic.looksLikeDirectory("6981"))
        // 正常/找不到文件都不该触发任何回退
        for (sw in listOf("9000", "6A82", "6B00", "6C1E", "6982")) {
            assertFalse("$sw 不该触发改读记录", CardProbeLogic.shouldTryRecords(sw))
            assertFalse("$sw 不该被当成目录", CardProbeLogic.looksLikeDirectory(sw))
        }
    }

    /** FID 扫描的每一步都要带上文件号 —— 进目录时得靠它重新 SELECT。 */
    @Test
    fun `FID 扫描的每步都记得自己是哪个文件号`() {
        val steps = CardProbeLogic.fidScan(0x0001..0x0012)
        assertEquals(18, steps.size)
        assertEquals(0x0001, steps.first().fid)
        assertEquals(0x0010, steps[15].fid)
        assertEquals(0x0012, steps.last().fid)
        assertEquals("SELECT 文件 0010", steps[15].label)
        // 不是按文件号选的那些步骤不该有 fid（SFI 扫描、SELECT MF 等）
        assertTrue(CardProbeLogic.sfiScan(1..3).all { it.fid == null })
        assertTrue(CardProbeLogic.firstRound().all { it.fid == null })
    }

    /** GBK 文本解码 —— 真卡 `0016` 文件里就是姓名，解不出来等于白读。 */
    @Test
    fun `GBK 文本能被认出来`() {
        assertEquals("高天翔", CardProbeLogic.decodeText(CardProbeLogic.parseHex("0000B8DFCCECCFE80000")))
        // ASCII 走同一条路（GBK 是 ASCII 超集）
        assertEquals("BIT101", CardProbeLogic.decodeText("BIT101".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `二进制乱码不许当成文本`() {
        // 含控制字节：宁可给 null，也不要端一坨乱码给用户
        assertNull(CardProbeLogic.decodeText(CardProbeLogic.parseHex("6F18 8410 D156".replace(" ", ""))))
        // 全是零填充 ⇒ 没有内容
        assertNull(CardProbeLogic.decodeText(ByteArray(16)))
        assertNull(CardProbeLogic.decodeText(ByteArray(0)))
        // 纯 GBK 高位字节但落在 GBK 空洞里 ⇒ 解出替换字符 ⇒ 判否
        assertNull(CardProbeLogic.decodeText(CardProbeLogic.parseHex("FFFEFFFE")))
    }

    /** FID 扫描：每条都带一个「选中了就读一段」的后续命令，且全部过只读闸门。 */
    @Test
    fun `FID 扫描带后续读且全只读`() {
        val steps = CardProbeLogic.fidScan(0x0001..0x0005)
        assertEquals(5, steps.size)
        assertTrue(steps.all { CardProbeLogic.isReadOnly(it.apdu) })
        assertTrue(steps.all { it.followUp != null && CardProbeLogic.isReadOnly(it.followUp!!) })
        assertEquals("SELECT 文件 0001", steps.first().label)
        assertEquals("READ 文件 0001 前 32 字节", steps.first().followUpLabel)
        assertEquals("00 B0 00 00 20", CardProbeLogic.toHex(steps.first().followUp!!))
    }

    // ---------------------------------------------------------------- 快速读

    /**
     * 快速读的范围必须**罩住真卡实测有数据的那一段**（`0010`~`001B`）。
     *
     * 这是它唯一的存在理由：日常读卡只想知道「这是谁的卡」，
     * 而姓名与学号就在 `0016`。范围一旦收过头，功能就直接失效了。
     */
    @Test
    fun `快速读的范围覆盖真卡有数据的文件段`() {
        val steps = CardProbeLogic.quickScan()

        // 第一条先把主文件选回来：否则读的是上一轮残留的上下文，结果不可复现
        assertEquals("SELECT 主文件 3F00（快速读起点）", steps.first().label)

        val fids = steps.mapNotNull { it.fid }
        listOf(0x0010, 0x0011, 0x0015, 0x0016, 0x0018, 0x001A, 0x001B).forEach { fid ->
            assertTrue("应含 %04X".format(fid), fid in fids)
        }
    }

    /** 快速读的每一条都必须过只读闸门 —— 一次误写就是事故。 */
    @Test
    fun `快速读的每条命令都是只读的`() {
        val steps = CardProbeLogic.quickScan()

        assertTrue(steps.all { CardProbeLogic.isReadOnly(it.apdu) })
        assertTrue(steps.all { it.followUp == null || CardProbeLogic.isReadOnly(it.followUp) })
        assertTrue(steps.all { step -> step.followUpMore.all { CardProbeLogic.isReadOnly(it) } })
    }

    /**
     * 快速读**必须比深度扫描短得多** —— 它存在的唯一理由就是快。
     *
     * 这一条防的是「范围被慢慢加宽」：谁都可能觉得「再多扫几个文件也不慢」，
     * 加着加着就退化成那个要按住好几秒的深度轮了。
     */
    @Test
    fun `快速读的命令数远少于深度扫描`() {
        val quick = CardProbeLogic.quickScan().size
        val deep = CardProbeLogic.sfiScan().size + CardProbeLogic.fidScan().size

        assertTrue("快速读 $quick 条，深度 $deep 条", quick * 5 < deep)
    }

    /** 每条都要记住自己试的是哪个文件号（命中后要能认回来）。 */
    @Test
    fun `快速读的每条都带自己的文件号`() {
        val small = CardProbeLogic.quickScan(0x0001..0x0003)
        assertEquals(listOf(0x0001, 0x0002, 0x0003), small.drop(1).map { it.fid })
        // 首条是 SELECT MF，不带文件号
        assertNull(small.first().fid)
    }

    /**
     * 快速读每条只读**两段**（够拿到 `0016` 里的姓名与学号）。
     *
     * ⚠️ 这与深度扫描的「三段」**是有意不同**的，不是漏了：
     * 那边宁可多读也不漏（要翻遍未知的卡），这边够用即止 ——
     * 真卡 `0016` 的第三段回的是 `6B00`（已经到文件末尾），多读那一条纯属白等。
     */
    @Test
    fun `快速读每条只读两段`() {
        val step = CardProbeLogic.quickScan(0x0016..0x0016).last()

        assertEquals(0x0016, step.fid)
        assertTrue(step.followUp != null)
        assertEquals(1, step.followUpMore.size)
        assertEquals("00 B0 00 20 20", CardProbeLogic.toHex(step.followUpMore.single()))
    }
}
