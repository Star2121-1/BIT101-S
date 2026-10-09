package cn.bit101.android.features.nfc

import android.app.Activity
import android.content.Context
import android.nfc.Tag
import cn.bit101.android.features.nfc.logic.NfcCardLogic
import cn.bit101.android.features.nfc.logic.NfcTapLogic

/**
 * 「贴卡即用」—— App 在前台时贴一下自己的校园卡，直接落到常用那一页。
 *
 * ## 它补的是哪一段
 *
 * 贴纸那条路（[NfcShortcut]）能跳，但前提是**先有贴纸**。而校园卡本来就天天在身上，
 * 于是这里让「卡」自己也能当那个开关 —— 不用买贴纸，不用往卡里写任何东西
 * （⚠️ 校园卡带 MIFARE Classic，写它会重写扇区尾块，这是我们一早就划死的红线）。
 *
 * ## 顺带修好一个冲突：贴纸在前台也要能用
 *
 * reader mode 一开，标签派发就被本监听接管了，系统**不会再发** `NDEF_DISCOVERED`
 * 的 Intent。所以这里在认卡之前**先读一遍 NDEF**，把贴纸那条路接住 ——
 * 否则用户一开这个开关，贴纸快捷入口在 App 前台时就整条失效，且看不出原因。
 *
 * ## 一个手势，两种用法
 *
 * 贴卡 → 认出是我那张 → **未登录就先去登录页，登录了才去我选的目标页**。
 * 登录页自己会用本机已存的加密凭据静默登一次（见 `LoginRepo.checkLogin`），
 * 所以「有凭据就直接进去、没有才让人输」是现成的，这里一分钱凭据都没往卡里放。
 *
 * ## ⚠️ 它不提升安全性，别当它是
 *
 * 卡号（UID）是**公开值**，任何一台带 NFC 的手机都能读出来，也能被复制。
 * 所以这里的定位是**便利触发物**：省掉「打开 App → 点底栏」这几下。
 * 想更安全得叠一层生物识别（贴卡 + 指纹），那是另一件事，不在这一版。
 *
 * ## 生命周期（调用方按这个来）
 *
 * ```
 * onCreate  → attach(activity, isLoggedIn, onRoute)   // 一次
 * onResume  → onResume()
 * onPause   → onPause()
 * onDestroy → detach()
 * ```
 *
 * 之所以要 onResume/onPause 一对：reader mode 本来就只在前台有效，
 * 我们主动跟着前后台开关，是不让它在后台白占着 NFC 控制器。
 *
 * ## 与诊断页的互斥
 *
 * 诊断页自己也要读卡（[NfcController]），一个 Activity 上两套 reader mode 会互相顶掉，
 * 所以页面前台时调 [setPageActive]`(true)` 让位，离开时 `(false)` 再按设置重新评估。
 */
object NfcTap {

    private var activity: Activity? = null
    private var isLoggedIn: () -> Boolean = { false }
    private var onRoute: (String) -> Unit = {}

    private var watcher: NfcTapWatcher? = null

    /** 诊断页是不是正占着读卡权。 */
    private var pageActive = false

    /** Activity 是不是在前台。 */
    private var foreground = false

    /**
     * 挂上监听。由 `MainActivity.onCreate` 调一次。
     *
     * ⚠️ 这里**持有 Activity 引用**，所以 [detach] 必须在 `onDestroy` 里调到 ——
     * 静态单例拿着一个已销毁的 Activity 是典型的内存泄漏。
     *
     * @param isLoggedIn 贴卡**那一刻**的登录态（不是 attach 时的快照）。
     * @param onRoute 决定好的目标 route 交回给宿主去跳转。
     */
    fun attach(activity: Activity, isLoggedIn: () -> Boolean, onRoute: (String) -> Unit) {
        this.activity = activity
        this.isLoggedIn = isLoggedIn
        this.onRoute = onRoute
        sync()
    }

    fun onResume() {
        foreground = true
        sync()
    }

    fun onPause() {
        foreground = false
        sync()
    }

    fun detach() {
        foreground = false
        // 复位：Activity 重建时 attach 会重新挂上，
        // 这里留着 true 的话新的 watcher 会永远不启动。
        pageActive = false
        watcher?.stop()
        watcher = null
        activity = null
        isLoggedIn = { false }
        onRoute = {}
    }

    /**
     * 诊断页让位 / 交还读卡权。
     *
     * [active] = true 时**停掉**本监听并把卡让给页面；false 时按设置重新评估要不要恢复。
     * 之所以交还时重新读设置（而不是恢复之前的状态）：用户可能就是在页面上把开关改掉的。
     */
    fun setPageActive(active: Boolean) {
        pageActive = active
        sync()
    }

    // ---------------------------------------------------------------- 设置读写

    fun isEnabled(context: Context): Boolean = NfcTapStore.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) =
        NfcTapStore.setEnabled(context, enabled)

    fun targetRoute(context: Context): String? = NfcTapStore.targetRoute(context)

    fun setTargetRoute(context: Context, route: String) =
        NfcTapStore.setTargetRoute(context, route)

    // ---------------------------------------------------------------- 内部

    /** 按「前台 + 没被页面占着 + 用户开着」三个条件，决定监听该开还是该关。 */
    private fun sync() {
        val act = activity
        val want = foreground && !pageActive && act != null && NfcTapStore.isEnabled(act)

        if (!want) {
            watcher?.stop()
            return
        }

        val existing = watcher
        if (existing != null && existing.running) return
        watcher = NfcTapWatcher(act!!) { tag -> handleTag(tag) }
        watcher?.start()
    }

    /**
     * 有标签贴上来了，判断该不该动、动去哪。
     *
     * 跑在主线程（[NfcTapWatcher] 已经切好了）。每一个「不满足条件」的分支都是
     * **安静返回**：这是常驻监听，别人贴一张公交卡、共享单车、门禁卡都不该弹出任何东西。
     *
     * ## 先看贴纸，再看卡 —— 顺序不能反
     *
     * 贴纸那条路（`NfcShortcut`）本来由系统按 manifest 唤起；但 reader mode 一开，
     * 标签派发就被我们接管了，系统**不会再发那个 Intent**。
     * 所以这里必须先自己把 NDEF 读一遍，否则「贴卡即用」一开，
     * 贴纸快捷入口在 App 前台时就整条失效了 —— 而那种失效在真机上完全看不出原因。
     *
     * 反过来，校园卡没有 `Ndef` 技术，`NfcShortcut.routeOfTag` 会立刻返回 `null`，
     * 不会为了「先试一下贴纸」拖慢读卡。
     */
    private fun handleTag(tag: Tag) {
        val act = activity ?: return

        NfcShortcut.routeOfTag(tag)?.let { route ->
            onRoute(route)
            return
        }

        // 没读过自己的卡 ⇒ 无从比对，安静退出。
        // 这不是失败：设置页那块会写清楚「先贴一次卡」。
        val saved = SavedCardStore.read(act) ?: return
        if (!NfcTapLogic.isMyCard(saved.uid, NfcCardLogic.uidHex(tag.id))) return

        onRoute(NfcTapLogic.target(NfcTapStore.targetRoute(act), isLoggedIn()))
    }
}
