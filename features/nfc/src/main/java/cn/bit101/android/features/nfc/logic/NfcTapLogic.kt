package cn.bit101.android.features.nfc.logic

import cn.bit101.android.config.setting.base.AppRoutes

/**
 * 「贴卡即用」的判据 —— 全是纯函数，能单测，不碰 NFC 框架。
 *
 * ## 这条路要解决什么
 *
 * 贴纸那条路（[NdefShortcutLogic]）需要先买贴纸、写进去、再贴到某处。
 * 而校园卡**本来就在身上**：进宿舍楼、进教学楼、吃食堂都要掏它。
 * 既然它天天在手上，那「贴一下就打开我常用的那一页」这件事就不该再等贴纸。
 *
 * ## ⚠️ 卡是「触发物」，不是「凭据」
 *
 * 这里做的是**辨认**（这是不是我那张卡），不是**认证**。
 * 比较用的卡号（UID）是公开值 —— 任何一台带 NFC 的手机贴上去都能读出来，
 * 也能被写成一张同号的假卡。所以：
 *
 * - 它只用来决定「跳不跳」，**绝不参与任何登录校验**；
 * - 不新增任何密码副本、不上传任何东西；
 * - 未登录时它把人送到登录页，**由登录流程自己去认证**（用的是本机已存的那份
 *   加密凭据），贴卡本身不构成任何形式的「免密登录」。
 *
 * 一句话：**贴卡能省的是「以后」，省不了「第一次」**，也不该省。
 */
internal object NfcTapLogic {

    /**
     * 用户没选目标页时的默认落点。
     *
     * 为什么是课表页：它是这个 App 打开后最常被看的一页（当天上什么课、
     * 有没有调课），也是底栏的第一个位置 —— 贴卡跳到「首页」是最不容易出错的默认。
     */
    const val DEFAULT_ROUTE = "schedule"

    /**
     * 卡号的**归一化**：只留字母数字、统一大写。
     *
     * 为什么要这一步：同一个 UID 在不同来源里的写法不一样 ——
     * `NfcCardLogic.uidHex` 给的是 `7775F07B`，而用户从别处抄来的可能是
     * `77:75:F0:7B` 或 `7775f07b`。直接 `==` 比字符串会把这些判成两张卡，
     * 表现为「我的卡贴上去没反应」，而查起来会往 NFC 那边找错方向。
     */
    fun normalizeUid(raw: String?): String =
        raw.orEmpty().filter { it.isLetterOrDigit() }.uppercase()

    /**
     * 贴上来的是不是「我存过的那张卡」。
     *
     * ⚠️ 判据是**卡号相等**，两边都归一化之后比。
     * 任意一边为空一律判否 —— 没存过卡时不该「默契地」放行。
     *
     * 已知局限（有意接受）：只认 UID，不读卡内学号。
     * 读学号要发一串 APDU（真卡上几十条），而**贴一下就走**是这个动作的常态 ——
     * 用户没等它读完就把卡拿开了，读到的会是半截数据。用 UID 换「贴一下就灵」，
     * 这个取舍是划算的；换新卡之后重读一次卡即可。
     */
    fun isMyCard(savedUid: String?, tagUid: String?): Boolean {
        val saved = normalizeUid(savedUid)
        if (saved.isEmpty()) return false
        return saved == normalizeUid(tagUid)
    }

    /**
     * 贴卡之后该落到哪一页。
     *
     * ## 为什么未登录时改去登录页
     *
     * 目标页（课表 / 座位）几乎都要求登录，直接跳过去只会撞上「请先登录」的门禁。
     * 而登录页本身会先**用本机已存的凭据静默登一次**（`checkLogin`）——
     * 也就是说：**有凭据就直接进去，没有才让人输**。
     * 这正是「贴卡登录」想要的效果，而且它一分钱的凭据都没往卡里放。
     *
     * @param chosenRoute 用户选的目标页；空白时退回 [DEFAULT_ROUTE]。
     * @param loggedIn 贴卡那一刻的登录态。
     * @return 要交给跳转流程的顶层 route。
     */
    fun target(chosenRoute: String?, loggedIn: Boolean): String {
        if (!loggedIn) return AppRoutes.LOGIN
        return chosenRoute?.takeIf { it.isNotBlank() } ?: DEFAULT_ROUTE
    }
}
