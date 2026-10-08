package cn.bit101.android.features.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import cn.bit101.android.features.nfc.logic.NdefShortcutLogic

/**
 * 「碰一下贴纸 → 打开 App 并跳到某个页面」这条链路的**入口**。
 *
 * ## 它补的是哪一段
 *
 * 贴纸那条路原来只做完了前半截 —— `NfcController` 能把载荷**写进**贴纸，
 * 但写完之后碰它，App 并不会被唤起：
 * `AndroidManifest.xml` 里没有 `NDEF_DISCOVERED`，[MainActivity] 也不认 NDEF。
 * 于是「能写不能读」，贴纸等于白贴。
 *
 * 这个类把**后半截**收在一处：给一个 Intent，还你一个目标 route。
 * 调用方（`MainActivity.handleGoto`）只多三行，不需要知道 NDEF 长什么样。
 *
 * ## ⚠️ 为什么解析放在这里、不放在 MainActivity
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

        for (item in raw) {
            val message = item as? NdefMessage ?: continue
            for (record in message.records) {
                // ⚠️ 用 toUri() 而不是直接 String(payload)：
                // NdefRecord.createUri() 写进去的 payload **第一个字节是 URI 前缀码**，
                // 后面才是真正的 URI 字节。直接按字符串解会多出一个乱码前缀字符，
                // 于是永远匹配不上 —— 这种错在没机器的时候根本查不出来。
                val uri = record.toUri()?.toString() ?: continue
                val route = NdefShortcutLogic.decode(uri)
                if (route != null) return route
            }
        }
        return null
    }
}
