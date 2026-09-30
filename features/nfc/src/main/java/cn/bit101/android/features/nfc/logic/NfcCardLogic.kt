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
