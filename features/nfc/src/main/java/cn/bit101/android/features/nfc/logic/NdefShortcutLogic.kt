package cn.bit101.android.features.nfc.logic

import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData

/**
 * NFC 标签里那串「快捷入口」载荷的**封装与解封**。
 *
 * ## 它解决什么
 *
 * 第一件事是「读校园卡」，那条路要等有一张真卡 + 一台有 NFC 的机器才能推进。
 * 这条路不需要：买几张 NFC 贴纸，写一句我们的载荷进去，贴在你想要的地方 ——
 * 宿舍书桌、图书馆某个阅览室的定义那面墙上、实验室门口。手机一贴，直接落到某个页面。
 *
 * ## 为什么是这个格式
 *
 * ```
 * bit101://nfc/shortcut?to=schedule
 * ```
 *
 * - 用 URI 而不是自定义二进制：任何第三方 NFC 工具都能把它读成「一条网址」，
 *   用户不会因为贴了我们的贴纸而得到一个打不开的黑盒。
 * - **`to` 只能取路由白名单里的值**（见 [targets]）。
 *   为什么要限制：`GotoRequest` 的消费方只认**底栏页 route**，写进去一个任性的字符串
 *   只会换来一次「点了没反应」，而这类 bug 在没机器的时候根本查不出来。
 * - 版本号先写在链路里：将来真要变格式，加一个 `v=2` 参数即可，
 *   老标签依然能被认识到「这是旧格式」，而不是被当成乱码。
 *
 * ## ⚠️ 不用 Android 的 `Uri`
 *
 * `android.net.Uri` 在 JVM 单测里是空壳（`Uri.parse` 直接抛未实现）。
 * 所以这里的解析是**纯字符串操作**，参数很少，自己切开比引 Robolectric 划算。
 */
internal object NdefShortcutLogic {

    const val SCHEME = "bit101"
    const val HOST = "nfc"
    const val PATH = "/shortcut"

    /** 载荷里代表目标的字段名。 */
    const val PARAM_TARGET = "to"

    private const val PREFIX = "$SCHEME://$HOST$PATH?"

    /**
     * 一个可选的快捷入口目标。
     *
     * @param route 写进载荷的值（必须与 `GotoRequest` 认的 route 一致）。
     * @param label 给人看的名字。
     */
    data class Target(
        val route: String,
        val label: String,
    )

    /**
     * 全部合法目标 —— **单一来源**来自 `:config`。
     *
     * 为什么不在这里抄一份列表：底栏页一旦调整（比如哪天加了个「成绩」页），
     * 抄在这里的那份会静默过期，写标签的人还在往旧 route 上写。
     * 直接 `PageShowOnNav` + `AppRoutes` 就不会漂移。
     */
    fun targets(): List<Target> = buildList {
        PageShowOnNav.allPages
            .map { it.toPageData() }
            .forEach { add(Target(route = it.value, label = it.name)) }

        // 这两个不是底栏页，但 `NavDestConfig` 已经把它们做成**无参顶层路由**，
        // 桌面小组件走的正是这条路，所以 NFC 标签用同一批 route 才不会「同样的动作、
        // 从组件点得进去、从标签点不进去」。
        add(Target(route = AppRoutes.LOGIN, label = "登录"))
        add(Target(route = AppRoutes.SCORE, label = "成绩"))
    }

    /**
     * 这个 route 允不允许被写进标签。
     *
     * 空串与空白串一律拒绝：`GotoRequest.request` 遇到空白也会直接 return，
     * 与其让它静默什么都不做，不如在写入这一步就挡掉。
     */
    fun isSupported(route: String): Boolean =
        route.isNotBlank() && targets().any { it.route == route }

    /**
     * 生成一个载荷。
     *
     * @return 载荷字符串；route 不在白名单里时返回 `null`（**让调用方显式处理**，
     *   不要写一个「永远成功」的 encode）。
     */
    fun encode(route: String): String? =
        route.takeIf(::isSupported)?.let { "$PREFIX$PARAM_TARGET=$it" }

    /**
     * 从一个载荷里解出目标 route。
     *
     * @return 目标 route；以下任何一种情况都返回 `null`：不是我们的 scheme、
     *   缺 `to` 参数、`to` 空、或目标不在白名单（比如标签是别人写的、或者 App 改版后
     *   这个 route 已经不存在的老标签）。
     */
    fun decode(payload: String): String? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(PREFIX)) return null

        val query = trimmed.substring(PREFIX.length)
        val target = query
            .split('&')
            .firstOrNull { it.startsWith("$PARAM_TARGET=") }
            ?.substringAfter('=')
            ?: return null

        return target.takeIf(::isSupported)
    }
}
