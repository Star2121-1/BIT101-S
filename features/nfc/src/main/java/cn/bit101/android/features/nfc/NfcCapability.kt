package cn.bit101.android.features.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter

/**
 * 这台机器**到底能不能用 NFC**，以及「能用，但现在是关着的」。
 *
 * ## 为什么必须判三态而不是一个布尔值
 *
 * 这三种情况用户要做的下一件事完全不同：
 * - [Unsupported]：再怎么点都点不出来告诉他**别找了**，而且换个机型才有希望；
 * - [Disabled]：他有 NFC，只是关了 —— 要给他一条**去系统设置打开**的路；
 * - [Enabled]：可以开干。
 *
 * 一刀切成 true/false 的话，第一类人会对着一个按不动的按钮反复试。

 * ## ⚠️ 为什么硬件开关单独查一次
 *
 * `NfcAdapter.getDefaultAdapter()` 在**没有 NFC 芯片**的机器上返回 `null`，
 * 但在「有芯片却被系统整体关闭」的情况下也可能返回 `null`（部分 ROM 的行为）。
 * 所以这里**查两样东西，且职责不混**：
 * - `hasSystemFeature(FEATURE_NFC)` = 硬件与框架功能在不在（这是可靠的那一票）；
 * - `NfcAdapter` 只用来看开关状态，硬件不支持时这一查压根不执行。
 *
 * 开发用真机 ZTE NP05J 就是 [Unsupported]（`ro.vendor.feature.board_uses_nfc=NONE`），
 * 鸿蒙的 APK 兼容沙盒大概率也是没透传 NFC —— 这两种机器上都必须报得清清楚楚，
 * 而不是一片空白或者一个点不动的按钮。
 */
internal sealed interface NfcCapability {

    /** 机器没有 NFC 硬件（或 ROM 没向应用层开放）。换一台带 NFC 的安卓机才有希望。 */
    data object Unsupported : NfcCapability

    /** 有硬件，但用户（或系统）把 NFC 关了。 */
    data object Disabled : NfcCapability

    /** 有硬件且已开启，可以读卡 / 写标签。 */
    data object Enabled : NfcCapability

    companion object {

        /** 一次性查出当前状态。任何异常（比如 ROM 把 feature 表改坏）都当不支持，不去猜。 */
        fun check(context: Context): NfcCapability {
            val hasFeature = runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
            }.getOrDefault(false)

            if (!hasFeature) return Unsupported

            val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }
                .getOrNull() ?: return Unsupported

            return if (runCatching { adapter.isEnabled }.getOrDefault(false)) Enabled else Disabled
        }
    }
}

/**
 * [NfcCapability] 对应的一段用户看得懂的话。
 *
 * ⚠️ 措辞纪律：**把我们知道的和不知道的分开写**。
 * 「本设备不支持 NFC」是我们查证过的结论（featurs 表里没有这一项）；
 * 而「沙盒环境可能没透传」这种**推论**是不能写成结论的 ——
 * 所以 [subTitle] 只陈述事实，故障排查建议留在 [hint] 里明确标成「可能」。
 */
internal fun NfcCapability.title(): String = when (this) {
    NfcCapability.Unsupported -> "本设备不支持 NFC"
    NfcCapability.Disabled -> "NFC 已关闭"
    NfcCapability.Enabled -> "NFC 可用"
}

internal fun NfcCapability.hint(): String = when (this) {
    NfcCapability.Unsupported ->
        "系统没有报告 NFC 硬件。换一台带 NFC 的安卓机才能用读写功能；" +
            "如果你是在兼容环境/沙盒里运行的 App，也可能是沙盒没透传 NFC —— 这一条我们无法从这里确认。"
    NfcCapability.Disabled ->
        "硬件是有的，现在关着。到系统设置里打开 NFC 再回来。"
    NfcCapability.Enabled ->
        "把校园卡或 NFC 贴纸贴在手机背面，下面两个功能就能用。"
}
