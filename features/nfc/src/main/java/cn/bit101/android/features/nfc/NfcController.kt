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
import cn.bit101.android.features.nfc.logic.BcdScan
import cn.bit101.android.features.nfc.logic.CardIdentityLogic
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
     * 目录下钻的**最大层数**。
     *
     * 递归必须有硬上限：卡要是把目录组织成环，没有上限就是无限循环，
     * 而贴卡时用户看到的只会是「App 卡住了」。两层足够覆盖真卡实测的结构。
     */
    private val MAX_DRILL_DEPTH = 2

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

    /**
     * 是否跑**完整的深度扫描**（只对 CPU 卡有意义，且明显更慢）。
     *
     * - `false`（默认）：**快速读** —— 只碰文件号 `0001`~`001F`，几十条命令，一秒内出结果。
     *   真卡上有数据的文件全在那一段里，日常要的就是它。
     * - `true`：SFI 1~30 + 文件号扫到 `00FF` + 目录下钻，400 条左右。
     *   换别的学校的卡、或想翻卡里还有没有别的东西时才需要。
     *
     * ⚠️ 收窄范围的代价是「可能什么都找不到」，所以上层必须把「快速读没认出人」
     * 这种情况说出来（提示开深度扫描），不能给一个空结果就完事。
     */
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

        // 第二轮有两种规模，由 [withSfiScan] 决定 —— 默认是**小的那个**：
        // - 关（默认）= 快速读：只碰低区文件号，几十条命令、一秒内出姓名学号。
        //   真卡上有数据的文件全落在 `0010`~`001B`，日常要的就是那一段；
        //   而完整那一轮绝大多数命令回的是「找不到这个文件」，纯粹是让用户白按住几秒。
        // - 开 = 深度读：SFI 1~30 + 文件号扫到 00FF + 目录下钻，共 400 条左右。
        //   换别的学校的卡、或想翻卡里还有没有别的东西时才打开。
        val extra = if (description.supportsApdu) {
            runCatching {
                if (!withSfiScan) {
                    probeApdu(tag, CardProbeLogic.quickScan(), drilldown = false)
                } else {
                    // 深度轮的顺序是**真卡实测**定下来的，四步各有理由：
                    // ① 先 SELECT MF 把上下文钉死 —— 带 SFI 的 READ BINARY 是「相对当前 DF」的，
                    //    不先选一次主文件，扫出来的结果是上一次操作残留的上下文，不可复现。
                    // ② SFI 扫描（30 条，快）排最前：实测 1..30 里有 12 个有响应，
                    //    性价比最高 —— 含那个回 `6982`「要认证」的 SFI=4。
                    // ③ FID 扫描（255 条，慢）跟上：命中率低，但覆盖的是另一个命名空间，
                    //    真卡上的目录（DF）`0010` 就是它先认出来的。
                    // ④ 目录下钻放最后：DF 是在③里认出来的，认出来才有得进。
                    probeApdu(
                        tag,
                        listOf(
                            CardProbeLogic.ProbeStep(
                                "SELECT 主文件 3F00（深度轮起点）",
                                CardProbeLogic.selectMasterFile(),
                            ),
                        ) + CardProbeLogic.sfiScan() + CardProbeLogic.fidScan(),
                        drilldown = true,
                    )
                }
            }.getOrNull()
        } else null

        val probeLines = base.orEmpty() + extra.orEmpty()

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
    private fun probeApdu(
        tag: Tag,
        steps: List<CardProbeLogic.ProbeStep>,
        drilldown: Boolean = false,
    ): List<ProbeLine> {
        val iso = runCatching { IsoDep.get(tag) }.getOrNull() ?: return emptyList()
        val sid = studentId
        val rawLines = mutableListOf<RawLine>()
        // 选中了、却吃不下 READ BINARY 的文件 ⇒ 疑似目录，稍后进去再扫一层。
        val directorySuspects = mutableListOf<Int>()

        try {
            iso.connect()
            steps.forEach { runStep(iso, it, sid, rawLines, directorySuspects) }

            // 目录记录里若有 ADF 名（tag 4F = AID），直接跟着 SELECT 一次 ——
            // 用户贴一次卡不容易，能多挖一层就多挖一层。
            val aids = rawLines.flatMap { CardProbeLogic.parseTlvs(it.bytes) }
                .let(CardProbeLogic::aidsOf)
                .take(3)
            aids.forEach { aid ->
                val hex = CardProbeLogic.toHexCompact(aid)
                rawLines += exchange(
                    iso,
                    "SELECT 目录里发现的 AID $hex",
                    CardProbeLogic.selectByName(hex),
                    sid,
                )
            }

            if (drilldown) drillIntoDirectories(iso, directorySuspects.distinct(), sid, rawLines)
        } finally {
            runCatching { iso.close() }
        }

        return rawLines.map { it.toLine() }
    }

    /**
     * 发一条 [CardProbeLogic.ProbeStep]，并且把命中之后该做的事**都**做完。
     *
     * ## 为什么必须抽出来（第五次真卡实测）
     *
     * 原来这段逻辑写在 [probeApdu] 的 `steps.forEach` 里，而下钻目录的
     * [drillIntoDirectories] **另写了一遍**发命令的循环 —— 于是它漏掉了两件事，
     * 而且两件都在真卡上丢了数据：
     *
     * |漏掉的| 真卡上的后果 |
     * |---|---|
     * | `6981` → 改读 `READ RECORD` | 目录内 `0002`（= `SFI=2`）也是定长记录文件，回 `6981`，**记录一条没读到** |
     * | 命中后连读第二/第三段 | 目录内 `SFI=7` 读回 32 字节就被当成读完了，**后面截断了** |
     *
     * ⚠️ 教训和上次那个 P1 编码 bug **是同一个**：
     * 「主命令回了 9000」不等于「这个文件读完了」，而**抄一份逻辑就会漏掉其中的分支**。
     * 同一套后续动作只能有一份实现 —— 谁需要就跟谁调，不要各自重写。
     *
     * @param labelPrefix 打在标签前面的前缀。目录下钻要标出「目录 0010 内 …」，
     *   不然用户分不清哪条是哪层读出来的。
     */
    private fun runStep(
        iso: IsoDep,
        step: CardProbeLogic.ProbeStep,
        sid: String?,
        rawLines: MutableList<RawLine>,
        directorySuspects: MutableList<Int>,
        labelPrefix: String = "",
    ) {
        val out = exchange(iso, labelPrefix + step.label, step.apdu, sid)
        rawLines += out

        // ⚠️⚠️ **主命令自己**回 6981 / 6986 时，必须在这里就处理 ——
        // 不能等followUp。原因：「接着读」只在首段 9000 时才发
        // （`proceed` 那个判断），而 `READ BINARY SFI=2` 回的正是 6981。
        // 等于说：首段失败 ⇒ 提前 return ⇒ `shouldTryRecords(out.sw)` 那一支**永远够不着**。
        //
        // 这个 bug 有两种表现，都真卡实测撞出来的：
        // ① `SFI=2` 回 6981（记录文件）却**一条回退命令都没发** ——
        //    而同层`0002` 走 FID 路发了 5 条（虽然也失败，见下）。
        // ② MF 下 `0018` 之所以能读到记录，纯粹是因为它的 `followUp` 存在、
        //    SELECT 先回了 9000，才**侥幸**走到了回退那一支。
        //    换句话说 `0018` 是运气好，不是设计对。
        //
        // ⇒ 教训：**回退的判断对象是「命令的结果」，而命令可能有好几条**
        // （主命令、接着读、续读），**每一条的结果都要单独判一次**。
        val mainLabel = labelPrefix + step.label
        when {
            CardProbeLogic.looksLikeDirectory(out.sw) -> {
                step.fid?.let { directorySuspects += it }
                return
            }

            CardProbeLogic.shouldTryRecords(out.sw) -> {
                readRecords(iso, step, sid, rawLines, mainLabel)
                return
            }
        }

        // 「接着读」的触发条件**只有一条**：第一段成功。
        //
        // ⚠️ 这里原先还挂着「首段全零就不再往下读」（由 `ProbeStep.followUpIfData` 带来，
        // 而那个开关只有 SFI 那一轮设了）。第十次实测把它删了：
        // 目录内 `0008` 与 `SFI=8` 是**同一个文件**，却因为开关只在一边生效，
        // 读出 48 / 32 两种长度。而「首段是零」从来不等于「后面没有」——
        // `0016` 的姓名在第 1 段、学号在第 2 段。
        val followUp = step.followUp
        if (followUp == null || out.sw != "9000") return

        // 「选中了就读一段」：只在选中成功时跟发，且**紧接着**发 ——
        // 中间夹一次别的 SELECT，当前文件就换了，读到的就不是这个文件的东西。
        val followLabel = labelPrefix + (step.followUpLabel ?: "「${step.label}」接着读")
        var r = exchange(iso, followLabel, followUp, sid)
        rawLines += r

        when {
            // ⚠️ **目录这一支必须排在「改读记录」前面**。
            // `6986` 与 `6981` 都是「命令用错了」，但处置完全不同：
            // `6986` 是「你选中的是目录」，对它发 READ RECORD 一样白费 ——
            // 真卡 `0010` 上就这么白白发了 5 条，全回 6986。
            CardProbeLogic.looksLikeDirectory(r.sw) -> {
                step.fid?.let { directorySuspects += it }
            }

            // 同上：这条是 followUp 的结果，也要单独判一次。
            CardProbeLogic.shouldTryRecords(r.sw) -> {
                readRecords(iso, step, sid, rawLines, followLabel)
            }

            else -> {
                // 一路往下读：文件多长事先不知道，
                // 读了一段就断定「没有更多」是不成立的。
                step.followUpMore.forEachIndexed { index, more ->
                    if (r.sw != "9000") return@forEachIndexed
                    r = exchange(iso, "$followLabel（续读第 ${index + 2} 段）", more, sid)
                    rawLines += r
                }
            }
        }
    }

    /**
     * 「这条不吃 READ BINARY，改读它的记录」—— 挨个记录号读，读到哪算哪。
     *
     * ## 按 SFI 还是按当前 EF：取决于这条命令**自己是怎么来的**，只有一种正确解
     *
     * - **带 `sfi` 的步骤**（[CardProbeLogic.sfiScan] 来的）：发 `READ BINARY` 时压根
     *   没 SELECT 过任何文件，「当前 EF」是谁说不清 ⇒ 必须按 SFI 寻址
     *   （[CardProbeLogic.readRecord]，`P2 = (SFI << 3) | 0x04`）。真卡 `SFI=2` 走这条。
     * - **不带 `sfi` 的步骤**（[CardProbeLogic.fidScan] 来的，刚 SELECT 过文件）：
     *   按当前 EF 寻址（[CardProbeLogic.readRecordCurrentEf]）。真卡 `0018` 就是这么读通的。
     *
     * ## ⚠️ 为什么不再「两种都试一遍」（第十次实测）
     *
     * 上一版在发完一轮失败后，还会再补发一轮。但那个补发的条件写的是 `sfi != null`，
     * 而第一轮**对带 SFI 的步骤本来就是按 SFI 发的** —— 于是补发出去的 5 条与第一轮
     * **逐字节相同**；反过来，不带 SFI 的步骤因为 `sfi == null`，补发分支**永远进不去**。
     *
     * ⇒ 那个补发在任何情况下都产生不了新信息，只是把真卡 `SFI=2` 的回退变成 10 条重复记录
     * （dump 里那两组标题不同、APDU 完全一样的行就是它）。
     * 判断对象与现象不是同一个东西 —— 这个毛病在本文件里已经是第三次发作了。
     *
     * @param baseLabel 触发回退的那条命令的标签，用于把记录行挂到它下面。
     */
    private fun readRecords(
        iso: IsoDep,
        step: CardProbeLogic.ProbeStep,
        sid: String?,
        rawLines: MutableList<RawLine>,
        baseLabel: String,
    ) {
        val sfi = step.sfi
        val bySfi = { rec: Int ->
            if (sfi != null) CardProbeLogic.readRecord(record = rec, sfi = sfi)
            else CardProbeLogic.readRecordCurrentEf(rec)
        }

        // 读到哪算哪：记录文件有几条事先不知道，
        // 读不到下一条的卡会自己回错误，不会白读。
        for (rec in 1..5) {
            rawLines += exchange(iso, "改读记录：$baseLabel 记录 $rec", bySfi(rec), sid)
        }
    }

    /**
     * 进目录（DF）里再扫一层。
     *
     * ## 为什么必须做
     *
     * 真卡 `0010` 的 FCI 是 `6F 18 84 10 D1560001…1002 A5 04 9F 08 01 02` ——
     * `6F` 底下挂 `84`（16 字节 DF 名）和 `A5`，这是**目录**的结构；
     * 普通文件的 FCI 里是 `80`/`82`/`83` 那一套。所以 `READ 0010` 回 `6986` 是对的：
     * 目录不是文件，不能直接读。**它的内容在它自己那一层里。**
     *
     * ⚠️ 进去之后 `READ BINARY` 要改用 **SFI** 而不是 FID（EMV 的一贯做法），
     * 所以这里两种都试：先沿 SFI 扫（这是规范里的正路），再扫一小段 FID 兜底。
     *
     * 成本：每个目录约 35 条只读命令。所以**每层最多进 2 个**目录 ——
     * 用户贴在手机上不动的那几秒是有限的，不能无节制地贪。
     *
     * @param depth 当前在第几层（顶层传 0）。**最多两层**：
     *   实测真卡的目录里没有再冒出目录，但递归必须有硬上限 ——
     *   卡要是故意造出环，没有上限就是无限循环，而贴卡时用户只会看到 App 卡死。
     */
    private fun drillIntoDirectories(
        iso: IsoDep,
        fids: List<Int>,
        sid: String?,
        rawLines: MutableList<RawLine>,
        depth: Int = 0,
    ) {
        // 两层封顶。宁可少挖一层，也不冒「贴卡时App 卡住」这个风险。
        if (depth >= MAX_DRILL_DEPTH) return

        fids.take(2).forEach { fid ->
            val tag = "%04X".format(fid)
            rawLines += exchange(
                iso,
                "重新进入目录 $tag",
                CardProbeLogic.selectByFileId(fid),
                sid,
            )

            // ⚠️ 目录内的两条扫描都交给 [runStep] —— 自己重写一遍发命令的循环，
            // 就是第五次实测那两个 bug 的成因（漏了 6981 回退、漏了连读第二段）。
            // 目录内挖到的目录也回填，让「目录套目录」也能继续往下走。
            val nested = mutableListOf<Int>()

            // 目录内按 SFI 找文件：这才是规范里的正路。
            CardProbeLogic.sfiScan(1..20).forEach { step ->
                runStep(iso, step, sid, rawLines, nested, "目录 $tag 内 ")
            }

            // 有些卡在目录内也编号文件，兜一段低区。
            CardProbeLogic.fidScan(0x0001..0x000F).forEach { step ->
                runStep(iso, step, sid, rawLines, nested, "目录 $tag 内 ")
            }

            // 目录里再认出目录就再进一层 —— 但**只进一层**：
            // 递归下去既可能打转，贴卡的那几秒也撑不住。
            val deeper = (nested.distinct() - fid).take(1)
            if (deeper.isNotEmpty()) {
                drillIntoDirectories(iso, deeper, sid, rawLines, depth + 1)
            }
        }
    }

    /**
     * 一条已经发出去并拿到回答的命令（还没翻译成人能看的 [ProbeLine]）。
     *
     * 中间这一层存在的原因：`probeApdu` 要**先看看返回里有什么**才能决定下一步
     * （TLV 里挖 AID、`6981` 要改读记录），所以原始字节得先留着。
     */
    private data class RawLine(
        val label: String,
        val apdu: String,
        val sw: String,
        val swText: String?,
        val bytes: ByteArray,
        val note: String?,
        val studentIdHit: String?,
        /** 没填学号时**猜**出来的学号（纯数字）；填过学号就恒为 `null`（见 [exchange]）。 */
        val guessedStudentId: String?,
        /** 上面那个猜值是怎么来的（形式 + 偏移），给人核对用。 */
        val guessedStudentIdNote: String?,
    ) {
        fun toLine(): ProbeLine = ProbeLine(
            label = label,
            apdu = apdu,
            sw = sw,
            swText = swText,
            data = CardProbeLogic.toHex(bytes),
            note = note,
            text = CardProbeLogic.decodeText(bytes),
            studentIdHit = studentIdHit,
            guessedStudentId = guessedStudentId,
            guessedStudentIdNote = guessedStudentIdNote,
            tlvs = CardProbeLogic.parseTlvs(bytes).map(::tlvText),
            // BCD 日期扫一遍：卡里的有效期与交易时间都是 BCD，
            // 不点出来的话那串 `20 26 03 22 11 08 43` 没人会意识到是时间。
            dates = BcdScan.scan(bytes).map { it.display },
        )
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
     * 发一条命令、拿到回答，并且在必要时**自动接着谈下去**。
     *
     * ## 为什么必须自动跟 —— 真卡实测两次吃亏
     *
     * - 卡回 `6C xx`（「你 Le 写错了，正确的是 xx」）：
     *   北理工校园卡上 `READ 文件 0015` 回的就是 `6C1E`。不按 0x1E 重发，
     *   那个 30 字节的文件里一个字节都拿不到。
     * - 卡回 `61 xx`（「数据还有 xx 字节」）：标准要求再发一次 `GET RESPONSE`。
     *
     * 这两种回答**都不是失败**，卡在说「话还没说完」。把它们当错误处理，
     * 表现出来就是「这卡读不出来」，而实际上数据就在那儿。
     *
     * ⚠️ 每一轮补发之前都再过一次只读闸门（[CardProbeLogic.isReadOnly]）——
     * `GET RESPONSE` 与改 Le 都只是读，闸门必须仍然认。
     */
    private fun exchange(
        iso: IsoDep,
        label: String,
        apdu: ByteArray,
        studentId: String?,
    ): RawLine {
        var current = apdu
        var note: String? = null
        var result = send(iso, current)

        // 最多谈三轮：正常卡一轮就够，三轮是防呆（别让坏卡把我们卡在死循环里）。
        var guard = 0
        while (guard++ < 3) {
            val next: ByteArray = when {
                result.hasMore -> {
                    val n = CardProbeLogic.remainingOf(result.sw) ?: break
                    note = listOfNotNull(note, "卡说还有 $n 字节，发 GET RESPONSE 取").joinToString("；")
                    CardProbeLogic.getResponse(n)
                }

                result.correctLe != null -> {
                    val n = result.correctLe
                    note = listOfNotNull(note, "卡说 Le 该是 $n，按它重发").joinToString("；")
                    runCatching { CardProbeLogic.withLe(current, n) }.getOrNull() ?: break
                }

                else -> break
            }
            current = next
            result = send(iso, current)
        }

        // 两条发现途径，**同一时刻只走一条**：
        // - 用户填了学号 ⇒ 只报「核对过的命中」，不去猜。
        //   既省一次扫描，也避免同一条数据上并排出现「学号 X」与「疑似学号 Y」——
        //   用户已经知道自己的学号了，再给一个猜的只会让人怀疑哪个才作数。
        // - 没填学号 ⇒ 猜。这正是「不填也能读出学号」那条路。
        val hit = studentId?.let { StudentIdScan.find(result.data, it) }
        val guess = if (studentId == null) StudentIdScan.guess(result.data) else null

        return RawLine(
            label = label,
            apdu = CardProbeLogic.toHex(current),
            sw = result.sw,
            swText = result.swText,
            bytes = result.data,
            note = note,
            studentIdHit = hit,
            guessedStudentId = guess?.value,
            guessedStudentIdNote = guess?.note,
        )
    }

    /**
     * 发**一条**命令并解析回答。⚠️ 这是唯一一个真正碰 `transceive` 的地方，
     * 也是最后一道只读闸门 —— 上面所有路径都得从这儿过。
     */
    private fun send(iso: IsoDep, apdu: ByteArray): CardProbeLogic.ProbeResult {
        if (!CardProbeLogic.isReadOnly(apdu)) {
            return CardProbeLogic.ProbeResult(
                sw = "", swText = "被安全闸门拦下（拒绝发出）",
                data = ByteArray(0), success = false,
            )
        }
        val raw = runCatching { iso.transceive(apdu) }.getOrNull()
            ?: return CardProbeLogic.ProbeResult(
                sw = "", swText = "transceive 失败",
                data = ByteArray(0), success = false,
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

/**
 * 一条探测流水。
 *
 * 实现 [CardIdentityLogic.ProbeLineLike] 的理由：汇总「这张卡是谁」时只需要
 * 文本 / 学号命中 / 疑似日期这三样，交给那个窄接口即可，不必为了汇总而新造结构。
 */
internal data class ProbeLine(
    val label: String,
    val apdu: String,
    val sw: String,
    val swText: String?,
    val data: String?,
    /** 与卡「继续谈」的过程说明（`6Cxx` 改 Le 重发、`61xx` 取 GET RESPONSE）。 */
    val note: String? = null,
    /**
     * 把这段返回当文本读出来的结果（GBK）。
     *
     * 真卡实测的价值：`0016` 文件里存的是姓名，不加这一栏，
     * 用户和我们看到的就只是一串 `B8 DF CC EC CF E8`。
     */
    override val text: String? = null,
    /** 在这条返回里**找到了学号**（含编码形式与偏移）；没找到是 `null`。 */
    override val studentIdHit: String? = null,
    /**
     * **没填学号**时猜出来的学号（含形式与偏移，如 `1120241355（分段形式…）`）。
     *
     * ⚠️ 它永远是**疑似**：判据是格式假设（长度 + 入学年份），不是卡里的事实。
     * 与 [studentIdHit] 不会同时出现 —— 填过学号就只报核对结果（见 `exchange`）。
     */
    override val guessedStudentId: String? = null,
    /** [guessedStudentId] 是怎么来的（形式 + 偏移）。 */
    override val guessedStudentIdNote: String? = null,
    /** 返回的 TLV 摊平后的 `标签=值`，如 `84=315041592E...`。人肉看十六进制太累。 */
    val tlvs: List<String> = emptyList(),
    /**
     * 这段返回里**疑似 BCD 日期**，已带偏移，如 `偏移 16：2026-03-22 11:08:43`。
     *
     * 卡里把日期存成 BCD 是常态，但人看十六进制看不出来 —— 真卡 `0018` 的记录里
     * 就有一条 `20 26 03 22 11 08 43`。⚠️ 措辞必须留「疑似」：BCD 三字节凑出一个
     * 合法时刻太容易，界面把它当**线索**展示，不能当结论。
     */
    override val dates: List<String> = emptyList(),
) : CardIdentityLogic.ProbeLineLike {
    /**
     * 这一条**真的拿到了东西**（不是只有状态字、也不是一整段零）。
     *
     * 用途：一次深度扫描会产生三四百条记录，绝大多数是「找不到这个文件」。
     * 全平铺出来会把人淹掉，所以 UI 默认只列有数据的，同时**如实标出总条数**，
     * 想看全的可以一键展开 —— 既不是把信息藏起来，也不是把用户埋掉。
     */
    val hasData: Boolean
        get() = studentIdHit != null ||
            guessedStudentId != null ||
            text != null ||
            (data?.split(" ")?.any { it != "00" && it.isNotEmpty() } == true)
}

/**
 * 一个 TLV 的人话版：`84=31504159… "1PAY.SYS.DDF01"`。
 *
 * 值**全是可打印 ASCII** 时顺手把原文附上 —— 卡名、应用名这类字段就是靠这个
 * 一眼认出来的，否则人得自己把 `31 50 41 59` 译成 `1PAY`。
 *
 * 放在顶层而不是 `NfcController` 的成员：嵌套的 [NfcController.RawLine] 取不到外层实例成员。
 */
private fun tlvText(t: CardProbeLogic.Tlv): String {
    val hex = CardProbeLogic.toHexCompact(t.value)
    val ascii = if (t.value.size >= 2 && t.value.all { it.toInt() in 0x20..0x7E }) {
        " \"" + String(t.value, Charsets.US_ASCII) + "\""
    } else ""
    return "${t.tag}=$hex$ascii"
}
