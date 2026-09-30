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

    /** PPSE 的应用名（`2PAY.SYS.DDF01`）。SELECT 它能拿到卡内支付类应用的清单。 */
    const val PPSE_AID = "325041592E5359532E4444463031"

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

    /**
     * `READ BINARY (with SFI)`：`00 B0 <P1> <offset> <length>`。
     *
     * P1 的最高位是 1 表示后面 5 位是 SFI（短文件标识），低三位为 100。
     * @param sfi 短文件标识，规范取值 1~30；0 表示「当前文件」。
     * @param length 期望读出的字节数，**不能超过 256**（我们不需要 GET RESPONSE 那一层复杂度）。
     */
    fun readBinary(sfi: Int, offset: Int = 0, length: Int = 32): ByteArray {
        require(sfi in 0..30) { "SFI 必须在 0~30 之间，实际 $sfi" }
        require(length in 1..256) { "length 必须在 1~256 之间，实际 $length" }
        // 256 在 Le 位置编码为 0x00
        val le = if (length == 256) 0x00.toByte() else length.toByte()

        return byteArrayOf(
            0x00,
            0xB0.toByte(),
            ((sfi shl 3) or 0x04).toByte(),
            offset.toByte(),
            le,
        )
    }

    /**
     * 一条探测项：给人看的 [label] + 待发的 [apdu]。
     */
    data class ProbeStep(
        val label: String,
        val apdu: ByteArray,
    )

    /**
     * 第一轮该打的那一整套命令。
     *
     * 顺序是有讲究的：先 `SELECT MF` 确认通道通（连这条都答 6E00 的就是非 7816 设备），
     * 再 `SELECT PPSE` 让卡**自己报出**它带的应用 —— 这是唯一不靠猜的发现途径。
     */
    fun firstRound(): List<ProbeStep> = listOf(
        ProbeStep("SELECT 主文件 3F00", selectMasterFile()),
        ProbeStep("SELECT PPSE（问卡里有哪些应用）", selectPpse()),
    )

    /**
     * 第二轮：挨个 SFI 试 `READ BINARY`。
     *
     * ⚠️ 这是**探索性**的一轮 —— 未选过文件就去读，多数卡会回 `6A82`（文件没找到）。
     * 之所以还留着：少数CPU 卡的 MF 下就有可执行读的 EF，且这条指令**确定不会写坏东西**
     * （已过 [isReadOnly]），试错成本为零。
     */
    fun sfiScan(range: IntRange = 1..30, length: Int = 32): List<ProbeStep> =
        range.map { ProbeStep("READ BINARY SFI=$it", readBinary(sfi = it, length = length)) }

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
        val hasMore: Boolean,
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
        )
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
        "6982" -> "不满足安全条件（要认证/要密钥）"
        "6983" -> "已被锁定（别再试了，可能是重试计数用尽）"
        "6985" -> "使用条件不满足"
        "6700" -> "长度不对"
        "6B00" -> "偏移量超界"
        else -> null
    }

    /** 把字节拼成「空格分隔的十六进制」，给导出诊断文本用（人要去对着查）。*/
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { b -> "%02X".format(b.unsigned()) }

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
