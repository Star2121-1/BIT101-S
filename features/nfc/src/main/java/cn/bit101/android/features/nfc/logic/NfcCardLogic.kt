package cn.bit101.android.features.nfc.logic

/**
 * 一次读卡从系统拿到的**原始信息**（还没做翻译成人话）。
 *
 * ## 为什么要在框架的 `Tag` 之外再包一层
 *
 * `android.nfc.Tag` 是 final 类、**自己构造不出来**，JVM 单测里没法伪造一个 ——
 * 而本项目的纪律是「纯逻辑一律抽 `*Logic`、必须能写单测」。
 * 所以这里只装基本类型，把「框架 `Tag` → [RawTag]」压在最薄的一层里，那一层之外全都可测。
 *
 * ⚠️ [id] 是 `ByteArray`：`data class` 的 `equals` 对数组走**引用比较**。
 * 本模块的用法一直是从 contents 推导字符串、不拿实例进 `Set`/`Map`；
 * 谁要是把它塞进集合，请先补 `contentEquals`。
 */
internal data class RawTag(
    /**
     * `Tag.getId()` 的结果。
     *
     * 常见长度：4 字节（单尺寸 UID）、7 字节（双尺寸 UID）。
     * ⚠️ 这个值**不等于卡面印的卡号**，换算见 [NfcCardLogic.candidates]。
     */
    val id: ByteArray,

    /**
     * `Tag.getTechList()` 的结果，
     * 如 `["android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic"]`。
     */
    val techs: List<String>,

    /**
     * ISO 14443-4 的「历史字节」（ATS 里去掉格式字节后剩下的部分）。
     *
     * 只有能起 ISO-DEP 通道的卡（`IsoDep`）拿得到；MIFARE Classic 这里是 `null`。
     * 校园卡若是 CPU 卡，这几个字节是判断它「到底是谁家的卡」最有价值的线索。
     */
    val historicalBytes: ByteArray? = null,
)

/**
 * 卡的大类。
 *
 * 区分它决定**下一步能做什么**：能不能发 APDU（[CPU_CARD]）、
 * 要不要密钥才能读扇区（[MIFARE_CLASSIC]）、还是根本只是一张贴纸（[NFC_FORUM_TAG]）。
 */
internal enum class CardKind {
    /** MIFARE Classic（S50/S70 等）。Android 能读 UID，读扇区要密钥。 */
    MIFARE_CLASSIC,

    /**
     * CPU 卡：走了 ISO 14443-4 / ISO 7816-4 通道，可以发 APDU 做 SELECT 探测。
     *
     * 多数高校校园卡属于这一类。
     */
    CPU_CARD,

    /** NFC Forum 标签（NTAG / Ultralight 等）：贴纸，读写不受密钥保护。 */
    NFC_FORUM_TAG,

    /** ISO 15693（vicinity）。校园场景少见，先认下来别报成未知。 */
    NFC_V,

    /** techList 里一条我们认识的都没有，或压根是空的。 */
    UNKNOWN,
}

/**
 * 卡信息的「人话版」结构，给诊断页展示 / 导出用。
 */
internal data class CardDescription(
    val kind: CardKind,
    /** UID 字节数，用来区分单/双尺寸 UID。 */
    val uidSize: Int,
    /** 能不能对它发 APDU —— 决定要不要接着跑 `CardProbeLogic`。 */
    val supportsApdu: Boolean,
    /**
     * 是否**同时**暴露了 MIFARE Classic 兼容层。
     *
     * 北理工校园卡实测就是这种双界面卡（`IsoDep` 与 `MifareClassic` 同时在 techList 里）。
     * 两个用处：① UI 告诉用户「它既是 CPU 卡、也能当 Classic 读」；
     * ② ⚠️ 写入前当**红线** —— 见 `NfcController.writeShortcut`：往这种卡写 NDEF
     * 会重写扇区尾块（密钥与存取位），可能把一卡通数据搞坏。
     */
    val classicCompat: Boolean,
    /** 去掉 `android.nfc.tech.` 前缀后的技术列表，如 `["NfcA", "MifareClassic"]`。 */
    val techShortNames: List<String>,
    /** 历史字节的十六进制字符串；没有则 `null`。 */
    val historicalHex: String?,
)

/**
 * 卡号的一种**候选换算**。
 *
 * ⚠️ 之所以叫「候选」：UID → 卡面印刷号 的换算规则是**各校自己定的**，没有标准。
 * 所以这里把常见几种全列出来，让用户在诊断页上**对着卡面找一个对得上的**，
 * 而不是由我们挑一个冒充结论（这条纪律见项目长期记忆第 0.6 条）。
 */
internal data class CardNoCandidate(
    val label: String,
    val value: String,
)

/**
 * 在卡内数据里**找学号**。
 *
 * ## 为什么这件事值得单独做
 *
 * 上游当年判「NFC 刷校园卡登录不可行」，根因是**卡号 → 学号 的映射表只在一卡通中心**，
 * 我们拿不到。但如果**学号本身就写在卡里**，那层映射根本不需要 ——
 * 卡自己就报出了它是谁的。
 *
 * ⚠️ 这是**假设**，不是结论：会不会写、写在哪个文件、用什么编码，只有真卡扫一遍才知道。
 * 所以这里的职责很窄 —— 拿用户给的学号，把可能的编码都列出来，去返回字节里**找**；
 * 找得到就标出来，找不到就如实说没找到，**绝不猜一个**。
 */
internal object StudentIdScan {

    private const val ASCII_ZERO = 0x30
    private const val ASCII_NINE = 0x39

    /**
     * 学号在卡里可能的编码形式。
     *
     * - **BCD**：学号最常见的存法。两位十进制压进一个字节，
     *   如 `1120241355` → `11 20 24 13 55`（5 字节）。校园卡的脱机钱包文件里基本都是这个。
     * - **ASCII**：直接当文本存，`"1120241355"`（10 字节）。少数系统会这么干。
     * - **反序 BCD**：字节序反过来。扇区数据有大端小端之争，两种都试一遍成本为零。
     */
    fun encodingsOf(studentId: String): List<Pair<String, ByteArray>> {
        val digits = studentId.trim()
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return emptyList()

        val bcd = bcdOf(digits) ?: return emptyList()
        return buildList {
            add("BCD" to bcd)
            add("BCD 反序" to bcd.reversedArray())
            add("ASCII" to digits.toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * 在 [data] 里找学号。
     *
     * @return 命中描述（含编码形式与偏移），没找到则 `null`。
     */
    fun find(data: ByteArray, studentId: String): String? {
        if (data.isEmpty()) return null
        findContiguous(data, studentId)?.let { return it }
        return findSegmented(data, studentId)
    }

    /** 连续字节的精确匹配（ASCII / BCD / BCD 反序）。 */
    private fun findContiguous(data: ByteArray, studentId: String): String? {
        encodingsOf(studentId).forEach { (name, needle) ->
            val at = indexOf(data, needle)
            if (at >= 0) return "$name 形式，偏移 $at"
        }
        return null
    }

    /**
     * ⚠️⚠️ **分段匹配** —— 真卡实测（第七次）才找到学号，靠的就是这一条。
     *
     * `0016`（姓名文件）的第 2 段里，用户学号是这样摆的（偏移 10 起）：
     *
     * ```
     * 31 31 06 32 30 32 34 31 33 04 35 35
     *  '1''1' 06  '2''0''2''4''1''3' 04  '5''5'
     * ```
     *
     * 即 `11` + **非数字分隔符** + `202413` + **非数字分隔符** + `55`，
     * **跳过两个分隔符拼起来正好是 `1120241355`**（已逐字节核对，完全一致）。
     *
     * ## 为什么必须单独写这条路
     *
     * 连续匹配对上面这段**必然失败**：字节不连续。
     * 而这条数据的价值极高 —— **它是整个 NFC 功能成立的关键**（见本文件顶部）：
     * 卡自己就报出了「这是谁」，不需要「卡号 → 学号」那层只有一卡通中心才有的映射表。
     * ⇒ **「找不到学号」不等于「卡里没有学号」，也可能只是它被分隔了。**
     *
     * ## 判据故意收紧
     *
     * 分隔符必须**恰好 1 字节**、非数字、且**不是常见填充值**，并且**所有分段拼起来
     * 正好等于完整学号**。否则 `20 26 03 22 11 08 43` 这种 BCD流水会被切成
     * `20`+`26`+`03`+… 拼出一串假学号。只认「拼起来完全相等」这一种，宁可漏也不误报。
     */
    private fun findSegmented(data: ByteArray, studentId: String): String? {
        val digits = studentId.trim()
        if (digits.length < 6 || digits.any { !it.isDigit() }) return null

        // ⚠️ 段数上限 = **学号位数**，而不是拍一个常数。
        // 这个上限兼两种职责：既是防误报（能切出比学号还多的段就不对），
        // 又必须**足够大**才能装下真卡上那三段（`11` / `202413` / `55`）。
        // 之前拍了个 `MAX_SEGMENTS = 6`，结果 `11`+`202413` 正好 6 段就停，
        // **永远拼不成 10 位** —— 判据太紧会漏掉真东西，那比误报更糟。
        val maxSegments = digits.length

        for (i in data.indices) {
            val seg = segmentationAt(data, i, maxSegments) ?: continue
            // 只有一段那是 findContiguous 的活，不必走这条路
            if (seg.parts.size < 2) continue
            if (seg.parts.joinToString("") == digits) {
                return "分段形式（${seg.parts.size} 段，${seg.separatorText}），偏移 $i"
            }
        }
        return null
    }

    /**
     * 一次「按数字段切分」的结果。
     *
     * @param parts 切出来的数字段（每段是若干个数字字符）。
     *   ⚠️ 这里数的是**段数**，不是数字字符数 ——
     *   早先每个数字字符都单独进 `parts`，于是 10 位学号被报成「10 段」，
     *   命中是对的、文案里那个段数是错的（真卡上是 3 段）。
     * @param separators 用掉了几个分隔符。
     */
    private data class Segmentation(val parts: List<String>, val separators: Int) {
        val separatorText: String
            get() = if (separators == 1) "1 个分隔符" else "$separators 个分隔符"
    }

    /**
     * 从 [from] 处按「数字段 + 单字节控制字符分隔」切一段出来。
     *
     * ⚠️ **这是唯一一份切分实现**：[findSegmented]（有学号时核对）与
     * [guessSegmented]（没学号时猜）都走它。抄成两份的代价在本模块已经付过好几次 ——
     * 两条本该一致的路会各自漂移，而表现只是「结果对不上」，很难定位。
     *
     * @return 没切出任何数字段时返回 `null`（调用方据此判断该起点无意义）。
     */
    private fun segmentationAt(data: ByteArray, from: Int, maxSegments: Int): Segmentation? {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var separators = 0
        var at = from

        while (at < data.size && parts.size < maxSegments) {
            val b = data[at].toInt() and 0xFF
            if (b in ASCII_ZERO..ASCII_NINE) {
                current.append(b.toChar())
                at++
                continue
            }
            // 非数字：只有「正好夹在两段数字之间、且自己是 1 字节控制字符」才算分隔符
            if (current.isNotEmpty() && at + 1 < data.size &&
                data[at + 1].toInt() and 0xFF in ASCII_ZERO..ASCII_NINE &&
                isSeparator(b)
            ) {
                parts += current.toString()
                current = StringBuilder()
                separators++
                at++
                continue
            }
            break
        }
        if (current.isNotEmpty()) parts += current.toString()

        return if (parts.isEmpty()) null else Segmentation(parts, separators)
    }

    // ------------------------------------------------------- 不填学号也能认

    /**
     * 猜出来的学号。
     *
     * @param value 学号数字串本身。
     * @param note 它是**怎么**认出来的（形式 + 偏移），给人核对用。
     */
    data class Guess(val value: String, val note: String)

    /**
     * 学号长度区间。北理工是 10 位；放宽到 8~12 是为了不把别届 / 别的学制漏掉，
     * 多出来的误报由 [looksLikeStudentId] 的年份判据兜着。
     */
    private const val MIN_LEN = 8
    private const val MAX_LEN = 12

    /** 「入学年份」的合理窗口。 */
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2039

    /**
     * 不拿学号，**直接从卡里认** —— 用户不填学号也能知道这张卡是谁的。
     *
     * ## 为什么做得到
     *
     * 学号不是随机数：北理工的 `1120241355` 里，第 3~6 位就是**入学年份** `2024`。
     * 于是「一串 8~12 位数字、首位非 0、靠前位置含一个 2000~2039 的四位数」
     * 就足以把学号从一堆二进制里挑出来，不必预先知道它是多少。
     *
     * ## ⚠️ 所以它只能叫「疑似」
     *
     * 上面那条是**格式假设**，不是卡里的既成事实：换一所学校、
     * 或学校改了学号规则，它就不成立。猜错一个学号的后果不小 ——
     * 用户会拿它当真。⇒ 界面与存下来的名片都必须标「疑似 / 未核对」，
     * 只有用户拿它跑过一次 [find]（真命中）之后才算确认。
     *
     * ## ⚠️ 为什么**不**猜 BCD 形式（权衡后的放弃，不是没想到）
     *
     * BCD 里**任何一个字节**都可能被读成两位十进制（`0x11` → `11`），
     * 于是一段随机二进制能滑出几十个「看着像学号」的 10 位串；年份窗口也压不住
     * （粗估每个文件零点几个误报，三十个文件就是好几个假学号）。
     * 真卡存的是 **ASCII**（`31 31 06 32 …`），所以先只做实测过的形式。
     * 将来真遇到 BCD 的卡再加 —— 那时有反例可验，比现在凭想当然加一条稳。
     *
     * @return 认出来的学号；认不出返回 `null`（**不猜一个凑数**）。
     */
    fun guess(data: ByteArray): Guess? {
        if (data.isEmpty()) return null
        // 分段优先：真卡就是分段形式，而且它比「碰巧连着一串数字」更不像噪声。
        return guessSegmented(data) ?: guessContiguous(data)
    }

    private fun guessSegmented(data: ByteArray): Guess? {
        for (i in data.indices) {
            val seg = segmentationAt(data, i, MAX_LEN) ?: continue
            if (seg.parts.size < 2) continue
            val digits = seg.parts.joinToString("")
            if (!looksLikeStudentId(digits)) continue
            return Guess(digits, "分段形式（${seg.parts.size} 段，${seg.separatorText}），偏移 $i")
        }
        return null
    }

    /** 连续 ASCII 数字串。要求**整段**落在长度区间内 —— 截半段去猜，比不猜更糟。 */
    private fun guessContiguous(data: ByteArray): Guess? {
        var i = 0
        while (i < data.size) {
            if (!isAsciiDigit(data[i])) {
                i++
                continue
            }
            var j = i
            while (j < data.size && isAsciiDigit(data[j])) j++
            val length = j - i
            if (length in MIN_LEN..MAX_LEN) {
                val digits = String(data, i, length, Charsets.US_ASCII)
                if (looksLikeStudentId(digits)) return Guess(digits, "连续 ASCII 数字，偏移 $i")
            }
            i = j
        }
        return null
    }

    /**
     * 这串数字像不像学号。两条判据都要满足：
     *
     * ① 长度 8~12、**首位不是 0** —— 学号不以 0 开头，而卡里补零的位置到处都是；
     * ② 靠前的某个连续四位落在 2000~2039（入学年份）。位置取 0~5：
     *    既装得下实测的 `1120241355`（第 3 位起），也容得下「年份打头」的写法。
     */
    private fun looksLikeStudentId(digits: String): Boolean {
        if (digits.length !in MIN_LEN..MAX_LEN) return false
        if (digits.first() == '0') return false

        val lastStart = minOf(5, digits.length - 4)
        for (i in 0..lastStart) {
            val year = digits.substring(i, i + 4).toIntOrNull() ?: continue
            if (year in MIN_YEAR..MAX_YEAR) return true
        }
        return false
    }

    private fun isAsciiDigit(b: Byte): Boolean = (b.toInt() and 0xFF) in ASCII_ZERO..ASCII_NINE

    /**
     * 这个非数字字节**算不算**分隔符。
     *
     * ## 只认控制字符域（`0x01`~`0x1F`）
     *
     * 真卡上那两个分隔符是 `0x06` 与 `0x04`，都在控制字符域里。
     * 而**放宽到「任何非数字」会立刻引入误报** ——
     * `31 31 00 31 00 32 …`（数字被 `0x00` 反复隔开）就能拼出 `1120241355`，
     * 卡里补零的位置到处都是，这么判必然出事。
     *
     * ⇒ 判据是「**恰好 1 字节的控制字符**」，不是「非数字」。
     * 这样`0x00`（补零）、`0xFF`（补零）、`0x20`（空格）、`0x2D`（连字符）
     * 全都被排除，而真卡那两个仍然认得出来。
     */
    private fun isSeparator(b: Int): Boolean = b in 0x01..0x1F

    /**
     * BCD：两位十进制压一个字节。奇数位**末尾补 0**（补在尾部而不是头部，
     * 是因为卡里高位在前；若补错位置就永远匹配不上，所以这里不做别的猜测）。
     */
    private fun bcdOf(digits: String): ByteArray? {
        val padded = if (digits.length % 2 == 0) digits else digits + "0"
        return ByteArray(padded.length / 2) { i ->
            val hi = padded[i * 2].digitToIntOrNull() ?: return null
            val lo = padded[i * 2 + 1].digitToIntOrNull() ?: return null
            (hi * 16 + lo).toByte()
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        outer@ for (i in 0..(data.size - needle.size)) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * 读卡结果的**无关框架**翻译层。
 *
 * 输入是从系统 `Tag` 抄下来的 [RawTag]，输出不需要任何 Android 类参与 ——
 * 所以它 100% 可单测，**不需要手边有 NFC 硬件**。
 */
internal object NfcCardLogic {

    private const val TECH_PREFIX = "android.nfc.tech."

    /**
     * 判断卡的大类。
     *
     * `IsoDep` 优先于 `MifareClassic`：两者的取舍写在下面的注释里，是真卡实测换来的，
     * 不要依据「Classic 更常见」这类直觉调回去。
     */
    fun recognize(raw: RawTag): CardKind = when {
        // ⚠️⚠️ `IsoDep` 必须排在 `MifareClassic` **前面** —— 这条顺序是**真卡实测**换来的。
        // 北理工校园卡实测 techList = [IsoDep, NfcA, NfcA, MifareClassic, NdefFormatable]：
        // 它同时具备 Classic 兼容层与 ISO 14443-4 通道。判成 MIFARE_CLASSIC 的后果是
        // 上层认为「要密钥，读不了」直接放弃 —— 那条**唯一能安全取数**的 APDU 通道就被我们自己掐了。
        // 只要有 IsoDep 就一定能发 APDU；反过来 Classic 读扇区要密钥，我们大概率没有。
        raw.techs.contains(TECH_PREFIX + "IsoDep") -> CardKind.CPU_CARD
        raw.techs.contains(TECH_PREFIX + "MifareClassic") -> CardKind.MIFARE_CLASSIC
        raw.techs.contains(TECH_PREFIX + "NfcV") -> CardKind.NFC_V
        raw.techs.any { it == TECH_PREFIX + "Ndef" || it == TECH_PREFIX + "NdefFormatable" } ->
            CardKind.NFC_FORUM_TAG
        else -> CardKind.UNKNOWN
    }

    /** 能不能对它发 ISO 7816-4 指令。 */
    fun supportsApdu(raw: RawTag): Boolean =
        raw.techs.contains(TECH_PREFIX + "IsoDep")

    /**
     * 去掉 `android.nfc.tech.` 前缀后的技术列表。
     *
     * 做了去重是因为**真的会有重复**：北理工校园卡实测报回来的是
     * `[IsoDep, NfcA, NfcA, MifareClassic, NdefFormatable]`（部分 ROM 会重复枚举同一 tech）。
     * 重复项不携带额外信息，列出来只会让人以为有两种不同的技术。
     */
    fun techShortNames(raw: RawTag): List<String> =
        raw.techs.map { it.removePrefix(TECH_PREFIX) }.distinct()

    /** UID 的十六进制表示（大写、无分隔符、按给定顺序）。 */
    fun uidHex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02X".format(b.unsigned()) }

    /** 字节序反转 —— 卡号换算里最常见的那个坑。 */
    fun reversed(bytes: ByteArray): ByteArray = bytes.reversedArray()

    /**
     * 把 UID 换算成**几种可能的卡面号**。
     *
     * 每年校园里最常被问的一句就是「我卡上印的这串数字和手机读出来的对不上」：
     * 根子是 UID 是**低字节在前**，而印刷号往往按反序、且只取其中几个字节再转十进制。
     *
     * 所以这里**不猜是哪一种**，把候选全列出来交给用户对。
     */
    fun candidates(id: ByteArray): List<CardNoCandidate> {
        val rev = reversed(id)
        val out = mutableListOf<CardNoCandidate>()

        // ⚠️ 「整串当一个无符号整数」放在**最前面**：这是现实的校园卡里最常见的那一版，
        // 卡面上印的多半是 10 位数字。上一版只给了 3 位补零拼接（12 位）与十六进制，
        // 用户拿着真卡来对，一个都对不上 —— 这个遗漏是**实测 dump 之后**补进来的。
        out += CardNoCandidate("UID 正序当整数（十进制）", decimalOfWhole(id))
        out += CardNoCandidate("UID 反序当整数（十进制）", decimalOfWhole(rev))
        out += CardNoCandidate("UID 正序（十六进制）", uidHex(id))
        out += CardNoCandidate("UID 反序（十六进制）", uidHex(rev))
        out += CardNoCandidate("UID 正序（十进制拼接）", decimalConcat(id))
        out += CardNoCandidate("UID 反序（十进制拼接）", decimalConcat(rev))

        // 印刷号常见做法：反序后只取前 3 或前 4 个字节再转十进制。
        if (rev.size > 4) {
            out += CardNoCandidate("反序取前 4 字节（十进制）", decimalConcat(rev.take(4).toByteArray()))
        }
        if (rev.size > 3) {
            out += CardNoCandidate("反序取前 3 字节（十进制）", decimalConcat(rev.take(3).toByteArray()))
        }
        return out
    }

    /** 一次读卡的完整人话版（给诊断页用）。 */
    fun describe(raw: RawTag): CardDescription = CardDescription(
        kind = recognize(raw),
        uidSize = raw.id.size,
        supportsApdu = supportsApdu(raw),
        classicCompat = raw.techs.contains(TECH_PREFIX + "MifareClassic"),
        techShortNames = techShortNames(raw),
        historicalHex = raw.historicalBytes?.let(::uidHex),
    )

    /** 把每个字节当成一个 0~255 的数、按三位一组拼起来。
     *
     * 例：`04 A2 3F` → `"004162063"`。这是印刷号最常见的拼法（补齐三位是为了不错位）。
     */
    /**
     * 把整串字节当成一个**大端无符号整数**转十进制。
     *
     * 例：`77 75 F0 7B` → `2004218299`（10 位）。
     * ⚠️ 不能用 `Long` 手算：7 字节 UID 会溢出。这里用 `BigInteger(1, bytes)` 直接拿无符号值。
     */
    private fun decimalOfWhole(bytes: ByteArray): String =
        java.math.BigInteger(1, bytes).toString()

    private fun decimalConcat(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%03d".format(b.unsigned()) }

    /**
     * Kotlin 的 `Byte` 是有符号的（-128~127），格式化之前必须先还原成 0~255。
     *
     * 不写 `toInt() and 0xFF` 是为了避开位运算 API 的版本差异 —— 这里只想要个算术结果。
     */
    private fun Byte.unsigned(): Int = toInt().let { if (it < 0) it + 256 else it }
}
