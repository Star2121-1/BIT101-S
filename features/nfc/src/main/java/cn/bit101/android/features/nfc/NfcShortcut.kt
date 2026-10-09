package cn.bit101.android.features.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import cn.bit101.android.features.nfc.logic.NdefShortcutLogic

/**
 * 「碰一下 → 打开 App 并跳到某个页面」这条链路的**入口**。两条来路都在这里收口：
 *
 * 1. **贴纸**：App 不在前台时，系统按 manifest 里的 `NDEF_DISCOVERED` 唤起 Activity，
 *    目标写在 NDEF 记录里（`bit101://nfc/shortcut?to=…`），从 Intent 里取。
 * 2. **同一张贴纸、但 App 正在前台**：这时 reader mode（见 [NfcTapWatcher]）接管了
 *    标签派发，系统**不会**再走 Intent 那条路 —— 所以得自己从贴着的标签里读 NDEF。
 *
 * ⚠️ 第 2 条不是锦上添花：漏了它，「贴卡即用」一开，贴纸快捷入口在 App 前台时
 * 就会**整条失效**（贴了没反应，且看不出原因）。两个功能必须能共存。
 *
 * ## 为什么解析放在这里、不放在 MainActivity
 *
 * `NdefShortcutLogic` 是 `internal`（只认同一个 Gradle 模块），
 * 而 `MainActivity` 在 `:features` —— 跨模块拿不到。
 * 与其把它改成 public 让每个调用方都自己拼解析步骤，
 * 不如在这里收口：**载荷格式的知识只有一份**，将来加 `v=2` 只改这一个文件。
 *
 * ## 安全
 *
 * 解出来的 route **只能是 `NdefShortcutLogic.targets` 白名单里的值**，
 * 写进载荷一个任性的字符串换来的只是 `null`（也就是不跳）。
 * 再加上 manifest 里把 scheme 收紧到 `bit101`，
 * 别人的贴纸既唤不起我们，我们也绝不会被一个 URI 指到任意页面去。
 */
object NfcShortcut {

    /**
     * 从贴纸触发的 Intent 里解出目标 route。
     *
     * @return 白名单内的 route（如 `"schedule"`）；
     *   不是我们的贴纸、或载荷不合规，一律 `null`（**调用方什么都不做**）。
     */
    @Suppress("DEPRECATION") // getParcelableArrayExtra(String) 在 API 33 才有两个参数的重载
    fun routeOf(intent: Intent?): String? {
        val raw = intent?.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?: return null
        return raw.filterIsInstance<NdefMessage>().firstNotNullOfOrNull(::routeOfMessage)
    }

    /**
     * 从一张**正贴在手机上**的标签里解出目标 route（reader mode 那条路用）。
     *
     * 不是 NDEF 标签（`Ndef.get` 返回 `null`）就立刻返回 —— 校园卡就属于这种，
     * 它只有 `NdefFormatable` 而没有 `Ndef`，所以这一步对它几乎是零成本，
     * 不会因为「先试着读一下贴纸」而拖慢读卡。
     */
    internal fun routeOfTag(tag: Tag): String? {
        val ndef = runCatching { Ndef.get(tag) }.getOrNull() ?: return null
        return try {
            ndef.connect()
            ndef.ndefMessage?.let(::routeOfMessage)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun routeOfMessage(message: NdefMessage): String? {
        for (record in message.records) {
            // ⚠️ 用 toUri() 而不是直接 String(payload)：
            // NdefRecord.createUri() 写进去的 payload **第一个字节是 URI 前缀码**，
            // 后面才是真正的 URI 字节。直接按字符串解会多出一个乱码前缀字符，
            // 于是永远匹配不上 —— 这种错在没机器的时候根本查不出来。
            val uri = record.toUri()?.toString() ?: continue
            val route = NdefShortcutLogic.decode(uri)
            if (route != null) return route
        }
        return null
    }
}
