package cn.bit101.android.features.nfc.logic

/**
 * MIFARE Classic 侧的**只读**探测：先拿常见默认密钥去认证扇区，认证过了才读块。
 *
 * ## 为什么要有这一条路
 *
 * 北理工校园卡实测是**双界面卡**：`IsoDep` 与 `MifareClassic` 同时在 techList 里。
 * APDU 那条路已经在 `CardProbeLogic` 里走了，但实测下来它的 MF 只是借了个支付目录的名字
 * （`1PAY.SYS.DDF01`），`READ RECORD` 一律 `6A82` —— 也就是说**文件结构我们摸不到**。
 * 而校园卡的学号/工号**经常就明文躺在 Classic 扇区的块里**。所以这条路值得单独走一遍。
 *
 * ## ⚠️⚠️ 红线：只认证、只读块
 *
 * 本模块**没有**任何写块 / 增值减值 / 改密钥的入口，接线层也不许有：
 * - 不许 `writeBlock`；
 * - 不许拿 `MifareClassic.transceive` 发非 7816 只读指令；
 * - **认证失败就停** —— 我们不去破密钥（既不道德也大概率徒劳）。
 *
 * 认证（`authenticateSectorWithKeyA/B`）本身**不改变卡内任何数据**，
 * 它只是让卡相信「我知道这个扇区的密钥」，之后才允许读。
 *
 * ## 为什么还是纯逻辑
 *
 * 开发机没有 NFC 硬件，一切跟 `MifareClassic` 对象有关的操作都跑不了。
 * 但「试哪些密钥」「扇区/块怎么排」「读到的 16 字节怎么翻成人能看的」全是确定性的，
 * 所以压在这里，接线层只负责把结果搬过去。
 */
internal object ClassicProbeLogic {

    /** 一个候选密钥。 [name] 是给人看的来历，[hex] 是 6 字节密钥。 */
    data class KeyCandidate(
        val name: String,
        val hex: String,
    )

    /**
     * 常见默认密钥。
     *
     * ⚠️ 这是**社区里流传最广的那一批**，不是本校卡的密钥：
     * 能不能过全靠运气，过不了是**正常结果**，不要据此下「卡里没数据」的结论。
     *
     * 顺序按「出厂卡最可能是哪个」排 —— 早命中就能少认证几百次，贴卡时间短一点。
     */
    val DEFAULT_KEYS: List<KeyCandidate> = listOf(
        KeyCandidate("出厂默认", "FFFFFFFFFFFF"),
        KeyCandidate("全零", "000000000000"),
        KeyCandidate("NFC Forum / MAD", "A0A1A2A3A4A5"),
        KeyCandidate("NDEF 专用", "D3F7D3F7D3F7"),
        KeyCandidate("常见备选", "B0B1B2B3B4B5"),
        KeyCandidate("常见备选", "A0B0C0D0E0F0"),
        KeyCandidate("常见备选", "AABBCCDDEEFF"),
        KeyCandidate("常见备选", "4D3A99C351DD"),
        KeyCandidate("常见备选", "1A982C7E459A"),
    )

    /** 密钥长度（字节）。MIFARE Classic 的密钥恒为 6 字节。 */
    const val KEY_LENGTH = 6

    /**
     * 一次「读某个块」的计划。
     *
     * @param sector 扇区号。
     * @param indexInSector 扇区内的第几个块（从 0 起）。
     * @param block **绝对块号** —— `MifareClassic.readBlock` 要的就是这个，
     *   不是「扇区内序号」。两者搞混是 Classic 最经典的坑（会读到隔壁扇区去）。
     */
    data class PlannedRead(
        val sector: Int,
        val indexInSector: Int,
        val block: Int,
    )

    /** 卡的容量（字节）。MIFARE Classic 只有 1K 与 4K 两种常见规格。 */
    const val SIZE_1K = 1024
    const val SIZE_4K = 4096

    /**
     * 扇区数。1K = 16 个扇区，4K = 32 个小扇区 + 8 个大扇区 = 40。
     *
     * @return 认不出容量时返回 0（上层要据此跳过整段，而不是瞎扫）。
     */
    fun sectorCount(sizeBytes: Int): Int = when (sizeBytes) {
        SIZE_1K -> 16
        SIZE_4K -> 40
        else -> 0
    }

    /**
     * 某扇区里有几个块。
     *
     * 1K 一律 4 个；4K 的前 32 个扇区 4 个，后 8 个扇区 16 个。
     * 这个「4K 后半段是 16 块」的不一致正是必须单独算的原因 —— 拍脑袋一律 4 会漏掉一大半空间。
     */
    fun blocksInSector(sector: Int, sizeBytes: Int): Int = when (sizeBytes) {
        SIZE_1K -> 4
        SIZE_4K -> if (sector < 32) 4 else 16
        else -> 0
    }

    /**
     * 生成整张卡的**待读块**清单，按扇区分好组、跳过尾块。
     *
     * ## 为什么跳过尾块
     *
     * 每个扇区的**最后一块**是扇区尾块，存的是密钥 A/B 与存取位。
     * ① 读它拿不到任何一卡通业务数据；② 卡被读出来的是全零，看了只会徒增困惑；
     * ③ 最重要 —— **尾块是最容易让人手滑去写的地方**，索性不读、不列、不显示。
     *
     * @param sizeBytes 卡容量；认不出（返回 0 扇区）时给空清单。
     * @param maxSector 最多扫到第几个扇区（不含）。用于给 4K 卡减速。
     */
    fun plan(sizeBytes: Int, maxSector: Int = Int.MAX_VALUE): List<PlannedRead> {
        val sectors = sectorCount(sizeBytes)
        if (sectors <= 0) return emptyList()

        val out = mutableListOf<PlannedRead>()
        var block = 0
        for (sector in 0 until minOf(sectors, maxSector)) {
            val n = blocksInSector(sector, sizeBytes)
            // 尾块 = 扇区内最后一个块，跳过
            for (i in 0 until n - 1) out += PlannedRead(sector, i, block + i)
            block += n
        }
        return out
    }

    /** 一个块读出来之后的「人话版」。 */
    data class BlockView(
        /** 十六进制原文（空格分隔，大写）。 */
        val hex: String,
        /** 整块都是可打印 ASCII 时的原文；否则 `null`。 */
        val ascii: String?,
        /**
         * 整块的每个半字节都是 0~9 时拼出来的数字串；否则 `null`。
         *
         * 学号在卡里最常见的存法就是 BCD，所以这一栏是**最该先看的一栏**：
         * 块里若是 `11 20 24 13 55`，这里直接显示 `1120241355`。
         */
        val bcdDigits: String?,
    )

    /**
     * 把一个块（16 字节）翻成人能看的样子。
     *
     * 三栏**并排给**：hex 永远有，ascii / bcd 只在真的成立时才有。
     * 不做「猜一个最像的」—— 猜错比不给更糟，这条纪律整个模块一致。
     */
    fun decode(block: ByteArray): BlockView {
        val hex = block.joinToString(" ") { b -> "%02X".format(b.unsigned()) }

        val ascii = if (block.isNotEmpty() && block.all { it.unsigned() in 0x20..0x7E }) {
            String(block, Charsets.US_ASCII)
        } else null

        val bcd = if (block.isNotEmpty() && block.all { b ->
            val u = b.unsigned()
            (u shr 4) <= 9 && (u and 0x0F) <= 9
        }) {
            block.joinToString("") { b -> "%d%d".format(b.unsigned() shr 4, b.unsigned() and 0x0F) }
        } else null

        return BlockView(hex = hex, ascii = ascii, bcdDigits = bcd)
    }

    /** 全零块：多数是没写过的空块，列出来意义不大，UI 上会折叠掉。 */
    fun isBlank(block: ByteArray): Boolean = block.all { it == 0.toByte() }

    private fun Byte.unsigned(): Int = toInt().let { if (it < 0) it + 256 else it }
}
