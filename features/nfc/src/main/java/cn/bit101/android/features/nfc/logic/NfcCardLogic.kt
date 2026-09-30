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
     * ## 顺序为什么是这样
     *
     * Type 4 标签会同时报 `IsoDep` 和 `Ndef`：此时它**确实能发 APDU**，
     * 对我们更有用的是把它当 [CardKind.CPU_CARD] 去跑 SELECT 探测 ——
     * 当成贴纸处理的话，APDU 这条唯一的取数路就断了。
     * 所以 `IsoDep` 的判断要排在 `Ndef` **前面**。
     */
    fun recognize(raw: RawTag): CardKind = when {
        raw.techs.contains(TECH_PREFIX + "MifareClassic") -> CardKind.MIFARE_CLASSIC
        raw.techs.contains(TECH_PREFIX + "IsoDep") -> CardKind.CPU_CARD
        raw.techs.contains(TECH_PREFIX + "NfcV") -> CardKind.NFC_V
        raw.techs.any { it == TECH_PREFIX + "Ndef" || it == TECH_PREFIX + "NdefFormatable" } ->
            CardKind.NFC_FORUM_TAG
        else -> CardKind.UNKNOWN
    }

    /** 能不能对它发 ISO 7816-4 指令。 */
    fun supportsApdu(raw: RawTag): Boolean =
        raw.techs.contains(TECH_PREFIX + "IsoDep")

    /** 把 techList 里的全限定名压成短名，便于人读。不是本模块认识的 tech 原样返回。 */
    fun techShortNames(raw: RawTag): List<String> =
        raw.techs.map { it.removePrefix(TECH_PREFIX) }

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
        techShortNames = techShortNames(raw),
        historicalHex = raw.historicalBytes?.let(::uidHex),
    )

    /**
     * 把每个字节当成一个 0~255 的数、按三位一组拼起来。
     *
     * 例：`04 A2 3F` → `"004162063"`。这是印刷号最常见的拼法（补齐三位是为了不错位）。
     */
    private fun decimalConcat(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%03d".format(b.unsigned()) }

    /**
     * Kotlin 的 `Byte` 是有符号的（-128~127），格式化之前必须先还原成 0~255。
     *
     * 不写 `toInt() and 0xFF` 是为了避开位运算 API 的版本差异 —— 这里只想要个算术结果。
     */
    private fun Byte.unsigned(): Int = toInt().let { if (it < 0) it + 256 else it }
}
