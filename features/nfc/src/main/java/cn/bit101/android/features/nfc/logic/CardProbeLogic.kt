package cn.bit101.android.features.nfc.logic

/**
 * CPU 卡的**只读**探测生成器。
 *
 * ## ⚠️⚠️ 这个文件的第一条纪律：只读
 *
 * 校园卡是**别人的资产**，一次错误的写操作（`UPDATE BINARY`/`UPDATE RECORD`/
 * 改 PIN 重试计数）就可能把卡弄废，甚至会触发卡片自锁。
 * 所以本模块**不存在任何写命令的构造入口**，并且在真的发出去之前还得再过一道
 * [isReadOnly] 闸门。新增任何命令前先问：「这条发出去，卡还能不能恢复到原样？」
 * 答不上来就别加。
 *
 * ## 为什么此刻要写它
 *
 * 眼下这台开发机（ZTE NP05J）**没有 NFC 硬件**，没法真刀真枪试 ——
 * 但「生成什么字节、怎么解析回答」是**纯确定性**的，一点都不依赖硬件。
 * 把这部分先钉住，等有机器时剩下的就只有「接线」那一层薄壳。
 *
 * ## AID 怎么来
 *
 * 除了 PPSE 是标准规定的名字，其余候选 AID 都是**照发放规则推出来的**，
 * 没有在本校卡上验证过。UI 展示时必须说清这是「候选」，不能写成结论 ——
 * 真正的正确做法是把 [SELECT_PPSE] 打下去，读它返回的 FCI 里**卡自己报的** AID 列表。
 */
internal object CardProbeLogic {

    /**
     * 允许发出的 ISO 7816-4 指令（INS 字节）。
     *
     * 为什么用**允许清单**而不是「写指令黑名单」：黑名单要穷举所有可能出错的指令，
     * 漏一条就是事故；允许清单漏一条顶多是少读点东西。
     */
    private val READ_ONLY_INS = setOf(
        0xA4, // SELECT：选文件 / 选应用，不改变卡内容
        0xB0, // READ BINARY：读二进制文件
        0xB2, // READ RECORD：读记录文件
        0xC0, // GET RESPONSE：取上次指令剩下的回答
        0xCA, // GET DATA：取卡内的数据对象
        0x84, // GET CHALLENGE：取随机数（也用来确认通道是活的）
    )

    /**
     * PPSE 的应用名（`2PAY.SYS.DDF01`）—— **非接触式**支付环境的目录入口。
     */
    const val PPSE_AID = "325041592E5359532E4444463031"

    /**
     * PSE 的应用名（`1PAY.SYS.DDF01`）—— **接触式**支付环境的目录入口。
     *
     * ⚠️ 这个常量是**真卡实测**加进来的，不是照抄标准：
     * 北理工校园卡 `SELECT MF(3F00)` 返回的 FCI 里，
     * `84 0E 31 50 41 59 2E 53 59 53 2E 44 44 46 30 31` 解出来就是 `1PAY.SYS.DDF01`，
     * 而同一张卡 SELECT `2PAY…`（PPSE）回的是 `6A82` 找不到。
     *
     * ⇒ **国内校园卡很多走的是接触式 PSE 那一套**，只试 PPSE 会一无所获。两个都试。
     */
    const val PSE_AID = "315041592E5359532E4444463031"

    /**
     * 一道**发出前的总闸门**：不是白名单里的指令，一律不许出 `IsoDep.transceive`。
     *
     * @return true = 确认这条 APDU 不改变卡内容，可以发。
     */
    fun isReadOnly(apdu: ByteArray): Boolean {
        if (apdu.size < 4) return false

        val cla = apdu[0].unsigned()
        // 只接受基本 CLA（逻辑通道 0~3）。带安全报文标记的 CLA 语义完全不同，宁可不与放行。
        if (cla !in 0..3) return false

        return apdu[1].unsigned() in READ_ONLY_INS
    }

    /**
     * `SELECT by DF name`（按 AID 选应用），P1=04、Le=00。
     *
     * @param aidHex AID 的十六进制串（长度必须是偶数、且 5~16 字节，ISO 7816-5 规定）。
     */
    fun selectByName(aidHex: String): ByteArray {
        val aid = parseHex(aidHex)
        require(aid.size in 5..16) { "AID 必须是 5~16 字节，实际 ${aid.size} 字节：$aidHex" }

        //        CLA INS P1  P2  Lc   <AID ...>            Le
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) +
            aid +
            byteArrayOf(0x00)
    }

    /** `SELECT MF`：`00 A4 00 00 02 3F 00`。取不到什么有用数据，但能确认通道通不通。 */
    fun selectMasterFile(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x00, 0x02, 0x3F.toByte(), 0x00)

    /** 按 AID 选 PPSE。卡若支持，会回答一列它自带的应用。 */
    fun selectPpse(): ByteArray = selectByName(PPSE_AID)

    /** 按 AID 选 PSE（接触式目录）。真卡实测：北理工校园卡走的是这一条。 */
    fun selectPse(): ByteArray = selectByName(PSE_AID)

    /**
     * `READ RECORD`：`00 B2 <记录号> <P2> <Le>`。
     *
     * P2 = `(SFI << 3) | 0x04` —— 与 [readBinary] 同一套 SFI 编码。
     * 目录文件（PSE/PPSE 的 EF）就是靠一条条记录把 AID 列出来的，
     * 所以想拿到「卡里有哪些应用」必须发这条，光 SELECT 是不够的。
     */
    fun readRecord(record: Int, sfi: Int, length: Int = 0): ByteArray {
        require(record in 1..254) { "记录号必须在 1~254 之间，实际 $record" }
        require(sfi in 0..30) { "SFI 必须在 0~30 之间，实际 $sfi" }
        // Le=0 表示「把整条记录都给我」，最长 256
        require(length in 0..256) { "length 必须在 0~256 之间，实际 $length" }
        val le = if (length == 256) 0x00.toByte() else length.toByte()

        return byteArrayOf(
            0x00,
            0xB2.toByte(),
            record.toByte(),
            ((sfi shl 3) or 0x04).toByte(),
            le,
        )
    }

    /**
     * `READ BINARY (with SFI)`：`00 B0 <P1> <offset> <length>`。
     *
     * ## ⚠️⚠️ P1 的编码 —— 这里踩过一次坑，改之前先看清楚
     *
     * ISO 7816-4 里两条读指令的编码**不一样**，我上一版把它们的写法搞混了：
     * - **READ BINARY**：`P1 = 0x80 | SFI`（最高位置 1 表示「低 5 位是 SFI」），
     *   `P2` 才是偏移量。
     * - **READ RECORD**（见 [readRecord]）：`P1` = 记录号，`P2 = (SFI << 3) | 0x04`。
     *
     * 上一版用 `(SFI << 3) | 0x04` 填了 READ BINARY 的 P1 —— 整整错了两条指令的编码，
     * 结果就是扫什么都是找不到。真卡实测确认：按修正后的编码才是对的路子。
     *
     * @param sfi 短文件标识，规范取值 1~30。
     * @param length 期望读出的字节数，**不能超过 256**（我们不需要 GET RESPONSE 那一层复杂度）。
     */
    fun readBinary(sfi: Int, offset: Int = 0, length: Int = 32): ByteArray {
        require(sfi in 1..30) { "SFI 必须在 1~30 之间，实际 $sfi" }
        require(offset in 0..255) { "offset 必须在 0~255 之间，实际 $offset" }
        require(length in 1..256) { "length 必须在 1~256 之间，实际 $length" }
        // 256 在 Le 位置编码为 0x00
        val le = if (length == 256) 0x00.toByte() else length.toByte()

        return byteArrayOf(
            0x00,
            0xB0.toByte(),
            (0x80 or sfi).toByte(),
            offset.toByte(),
            le,
        )
    }

    /**
     * `READ RECORD`（**当前已选中的 EF**）：`00 B2 <记录号> 04 <Le>`。
     *
     * 与 [readRecord] 的区别在 P2：那边 P2 = `(SFI << 3) | 0x04` 指定「哪个文件的第几条记录」，
     * 这边 P2 的低 5 位仍是 4（表示记录号在 P1）、高 3 位为 0（表示**就用当前选中的文件**）。
     *
     * ## 为什么需要它 —— 真卡实测
     *
     * 北理工校园卡上 `SELECT 0018` 回 `9000`（文件在），紧接着 `READ BINARY` 回 `6981`
     * （命令与文件结构不兼容）。这就是**定长记录文件**的典型表现：它不吃 READ BINARY，
     * 只吃 READ RECORD。一条 READ BINARY 失败就收工，会把这类文件整片漏掉。
     */
    fun readRecordCurrentEf(record: Int, length: Int = 0): ByteArray {
        require(record in 1..254) { "记录号必须在 1~254 之间，实际 $record" }
        require(length in 0..256) { "length 必须在 0~256 之间，实际 $length" }
        val le = if (length == 256) 0x00.toByte() else length.toByte()

        return byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), 0x04, le)
    }

    /** `GET RESPONSE`：`00 C0 00 00 <Le>`。卡回 `61 xx` 表示「数据还有 xx 字节，再问一次」。 */
    fun getResponse(length: Int): ByteArray {
        require(length in 1..256) { "length 必须在 1~256 之间，实际 $length" }
        val le = if (length == 256) 0x00.toByte() else length.toByte()
        return byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, le)
    }

    /**
     * 把一条**读命令**末尾的 Le 换掉，其余字节原样不动。
     *
     * ## 为什么需要它 —— 真卡实测
     *
     * 卡回 `6C xx` 是标准（ISO 7816-4）的「你 Le 写错了，正确的是 xx」。
     * 北理工校园卡上 `READ 文件 0015 前 32 字节` 就回了 **`6C1E`** ——
     * 意思是「这个文件只有 0x1E = 30 字节，按 30 来问」。不按它说的重发，
     * 那个文件里的东西就一个字节都拿不到（我们就是这么白丢过一次机会的）。
     *
     * ⚠️ 只允许对**读指令**（[READ_ONLY_INS] 里的 B0/B2/C0）用。
     * 因为「末尾就是 Le」这个前提不是所有命令都成立 —— 比如
     * [selectByFileId] 生成的 `00 A4 02 00 02 00 10`，末尾那个 `10` 是文件号的一部分，
     * 换掉它就变成去选另一个文件了。宁可这里直接拒绝，也不要悄悄改错一条命令。
     */
    fun withLe(apdu: ByteArray, length: Int): ByteArray {
        require(length in 1..256) { "length 必须在 1~256 之间，实际 $length" }
        require(apdu.size >= 5) { "命令太短，末尾不可能是 Le：${apdu.size} 字节" }
        val ins = apdu[1].unsigned()
        require(ins == 0xB0 || ins == 0xB2 || ins == 0xC0) {
            "只对读指令协商 Le，这条的 INS 是 %02X".format(ins)
        }
        val le = if (length == 256) 0x00.toByte() else length.toByte()
        return apdu.copyOf().also { it[it.size - 1] = le }
    }

    /**
     * `READ BINARY`（**不带 SFI**）：`00 B0 <offset 高字节> <offset 低字节> <length>`。
     *
     * 与 [readBinary] 的区别在 P1/P2 的含义：
     * - 带 SFI（[readBinary]）：P1 = `0x80 | SFI`，**P2 才是偏移量**（单字节 0~255）。
     * - 不带 SFI（本函数）：P1 = 偏移量的**高字节**，P2 = 偏移量的**低字节**。
     *
     * ⚠️⚠️ 两条路的偏移量编码**完全不同**，别互相套用。
     * 这个坑真踩过：第五次实测里 `0006` 走 FID 路续读回 `6B00`（偏移超界），
     * 而**同一个文件**走 `SFI=6` 路续读回 `9000`（读到了东西）。
     * 同一份数据、两条路、不同结果 —— 原因就是当时本函数写成了
     * `byteArrayOf(0x00, 0xB0, offset, 0x00, le)`，offset 全进了 P1、P2 恒为 0，
     * 于是「读第 2 段」（本意 offset=32）实际发的是 offset=**0x2000 = 8192** ⇒ 必然 `6B00`。
     *
     * ⇒ 凡是本函数生成的续读命令，之前**全部无效**，而且失败方式很隐蔽：
     * 回的是 `6B00`（偏移超界），看起来像「文件只有 32 字节」，
     * 于是把「文件是空的」当成了结论 —— **与真卡同文件走 SFI 路能读出数据的事实矛盾**。
     *
     * @param offset 0~0x7FFF。规范上 16 位偏移够用；再大的文件应该换别的读法。
     */
    fun readBinaryPlain(offset: Int = 0, length: Int = 32): ByteArray {
        require(offset in 0..0x7FFF) { "offset 必须在 0~32767 之间，实际 $offset" }
        require(length in 1..256) { "length 必须在 1~256 之间，实际 $length" }
        val le = if (length == 256) 0x00.toByte() else length.toByte()

        return byteArrayOf(
            0x00,
            0xB0.toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            le,
        )
    }

    /**
     * `SELECT by file identifier`（按文件标识选）：`00 A4 02 00 02 <FID 高> <FID 低>`。
     *
     * 与 [selectByName]（按 AID 选）是**两条不同的路**：AID 选的是应用，FID 选的是 MF 下的文件。
     * 校园卡的业务数据（学号、钱包）通常躺在具体文件里，而**我们不知道文件的名字** ——
     * 所以只能靠挨个 FID 试，试中了再 [readBinaryPlain] 读一段。
     */
    fun selectByFileId(fid: Int): ByteArray {
        require(fid in 0..0xFFFF) { "FID 必须在 0~FFFF 之间，实际 $fid" }
        return byteArrayOf(
            0x00,
            0xA4.toByte(),
            0x02,
            0x00,
            0x02,
            ((fid shr 8) and 0xFF).toByte(),
            (fid and 0xFF).toByte(),
        )
    }

    /**
     * 一条探测项：给人看的 [label] + 待发的 [apdu]。
     *
     * @param followUp 这条**成功之后**紧接着再发的一条命令（通常是「选中了就读一段」）。
     *   为什么塞在这里而不是让上层自己排：两条命令必须**连着发**才有意义
     *   （中间夹一次别的 SELECT，当前文件就换了，读出来的是另一个文件的东西）。
     * @param followUpLabel [followUp] 的显示名；为 `null` 时用 `「…」接着读`。
     * @param followUpMore 只要上一条还成功，就**继续沿着文件往下读**的命令。
     *   为什么要连续读：文件长度事先不知道，而「读到 32 字节就没下文」并不等于
     *   「文件只有 32 字节」—— 后面可能还有。多读两段的成本是两条只读命令，
     *   收益是「不会因为一次读短了就把数据当成不存在」。
     */
    data class ProbeStep(
        val label: String,
        val apdu: ByteArray,
        val followUp: ByteArray? = null,
        val followUpLabel: String? = null,
        val followUpMore: List<ByteArray> = emptyList(),
        /**
         * 这条命令是「按文件标识选文件（[selectByFileId]）」时带上它选的是哪个 FID。
         *
         * 用处：某个 FID 选中了却吃不下 `READ BINARY`（回 `6986`）时，
         * 它**很可能是个目录（DF）**，要进去再扫一层 —— 但那时需要重新 SELECT 它，
         * 而光看标签字符串是拿不到文件号的（真卡上 `0010` 就是这样，见 nfc-card-probe.md）。
         */
        val fid: Int? = null,
        /**
         * 这条命令是 [readBinary]（带 SFI 的那种读）时带上它读的是哪个 SFI。
         *
         * 用处与 [fid] 完全对称，但方向相反：当这条 `READ BINARY SFI=n` 回 `6981`
         * （命令与文件结构不兼容）时，要改发的是**这个 SFI 的** `READ RECORD`，
         * 而不是「当前已选中 EF」的那条 —— 发 `READ BINARY` 时压根没选中过任何文件，
         * 「当前 EF」是谁完全说不清。真卡实测 `SFI=2` 与 `SFI=24` 都回 `6981`，
         * 拿不到这个编号就只能瞎发。
         */
        val sfi: Int? = null,
        /**
         * [followUp] / [followUpMore] 是否**只在第一段真有内容时**才发。
         *
         * 为什么需要这个开关：SFI 扫描要挨个试 30 个短标识，其中大多数是空文件
         * （全零）或不存在。对全零文件继续往下读三段，只是白等三轮传输时间，
         * 而用户贴在手机上不动的那几秒是有限的。
         * 但「前 32 字节是零」确实**不等于**「文件是空的」，所以这里不是不读，
         * 而是「先看有没有东西，有才继续」。
         */
        val followUpIfData: Boolean = false,
    )

    /**
     * 第一轮该打的那一整套命令。
     *
     * 顺序是有讲究的：先 `SELECT MF` 确认通道通（连这条都答 6E00 的就是非 7816 设备），
     * 再 `SELECT PPSE` 让卡**自己报出**它带的应用 —— 这是唯一不靠猜的发现途径。
     */
    fun firstRound(): List<ProbeStep> = buildList {
        add(ProbeStep("SELECT 主文件 3F00", selectMasterFile()))
        // PSE 排在 PPSE 前面：真卡实测这张卡认的是 1PAY（接触式），PPSE 直接 6A82。
        // 顺序也有讲究 —— 下面那几条 READ RECORD 依赖「当前选中的是目录文件」，
        // 所以必须紧跟在 SELECT PSE 之后，中间不能被别的 SELECT 打断。
        add(ProbeStep("SELECT PSE 1PAY.SYS.DDF01（卡自称的名字）", selectPse()))
        // SFI=1 也是实测得来的：MF 的 FCI 里 `88 01 01` 说的就是 SFI=1。
        // 目录文件通常只有一两条记录，取 1~3 足够，多了纯粹浪费时间。
        (1..3).forEach { rec ->
            add(ProbeStep("READ RECORD SFI=1 记录 $rec", readRecord(record = rec, sfi = 1)))
        }
        add(ProbeStep("SELECT PPSE 2PAY.SYS.DDF01（非接触式，作对照）", selectPpse()))
    }

    /**
     * 第二轮：挨个 SFI 试 `READ BINARY`，命中的**接着往下读**。
     *
     * ## ⚠️ 这一轮曾经被自己的编码 bug 废掉，别再小看它
     *
     * 上一版把 READ BINARY 的 P1 写成了 `(SFI << 3) | 0x04`（那是 READ RECORD 的 P2），
     * 于是 30 条全是 `6A82`，我据此在文档里写下「这一轮没有价值，实测全找不到」——
     * **那是错的结论**。把 P1 改成正确的 `0x80 | SFI` 之后，确有 SFI 开始回非 `6A82`。
     *
     * ## ⚠️⚠️ 归属必须写清楚：**带响应的 SFI 全在目录 `0010` 里，不在 MF 下**
     *
     * 第五次实测（同一张卡）把这件事钉死了：
     *
     * | 上下文 | SFI 1~30 |
     * |---|---|
     * | MF（`SELECT 3F00` 之后） | **30条全 `6A82`** —— MF 下压根没有按 SFI 寻址的文件 |
     * | 目录 `0010` 内 | `2`→`6981`、**`4`→`6982`（要认证）**、`5/6/7`→有数据、`8/9/16~19`→全零 |
     *
     * 而目录内 `0005`/`0006`/`0007` 的内容与 `SFI=5/6/7` **完全一致** ——
     * 说明这一层的 SFI 与低区 FID 是同一批文件的两个编号方式，不是两批数据。
     *
     * ⇒ 上一版把「12 个 SFI 有响应」写成 MF 下的结果，是**把两层搞混了**。
     * 这不只是措辞问题：它会让人以为「MF 下扫 SFI 就够了」，
     * 而真卡的事实是**必须先钻进目录，SFI 才有东西**。
     *
     * ## 命中之后为什么还要往下读
     *
     * 一次只读 32 字节就收工，会把超过 32 字节的文件**截断成半截**。
     * 真卡目录内`SFI=7` 读回来是 `00 00 01 14 01 03 05 00 00 02 76 …`，
     * 看着像好几条记录拼在一起 —— 32 字节后面几乎肯定还有，
     * 而「一次读满 32 字节」从来不是「文件到此为止」的证据。
     *
     * @param range 要试的 SFI 区间（规范取值 1~30）。
     * @param length 每段读多少字节。
     */
    fun sfiScan(range: IntRange = 1..30, length: Int = 32): List<ProbeStep> =
        range.map { sfi ->
            ProbeStep(
                label = "READ BINARY SFI=$sfi",
                apdu = readBinary(sfi = sfi, length = length),
                followUp = readBinary(sfi = sfi, offset = 0x20, length = length),
                followUpLabel = "SFI=$sfi 第 2 段",
                followUpMore = listOf(readBinary(sfi = sfi, offset = 0x40, length = length)),
                sfi = sfi,
                // 空文件（全零）就不必再读两段了，贴卡时间是有成本的。
                followUpIfData = true,
            )
        }

    /**
     * 第三轮：挨个**文件标识（FID）**试 `SELECT`，命中了再接着读一段。
     *
     * ## 为什么在 SFI 扫描之外还要这一轮
     *
     * `sfiScan` 是「不选中任何文件就去读」，而且带 SFI 的读是**相对当前 DF** 的 ——
     * 所以在 MF 下发，真卡 30 条全`6A82`，**这是正常的**（MF 里没有按 SFI 寻址的文件），
     * 不是「这条路没用」。真卡要有响应的 SFI 得先钻进目录 `0010`（见 [sfiScan] 的实测表）。
     *
     * 这一轮反过来：**先选中文件再读** —— 命中一次就知道这个 FID 下真有东西，含金量高得多。
     * 真卡实测 MF 下 `0010~001B` 有 8 个文件选中成功，是目前唯一能稳定拿到数据的路子。
     *
     * ⚠️ 这是**最慢**的一轮：默认 255 个 FID，每个都要一次 SELECT（命中的还各加一次读）。
     * 所以放在深度开关后面，且接线层必须**边收边显示**、中途掉卡也要保留已拿到的部分。
     *
     * @param range 要试的 FID 区间。默认 `0001~00FF`：业务文件绝大多数落在低区，
     *   再往上扫（到 `FFFF`）收益递减而贴卡时间翻倍，不划算。
     */
    fun fidScan(range: IntRange = 0x0001..0x00FF): List<ProbeStep> =
        range.map { fid ->
            ProbeStep(
                label = "SELECT 文件 %04X".format(fid),
                apdu = selectByFileId(fid),
                followUp = readBinaryPlain(),
                followUpLabel = "READ 文件 %04X 前 32 字节".format(fid),
                // 真卡实测：0011 / 0012 / 0013 / 001A / 001B 前 32 字节全是零，
                // 但「前 32 字节是零」不等于「文件是空的」—— 继续往下读两段看看。
                followUpMore = listOf(
                    readBinaryPlain(offset = 0x20),
                    readBinaryPlain(offset = 0x40),
                ),
                fid = fid,
            )
        }

    /**
     * 这个状态字是不是在说「**文件在，但这条命令用错了，换 READ RECORD 再试**」。
     *
     * 真卡实测的教训：`0018` 文件 SELECT 回 `9000`（文件在），紧接着 READ BINARY 回 `6981`
     * （命令与文件结构不兼容）—— 它是定长记录文件，要用 `READ RECORD` 才读得到。
     * 改发记录之后立刻读到了 23 字节的业务数据，所以这条回退是**有实在收益**的。
     *
     * ## ⚠️ 两个边界，都是实测撞出来的
     *
     * 1. **只认 `6981`，不认 `6986`**。`6986` 虽然也是「命令不对」，但它的含义是
     *    「当前选中的是目录，不给你读」—— 对目录发 `READ RECORD` 同样是白费：
     *    真卡 `0010` 上就这么白白发了 5 条，全回 `6986`。目录的正确处置是**进去**（见 [looksLikeDirectory]）。
     * 2. **判断依据必须是读命令的结果**，不是 SELECT 的：SELECT 回 `9000`，
     *    回退挂在它上面就永远不会触发（这个 bug 真发生过一次，整个文件被漏掉）。
     */
    fun shouldTryRecords(sw: String): Boolean = sw == "6981"

    /**
     * 这个状态字是不是在说「**选中的多半是个目录，不是文件**」。
     *
     * `6986` = 当前文件不允许这条命令。对 DF（目录）发 `READ BINARY` 就是回这个 ——
     * 它的资源不直接暴露，得**进去**按 SFI 或 FID 找。真卡 `0010` 正是如此：
     * FCI 里 `6F` 下挂的是 `84`（16 字节 DF 名）与 `A5`，那是 DF 的 FCI 结构，
     * 而普通 EF 的 FCI 里是 `80`（长度）`82`（存取条件）`83`（文件标识）那一套。
     */
    fun looksLikeDirectory(sw: String): Boolean = sw == "6986"

    /**
     * `transceive` 回来的原始字节。
     *
     * @param sw 状态字（最后两个字节），如 `"9000"`。
     * @param swText 状态字的中文解释；不认识时是 `null`（**要如实报没认出来**，别编）。
     * @param data 除状态字以外的数据部分。
     * @param success 是否成功（`sw == 9000`）。另有数据时规范允许 `61xx` —— 那要用
     *   `GET RESPONSE` 再取一次，本模块暂不自动做。
     */
    data class ProbeResult(
        val sw: String,
        val swText: String?,
        val data: ByteArray,
        val success: Boolean,
        /** 还要接着发 `GET RESPONSE` 取剩下的数据（`sw` 以 `61` 开头）。 */
        val hasMore: Boolean = false,
        /**
         * 卡回了 `6C xx`：**Le 写错了，正确的是 `xx`**。
         *
         * 不是错误、更不是「没这个文件」—— 按它给的字节数原样重发一次就能拿到数据。
         * 真卡实测：`READ 文件 0015` 回 `6C1E`，那个文件只有 30 字节。不按它说的重发，
         * 文件内容一个字节都拿不到。`xx` 为 `00` 时按规范表示 256。
         */
        val correctLe: Int? = null,
    )

    /**
     * 解析 `transceive` 的回答。
     *
     * ⚠️ 短于 2 字节的回答是**异常**的（7816 规定至少回一个状态字），
     * 此时不猜、不补零：标成失败并把原文留给上层，让诊断页照原样显示出来。
     */
    fun parseResponse(bytes: ByteArray): ProbeResult {
        if (bytes.size < 2) {
            return ProbeResult(sw = "", swText = null, data = bytes, success = false, hasMore = false)
        }
        val sw = byteArrayOf(bytes[bytes.size - 2], bytes[bytes.size - 1]).let { raw ->
            raw.joinToString("") { b -> "%02X".format(b.unsigned()) }
        }
        val data = bytes.copyOfRange(0, bytes.size - 2)

        return ProbeResult(
            sw = sw,
            swText = statusText(sw),
            data = data,
            success = sw == "9000",
            hasMore = sw.startsWith("61"),
            correctLe = if (sw.startsWith("6C")) {
                // 0x00 按规范代表 256，不是 0 —— 当成 0 会生成一条非法命令
                sw.substring(2).toIntOrNull(16)?.let { if (it == 0) 256 else it }
            } else null,
        )
    }

    /**
     * `61 xx` 里的那个 `xx`：还要用 [getResponse] 取多少字节。
     *
     * 与 [ProbeResult.correctLe] 同一个坑：`xx = 00` 按规范代表 **256**，
     * 当成 0 会生成一条取不到东西的命令。
     */
    fun remainingOf(sw: String): Int? {
        if (!sw.startsWith("61") || sw.length != 4) return null
        return sw.substring(2).toIntOrNull(16)?.let { if (it == 0) 256 else it }
    }

    /**
     * 常见状态字的解释。
     *
     * 认不出来就返回 `null` —— 这条很重要：UI 拿到 `null` 应该显示「未知状态 6F00」，
     * 而不是自作主张解释成成功。
     */
    fun statusText(sw: String): String? = when (sw) {
        "9000" -> "成功"
        "6283" -> "文件已失效（被停用，数据还在）"
        "6A81" -> "不支持该功能"
        "6A82" -> "找不到这个文件/应用"
        "6A83" -> "记录没找到"
        "6A84" -> "空间不足"
        "6A86" -> "P1/P2 参数不对"
        "6A87" -> "Lc 与指令长度不符"
        "6A88" -> "找不到引用数据"
        "6D00" -> "指令（INS）不支持"
        "6E00" -> "类别（CLA）不支持"
        "6F00" -> "未指明的错误"
        // 这两条是真卡实测加进来的，它们是「文件在、但命令用错了」的信号 ——
        // 见到就要换一条命令再试（见 readRecordCurrentEf），而不是当成「没有这个文件」。
        "6981" -> "命令与文件结构不兼容（这类文件不吃这条命令，多半要改用 READ RECORD）"
        "6986" -> "当前文件不允许这条命令（选中的多半是目录，不是文件）"
        "6982" -> "不满足安全条件（要认证/要密钥）"
        "6983" -> "已被锁定（别再试了，可能是重试计数用尽）"
        "6985" -> "使用条件不满足"
        "6700" -> "长度不对"
        // 6B00 不是故障：它说的是「你要的偏移量已经越过文件末尾了」——
        // 在「沿文件往下读」时这正是「这个文件到这里就没了」的答复。
        "6B00" -> "偏移量超界（多半是已经读到文件末尾了）"
        else -> null
    }

    /** 把字节拼成「空格分隔的十六进制」，给导出诊断文本用（人要去对着查）。*/
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { b -> "%02X".format(b.unsigned()) }

    /** 紧凑十六进制（无空格）—— 给「解析出来的 AID 再拿去 SELECT」这种场合用。 */
    fun toHexCompact(bytes: ByteArray): String = toHex(bytes).replace(" ", "")

    /**
     * 试着把一段数据当**文本**读出来，读不出来就返回 `null`。
     *
     * ## 为什么必须试 —— 真卡实测
     *
     * 北理工校园卡的 `SELECT 0016` 读回来是
     * `00 00 B8 DF CC EC CF E8 00 00 …`，GBK 一解就是**姓名「高天翔」**。
     * 不试着解码的话，用户（和我们）看到的就只是一串十六进制 ——
     * 数据明明已经拿到了，却认不出来，等于没拿到。
     *
     * ## 为什么是 GBK 而不是 UTF-8
     *
     * 国内校园卡一卡通系统绝大多数是 GBK（GB2312 超集）：
     * `B8 DF` = 「高」，这是 GBK 的编码，用 UTF-8 解出来只会是乱码。
     * GBK 是 ASCII 的超集，所以英文内容也走这一条，不用分两遍。
     *
     * ## 判据：解出来不能有替换字符
     *
     * 随便一段二进制用 GBK 硬解也会出字符，所以这里要求**每一个字符都是可打印的**
     * （`>= 0x20` 且不是 U+FFFD）。汉字都满足，乱码里的控制字节不满足。
     * 宁可返回 `null`（UI 就只显示十六进制），也不要端一坨乱码给用户。
     */
    fun decodeText(bytes: ByteArray): String? {
        val trimmed = bytes.trimZeroBytes()
        if (trimmed.isEmpty()) return null

        val decoded = runCatching { String(trimmed, charset("GBK")) }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        if (decoded.any { it.code < 0x20 || it == '\uFFFD' }) return null
        return decoded.trim()
    }

    /** 去掉首尾的 0x00 填充（文件里空位都是零，留着只会把文本搞乱）。 */
    private fun ByteArray.trimZeroBytes(): ByteArray {
        var from = 0
        var to = size
        while (from < to && this[from] == 0.toByte()) from++
        while (to > from && this[to - 1] == 0.toByte()) to--
        return copyOfRange(from, to)
    }

    // ------------------------------------------------------------------ TLV

    /**
     * 一个 TLV 条目。
     *
     * @param tag 十六进制大写字符串（单字节如 `"4F"`，双字节如 `"9F1F"`）。
     * @param value 值部分。
     * @param constructed 是否**构造型**（值里还套着 TLV，如 `6F` / `A5`）。
     */
    data class Tlv(
        val tag: String,
        val value: ByteArray,
        val constructed: Boolean,
    )

    /**
     * 解析 ISO 7816-4 的 BER-TLV。
     *
     * ## 为什么要自己写
     *
     * 卡回答的 FCI / 目录记录**全是 TLV**，不解析就只是一串十六进制，
     * 看不出「哦，它的目录在 SFI=1」。而我们真正要的东西就是几个 tag：
     * - `84` = DF 名（卡自称叫什么，实测是 `1PAY.SYS.DDF01`）
     * - `88` = SFI（目录文件在哪）
     * - `4F` = ADF 名（**下一个要 SELECT 的 AID**，最重要的那个）
     *
     * ## 构造型必须递归
     *
     * 是否构造看首字节第 6 位（`0x20`）：置位即构造型。
     * 实测那条返回是 `6F 15 … A5 03 88 01 01`，不递归的话 `88`（SFI）就埋在 `A5` 里拿不到。
     */
    fun parseTlvs(bytes: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        walk(bytes, 0, bytes.size, out, depth = 0)
        return out
    }

    private fun walk(bytes: ByteArray, from: Int, to: Int, out: MutableList<Tlv>, depth: Int) {
        if (depth > 6) return // 防呆：正常卡不会套这么深，套成这样多半是读错了
        var i = from

        while (i + 1 < to) {
            val first = bytes[i].unsigned()
            val tag: String
            if (first and 0x1F == 0x1F && i + 1 < to) { // 低 5 位全 1 ⇒ tag 还有后续字节
                tag = "%02X%02X".format(first, bytes[i + 1].unsigned())
                i += 2
            } else {
                tag = "%02X".format(first)
                i += 1
            }
            if (i >= to) return

            var len = bytes[i].unsigned()
            i += 1
            if (len >= 0x80) { // 长格式：低 7 位表示「后面还有几个长度字节」
                val n = len - 0x80
                if (n !in 1..3 || i + n > to) return
                len = 0
                for (k in 0 until n) len = (len shl 8) + bytes[i + k].unsigned()
                i += n
            }
            if (len < 0 || i + len > to) return

            val constructed = first and 0x20 != 0
            out += Tlv(tag, bytes.copyOfRange(i, i + len), constructed)
            if (constructed) walk(bytes, i, i + len, out, depth + 1)
            i += len
        }
    }

    /**
     * 从 TLV 里挑 AID（tag `4F` = ADF 名）。
     *
     * 只收 5~16 字节（ISO 7816-5 规定 AID 就在这个长度区间）：
     * 比这短的多半是别的数据被误读成 tag，收进来只会拿去发一次注定失败的 SELECT。
     */
    fun aidsOf(tlvs: List<Tlv>): List<ByteArray> =
        tlvs.filter { it.tag == "4F" && it.value.size in 5..16 }
            .map { it.value }
            .distinctBy { it.toList() }

    /** 十六进制串转字节。空串得空数组；奇数长度或非法字符一律报错（早失败好过静默错位）。 */
    fun parseHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "").uppercase()
        require(clean.length % 2 == 0) { "十六进制串长度必须是偶数：$hex" }
        require(clean.all { it in '0'..'9' || it in 'A'..'F' }) { "含有非法字符：$hex" }
        if (clean.isEmpty()) return ByteArray(0)

        return ByteArray(clean.length / 2) { i ->
            (digit(clean[i * 2]) * 16 + digit(clean[i * 2 + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int =
        if (c in '0'..'9') c - '0' else c - 'A' + 10

    private fun Byte.unsigned(): Int = toInt().let { if (it < 0) it + 256 else it }
}
