package cn.bit101.android.features.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import cn.bit101.android.features.nfc.logic.CardKind
import cn.bit101.android.features.nfc.logic.ClassicProbeLogic
import cn.bit101.android.features.nfc.logic.CardProbeLogic
import cn.bit101.android.features.nfc.logic.NdefShortcutLogic
import cn.bit101.android.features.nfc.logic.NfcCardLogic
import cn.bit101.android.features.nfc.logic.RawTag
import cn.bit101.android.features.nfc.logic.StudentIdScan
import java.util.concurrent.Executors

/**
 * NFC 的**框架接线层**：本机唯一直接碰 `android.nfc.*` 的地方。
 *
 * ## 为什么用 Reader Mode 而不是前台派发
 *
 * 前台派发（`enableForegroundDispatch`）要求 Activity 在 `onNewIntent` 里把 Intent
 * 转交过来 —— 那意味着要改 `MainActivity`。Reader Mode 把 `Tag` 直接回调给我们，
 * 一块充气艇改宿主 Activity 都不用动，也顺手把系统那套「要用哪个 App 打开」的弹窗压掉了。
 *
 * ## 线程
 *
 * Reader Mode 的回调跑在 Binder 线程上，而 `IsoDep.transceive` 是**阻塞**的
 * （一次几十毫秒，扫 30 个 SFI 就是一两秒）。放主线程会直接 ANR，
 * 放 Binder 线程会卡住 NFC 服务。所以这里单独开一条线程干活，结果用 `Handler` 回主线程。
 *
 * ## ⚠️ 这一层在当前开发机上**无法运行**
 *
 * ZTE NP05J 没有 NFC 芯片，`NfcAdapter.getDefaultAdapter()` 返回 `null`，
 * [start] 会直接把自己判废。所以本文件的正确性只能靠：
 * ① 它调用的所有判定都在 `logic/` 里、且都有单测；
 * ② 真机上有 NFC 的人跑一遍（见 `docs/` 待补的真机清单）。
 */
internal class NfcController(
    private val activity: Activity,
    private val onResult: (NfcScan) -> Unit,
    private val onStateChange: (Busy) -> Unit,
) {

    /**
     * 正在做的事 —— UI 靠它把「请把卡贴在手机背面」提示显示出来。
     * 这么做的原因：贴卡这件事本身没有任何进度回报，没有它用户不知道是没贴上还是卡死了。
     */
    sealed interface Busy {
        data object Idle : Busy
        data object Reading : Busy
        data object Writing : Busy
    }

    /**
     * 待写入的快捷入口载荷；`null` = 下一次贴卡按「读取」处理。
     *
     * ⚠️ 这个字段会在**别的线程**上被读，所以标 `@Volatile`。
     * 不用更重的同步是因为它的读写都是「一次赋值 + 立刻贴卡」，没有复合操作要保护。
     */
    @Volatile
    var pendingWrite: String? = null

    /** 是否在小目标上多跑一轮 SFI / FID 扫描（只对 CPU 卡有意义，且明显更慢）。 */
    @Volatile
    var withSfiScan: Boolean = false

    /**
     * 是否拿常见默认密钥去试 MIFARE Classic 扇区（只读，默认开）。
     *
     * 默认**开**是因为这是眼下最有可能挖出学号的一条路：
     * APDU 那条路实测只摸到一个借来的目录名，而校园卡的学号经常明文躺在扇区块里。
     * 认证失败不伤卡、只是白跑一次，没有理由让用户手动去打开它。
     */
    @Volatile
    var withClassicScan: Boolean = true

    /**
     * 本人学号。**只用来在探测返回里找**，不做任何网络请求、不上传。
     *
     * 为什么值得找：学号若就写在卡里，「卡号 → 学号」那层映射就不需要外部表了 ——
     * 卡自己会报出它是谁的。这是上游当年判「不可行」的根因所在，能不能翻案就看这一找。
     */
    @Volatile
    var studentId: String? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var adapter: NfcAdapter? = null

    private val callback = NfcAdapter.ReaderCallback { tag ->
        if (tag == null) return@ReaderCallback
        val payload = pendingWrite
        if (payload != null) {
            pendingWrite = null
            submit(Busy.Writing) { writeShortcut(tag, payload) }
        } else {
            submit(Busy.Reading) { scan(tag) }
        }
    }

    /**
     * 开始监听贴卡。
     *
     * @return true = 已开始；false = 这台机器没有可用的 NFC（上层要据此说明，不是静默失败）。
     */
    fun start(): Boolean {
        val nfc = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
        adapter = nfc
        if (nfc == null) return false

        runCatching {
            nfc.enableReaderMode(
                activity,
                callback,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }.onFailure { return false }
        return true
    }

    fun stop() {
        runCatching { adapter?.disableReaderMode(activity) }
        adapter = null
    }

    fun shutdown() {
        stop()
        worker.shutdown()
    }

    private fun submit(state: Busy, block: () -> NfcScan) {
        post { onStateChange(state) }
        worker.execute {
            val result = runCatching(block).getOrElse { e ->
                NfcScan(uid = ByteArray(0), kind = null, error = "读取失败：${e.message}")
            }
            post {
                onStateChange(Busy.Idle)
                onResult(result)
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ------------------------------------------------------------------ 读

    private fun scan(tag: Tag): NfcScan {
        val raw = toRawTag(tag)
        val description = NfcCardLogic.describe(raw)
        val shortcut = readShortcut(tag)

        // 三轮的顺序是有讲究的，而且**每轮各自吞掉自己的异常**：
        // ① APDU 基础轮最快、最稳，先跑；
        // ② Classic 扇区轮是当前最可能挖出学号的一轮，抢在慢轮前面；
        // ③ 深度轮（SFI + 255 个 FID）又慢又容易贴不住掉卡，放最后 ——
        //    就算它整轮崩掉，前面两轮拿到的数据也已经落袋了。
        val base = if (description.supportsApdu) {
            runCatching { probeApdu(tag, CardProbeLogic.firstRound()) }.getOrNull()
        } else null

        val classic = if (description.classicCompat && withClassicScan) {
            runCatching { probeClassic(tag) }.getOrNull()
        } else null

        val deep = if (description.supportsApdu && withSfiScan) {
            runCatching {
                probeApdu(tag, CardProbeLogic.sfiScan() + CardProbeLogic.fidScan())
            }.getOrNull()
        } else null

        val probeLines = base.orEmpty() + deep.orEmpty()

        return NfcScan(
            uid = raw.id,
            kind = description.kind,
            uidHex = NfcCardLogic.uidHex(raw.id),
            techShortNames = description.techShortNames,
            classicCompat = description.classicCompat,
            cardNoCandidates = NfcCardLogic.candidates(raw.id),
            shortcut = shortcut,
            // 用 supportsApdu 而不是 kind：判断依据就是「能不能发指令」本身，
            // 万一将来 kind 的枚举又加了一类，这里也不会漏掉能跑探测的卡。
            probe = probeLines.takeIf { it.isNotEmpty() },
            classic = classic,
            error = null,
        )
    }

    /**
     * 把框架的 `Tag` 抄进无害的 [RawTag]。
     *
     * 历史字节必须**在 `connect()` 之后**才拿得到 —— 顺手把必备的一次 connecting 在这里做掉，
     * 后面 APDU 探测再各自 connect 一次。
     */
    private fun toRawTag(tag: Tag): RawTag {
        val historical = runCatching {
            IsoDep.get(tag)?.let { iso ->
                iso.connect()
                val bytes = iso.historicalBytes
                iso.close()
                bytes
            }
        }.getOrNull()

        return RawTag(
            id = tag.id,
            techs = tag.techList.toList(),
            historicalBytes = historical,
        )
    }

    /**
     * 读标签里的第一条记录。
     *
     * 先按 URI 记录读（`toUri()`），读不出来再退一步按 UTF-8 明文读 ——
     * 有些第三方工具写出来的 NDEF 并不是标准 URI 记录，别一上来就判读不了。
     *
     * ⚠️ NDEF 消息为空是**正常**的（全新标签、或被人格式化过）：这里返回 `null`，
     * 不是报错。把它当「读失败」会让 UI 上出现假的错误提示。
     */
    private fun readShortcut(tag: Tag): String? {
        val ndef = runCatching { Ndef.get(tag) }.getOrNull() ?: return null
        return try {
            ndef.connect()
            val record = ndef.ndefMessage?.records?.firstOrNull() ?: return null
            record.toUri()?.toString() ?: String(record.payload, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    /**
     * CPU 卡的只读探测。
     *
     * ⚠️ 每条命令发出去之前都要再过一次 [CardProbeLogic.isReadOnly] ——
     * `logic/` 里生成的 APDU 原则上已经是安全的，但这层是**最后一道门**：
     * 将来谁在 `CardProbeLogic` 里填错一个字节，这道门还能兜住，不至于把卡写坏。
     */
    private fun probeApdu(tag: Tag, steps: List<CardProbeLogic.ProbeStep>): List<ProbeLine> {
        val iso = runCatching { IsoDep.get(tag) }.getOrNull() ?: return emptyList()

        // 先收集「原始回答」，因为下一轮要发哪些命令**取决于上一轮回答里有什么**
        val raws = mutableListOf<Pair<CardProbeLogic.ProbeStep, CardProbeLogic.ProbeResult>>()

        try {
            iso.connect()
            steps.forEach { step ->
                raws += step to transceive(iso, step)
                // 「选中了就读一段」：只在成功时跟发，且**紧接着**发 ——
                // 中间夹一次别的 SELECT，当前文件就换了，读到的就不是这个文件的东西。
                val next = step.followUp
                if (next != null && raws.last().second.success) {
                    val sub = CardProbeLogic.ProbeStep(
                        label = step.followUpLabel ?: "「${step.label}」接着读",
                        apdu = next,
                    )
                    raws += sub to transceive(iso, sub)
                }
            }

            // 目录记录里若有 ADF 名（tag 4F = AID），直接跟着 SELECT 一次 ——
            // 用户贴一次卡不容易，能多挖一层就多挖一层。
            val aids = CardProbeLogic.aidsOf(raws.flatMap { CardProbeLogic.parseTlvs(it.second.data) })
                .take(3)
            aids.forEach { aid ->
                val hex = CardProbeLogic.toHexCompact(aid)
                val step = CardProbeLogic.ProbeStep(
                    "SELECT 目录里发现的 AID $hex",
                    CardProbeLogic.selectByName(hex),
                )
                raws += step to transceive(iso, step)
            }
        } finally {
            runCatching { iso.close() }
        }

        val sid = studentId
        return raws.map { (step, r) ->
            ProbeLine(
                label = step.label,
                apdu = CardProbeLogic.toHex(step.apdu),
                sw = r.sw,
                swText = r.swText,
                data = CardProbeLogic.toHex(r.data),
                studentIdHit = sid?.let { StudentIdScan.find(r.data, it) },
                tlvs = CardProbeLogic.parseTlvs(r.data).map(::tlvText),
            )
        }
    }

    /**
     * MIFARE Classic 侧的**只读**扇区探测：拿常见默认密钥去认证，过了才读块。
     *
     * ## ⚠️⚠️ 这个方法的红线
     *
     * 只调 `authenticateSectorWithKeyA/B` 与 `readBlock`。
     * **不许**在这里出现 `writeBlock` / `increment` / `decrement` / `restore` / `transfer`，
     * 也不许拿 `MifareClassic.transceive` 发任何非只读指令 ——
     * 校园卡是别人的资产，写坏了没有厂商密钥就恢复不了。
     *
     * ## 认证失败为什么不继续
     *
     * 过不了就是过不了。我们**不去破密钥**（不跑嵌套认证攻击之类的东西）：
     * 那既是在撬别人的门锁，又几乎肯定徒劳（校园卡扇区密钥多是发卡时随机的）。
     * 如实回报「默认密钥都没过」，让用户知道这条路走到了头，比给一个假希望有用。
     */
    private fun probeClassic(tag: Tag): List<ClassicSectorLine> {
        val mc = runCatching { MifareClassic.get(tag) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ClassicSectorLine>()

        try {
            mc.connect()

            val size = runCatching { mc.size }.getOrNull() ?: 0
            val totalBlocks = runCatching { mc.blockCount }.getOrNull() ?: 0
            if (totalBlocks <= 0) {
                return listOf(
                    ClassicSectorLine(
                        sector = -1,
                        key = null,
                        blocks = emptyList(),
                        note = "拿不到卡容量（size=$size），跳过扇区探测",
                    ),
                )
            }

            val planned = ClassicProbeLogic.plan(size).filter { it.block < totalBlocks }
            val sid = studentId

            planned.groupBy { it.sector }.forEach { (sector, reads) ->
                val key = authenticate(mc, sector)
                if (key == null) {
                    out += ClassicSectorLine(
                        sector = sector,
                        key = null,
                        blocks = emptyList(),
                        note = "默认密钥都没通过（到此为止，不会去破密钥）",
                    )
                    return@forEach
                }

                val blocks = reads.mapNotNull { r ->
                    val data = runCatching { mc.readBlock(r.block) }.getOrNull()
                        ?: return@mapNotNull null
                    val view = ClassicProbeLogic.decode(data)
                    ClassicBlockLine(
                        block = r.block,
                        indexInSector = r.indexInSector,
                        hex = view.hex,
                        ascii = view.ascii,
                        bcdDigits = view.bcdDigits,
                        blank = ClassicProbeLogic.isBlank(data),
                        studentIdHit = sid?.let { StudentIdScan.find(data, it) },
                    )
                }
                out += ClassicSectorLine(sector = sector, key = key, blocks = blocks, note = null)
            }
        } finally {
            runCatching { mc.close() }
        }
        return out
    }

    /**
     * 用候选密钥挨个试某个扇区，Key A 不成再试 Key B。
     *
     * @return 命中的密钥描述（`名字(十六进制) KeyA/KeyB`）；全都没过返回 `null`。
     *
     * ⚠️ `authenticateSectorWithKey*` 的返回值与异常都要接住：
     * 有的 ROM 回 `false`，有的直接抛 `IOException`（标签掉了 / 参数越界），
     * 任何一种都算「这一个密钥不对」，继续试下一个，不能让整个探测崩在这里。
     */
    private fun authenticate(mc: MifareClassic, sector: Int): String? {
        for (candidate in ClassicProbeLogic.DEFAULT_KEYS) {
            val key = CardProbeLogic.parseHex(candidate.hex)
            for (useB in booleanArrayOf(false, true)) {
                val ok = runCatching {
                    if (useB) mc.authenticateSectorWithKeyB(sector, key)
                    else mc.authenticateSectorWithKeyA(sector, key)
                }.getOrDefault(false)
                if (ok) {
                    return "${candidate.name} ${candidate.hex} ${if (useB) "KeyB" else "KeyA"}"
                }
            }
        }
        return null
    }

    /**
     * 发一条命令并解析回答。
     *
     * ⚠️ 这里再过一次 [CardProbeLogic.isReadOnly] 是**最后一道门**：
     * `logic/` 生成的 APDU 原则上已经安全，但将来谁在那边填错一个字节，
     * 这道门还能兜住，不至于把卡写坏。
     */
    /**
     * 一个 TLV 的人话版：`84=31504159… "1PAY.SYS.DDF01"`。
     *
     * 值**全是可打印 ASCII** 时顺手把原文附上 —— 卡名、应用名这类字段就是靠这个
     * 一眼认出来的，否则人得自己把 `31 50 41 59` 译成 `1PAY`。
     */
    private fun tlvText(t: CardProbeLogic.Tlv): String {
        val hex = CardProbeLogic.toHexCompact(t.value)
        val ascii = if (t.value.size >= 2 && t.value.all { it.toInt() in 0x20..0x7E }) {
            " \"" + String(t.value, Charsets.US_ASCII) + "\""
        } else ""
        return "${t.tag}=$hex$ascii"
    }

    private fun transceive(
        iso: IsoDep,
        step: CardProbeLogic.ProbeStep,
    ): CardProbeLogic.ProbeResult {
        if (!CardProbeLogic.isReadOnly(step.apdu)) {
            return CardProbeLogic.ProbeResult(
                sw = "", swText = "被安全闸门拦下（拒绝发出）",
                data = ByteArray(0), success = false, hasMore = false,
            )
        }
        val raw = runCatching { iso.transceive(step.apdu) }.getOrNull()
            ?: return CardProbeLogic.ProbeResult(
                sw = "", swText = "transceive 失败",
                data = ByteArray(0), success = false, hasMore = false,
            )
        return CardProbeLogic.parseResponse(raw)
    }

    // ------------------------------------------------------------------ 写

    /**
     * 往贴纸里写一条快捷入口。
     *
     * ## ⚠️⚠️ 第一道红线：不许往校园卡上写
     *
     * 真卡实测发现校园卡的 techList 里**有 `NdefFormatable`**（NDEF 区是空白、可格式化）——
     * 它的字面意思是「可以拿来当标签用」，但这类卡同时**有 `MifareClassic`**。
     * 对 MIFARE Classic 写 NDEF（尤其是 `NdefFormatable.format()`）会**重写扇区尾块**，
     * 也就是密钥 A/B 与存取位；一旦弄坏，学校的读卡器就不认这张卡了，
     * 而我们手里没有厂商密钥，**无法恢复**。
     *
     * 所以这里先拦一道：只要还带 Classic tech，就一律不写 ——
     * 「能写」和「该写」是两回事，省一条分支换来一张废卡不值得。
     * 快捷入口请写在我们自己的 NTAG 贴纸上（`Ndef` / 纯 `NdefFormatable` 的那种）。
     */
    private fun writeShortcut(tag: Tag, payload: String): NfcScan {
        val techs = tag.techList.toList()

        fun refused(why: String) = NfcScan(
            uid = tag.id,
            kind = null,
            uidHex = NfcCardLogic.uidHex(tag.id),
            techShortNames = techs.map { it.substringAfterLast('.') }.distinct(),
            cardNoCandidates = emptyList(),
            shortcut = null,
            probe = null,
            error = null,
            writeOutcome = why,
        )

        if (techs.contains("android.nfc.tech.MifareClassic")) {
            return refused(
                "拒绝写入：这张卡带 MIFARE Classic 扇区（校园卡就是这种）。" +
                    "往 Classic 卡写 NDEF 会重写扇区尾块，可能把它弄成废卡。" +
                    "快捷入口请写在自己的 NTAG 贴纸上。"
            )
        }

        val record = NdefRecord.createUri(payload)
        val aar = NdefRecord.createApplicationRecord(activity.packageName)
        val message = NdefMessage(arrayOf(record, aar))

        val outcome = runCatching {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    ndef.writeNdefMessage(message)
                } finally {
                    ndef.close()
                }
                "写入成功"
            } else {
                // 全新的贴纸要先格式化
                val blank = NdefFormatable.get(tag)
                    ?: return@runCatching "这张标签既不可写也无法格式化（可能是只读卡）"
                blank.connect()
                try {
                    blank.format(message)
                } finally {
                    blank.close()
                }
                "格式化并写入成功"
            }
        }.getOrElse { e -> "写入失败：${e.message}" }

        return refused(outcome)
    }
}

/**
 * 一次读卡 / 一次写入的结果。
 *
 * 之所以**所有字段都有默认值**：这一层在真机上很可能半途失败，
 * UI 应该显示「拿到了什么」而不是因为某个字段缺失整个崩掉。
 */
internal data class NfcScan(
    val uid: ByteArray,
    val kind: CardKind?,
    val uidHex: String = "",
    val techShortNames: List<String> = emptyList(),
    /** 双界面卡（同时有 Classic 兼容层）；UI 要明说是这种卡，写入流程会据此拒绝。 */
    val classicCompat: Boolean = false,
    val cardNoCandidates: List<cn.bit101.android.features.nfc.logic.CardNoCandidate> = emptyList(),
    val shortcut: String? = null,
    val probe: List<ProbeLine>? = null,
    /** MIFARE Classic 侧的只读扇区探测结果；这张卡没有 Classic 兼容层时是 `null`。 */
    val classic: List<ClassicSectorLine>? = null,
    val error: String? = null,
    val writeOutcome: String? = null,
)

/**
 * Classic 一个扇区的探测结果。
 *
 * @param key 认证成功的那个密钥（`null` = 全都没过）。
 * @param blocks 读出来的块；认证失败时为空。
 * @param note 需要额外说明的一句话（认证失败、拿不到容量等）。
 */
internal data class ClassicSectorLine(
    val sector: Int,
    val key: String?,
    val blocks: List<ClassicBlockLine>,
    val note: String?,
)

/**
 * Classic 一个块的读值。
 *
 * 三种**并列**的读法都给出来（hex / ascii / BCD 数字串），因为没人预先知道学号
 * 是用哪种方式存的 —— 让人一眼扫过去，比我们替他挑一种要好。
 */
internal data class ClassicBlockLine(
    val block: Int,
    val indexInSector: Int,
    val hex: String,
    val ascii: String?,
    val bcdDigits: String?,
    /** 全零块：多半是没写过的空块，UI 会折叠掉。 */
    val blank: Boolean,
    /** 在这个块里**找到了学号**（含编码形式与偏移）；没找到是 `null`。 */
    val studentIdHit: String?,
)

internal data class ProbeLine(
    val label: String,
    val apdu: String,
    val sw: String,
    val swText: String?,
    val data: String?,
    /** 在这条返回里**找到了学号**（含编码形式与偏移）；没找到是 `null`。 */
    val studentIdHit: String? = null,
    /** 返回的 TLV 摊平后的 `标签=值`，如 `84=315041592E...`。人肉看十六进制太累。 */
    val tlvs: List<String> = emptyList(),
)
