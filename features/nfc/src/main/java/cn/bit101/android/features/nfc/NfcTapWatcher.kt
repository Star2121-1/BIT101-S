package cn.bit101.android.features.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper

/**
 * 前台常驻的「贴卡」监听。
 *
 * ## 它为什么这么薄
 *
 * 这个监听是**常驻**的（只要 App 在前台且用户开了开关），它每一次被唤起都在花用户的电。
 * 所以这里只做一件最便宜的事：**把标签原样交出去**，判断留给 [NfcTapLogic] 与
 * [NfcTap] —— 那一层是纯函数，能单测；这一层是框架胶水，跑不了单测。
 * 把逻辑从跑不了单测的层里挪出去，是这个文件存在的理由。
 *
 * ## 线程
 *
 * Reader Mode 的回调跑在 Binder 线程上；上层拿到标签会去碰导航（主线程的东西），
 * 所以这里统一 `post` 到主线程再回调，让上层不必自己去切。
 *
 * ## ⚠️ 与诊断页的 reader mode 不能同时开
 *
 * 一个 Activity 上只认最后一次 `enableReaderMode`。两边都开的话，
 * 后开的那次会把先开的顶掉，而**任意一边 `disableReaderMode` 都会把两边一起关掉** ——
 * 表现为「从诊断页退出来之后贴卡就不灵了」这种很难查的问题。
 * 所以由 [NfcTap] 统一裁决**同一时刻只有一方在监听**（见其 `setPageActive`）。
 */
internal class NfcTapWatcher(
    private val activity: Activity,
    private val onTag: (Tag) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var adapter: NfcAdapter? = null

    /** 当前是不是我们开着的。用来避免重复 `enableReaderMode`。 */
    var running: Boolean = false
        private set

    /**
     * 开始监听。
     *
     * @return true = 已开始；false = 这台机器没有可用的 NFC（上层据此**不显示成功**，
     *   而不是静默什么都不做 —— 用户开了开关却毫无反应会被当成 App 坏了）。
     */
    fun start(): Boolean {
        if (running) return true

        val nfc = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull() ?: return false
        adapter = nfc

        // 只要 NFC-A：校园卡与常见贴纸都是 A 类。少开几种调制方式，少一点功耗。
        //
        // ⚠️ **不加** `FLAG_READER_SKIP_NDEF_CHECK`。加它能省掉系统在发现标签时的那次
        // NDEF 读取，但代价是标签不再带 `Ndef` 这项技术 —— 而 [NfcTap] 正是靠读 NDEF
        // 来让「贴纸快捷入口」在 App 前台时仍然生效的。省这一次读换来一条功能失效，
        // 不划算；何况非 NDEF 的标签（校园卡）本来就走不到那一步。
        val ok = runCatching {
            nfc.enableReaderMode(
                activity,
                { tag -> tag?.let { main.post { onTag(it) } } },
                NfcAdapter.FLAG_READER_NFC_A,
                null,
            )
        }.isSuccess

        running = ok
        return ok
    }

    fun stop() {
        if (!running) return
        runCatching { adapter?.disableReaderMode(activity) }
        adapter = null
        running = false
    }
}

