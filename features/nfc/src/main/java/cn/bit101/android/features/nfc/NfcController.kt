package cn.bit101.android.features.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import cn.bit101.android.features.nfc.logic.CardKind
import cn.bit101.android.features.nfc.logic.CardProbeLogic
import cn.bit101.android.features.nfc.logic.NdefShortcutLogic
import cn.bit101.android.features.nfc.logic.NfcCardLogic
import cn.bit101.android.features.nfc.logic.RawTag
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

    /** 是否在小目标上多跑一轮 SFI 扫描（只对 CPU 卡有意义，且明显更慢）。 */
    @Volatile
    var withSfiScan: Boolean = false

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
            probe = if (description.supportsApdu) probe(tag) else null,
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
    private fun probe(tag: Tag): List<ProbeLine> {
        val iso = runCatching { IsoDep.get(tag) }.getOrNull() ?: return emptyList()

        val steps = CardProbeLogic.firstRound() +
            if (withSfiScan) CardProbeLogic.sfiScan() else emptyList()

        return try {
            iso.connect()
            steps.map { step ->
                val apdu = step.apdu
                if (!CardProbeLogic.isReadOnly(apdu)) {
                    return@map ProbeLine(step.label, CardProbeLogic.toHex(apdu), "", "被安全闸门拦下（拒绝发出）", null)
                }
                val raw = runCatching { iso.transceive(apdu) }.getOrNull()
                    ?: return@map ProbeLine(step.label, CardProbeLogic.toHex(apdu), "", "transceive 失败", null)
                val r = CardProbeLogic.parseResponse(raw)
                ProbeLine(step.label, CardProbeLogic.toHex(apdu), r.sw, r.swText, CardProbeLogic.toHex(r.data))
            }
        } finally {
            runCatching { iso.close() }
        }
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
    val error: String? = null,
    val writeOutcome: String? = null,
)

internal data class ProbeLine(
    val label: String,
    val apdu: String,
    val sw: String,
    val swText: String?,
    val data: String?,
)
