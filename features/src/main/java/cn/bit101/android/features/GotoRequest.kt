package cn.bit101.android.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 从外部（桌面组件的「立即预约」按钮）发起的「打开 App 并跳到指定页」请求。
 *
 * 为什么用全局单例而不是一路传参：跳转请求**可能在 Activity 已经存在时才到**
 * （用户点组件按钮 → `onNewIntent`），那时 Compose 树早就建好了，
 * 重新走一遍构造参数没有意义；而新建的情况下又需要在 NavHost 建图**之前**就知道
 * 目标页，否则会先闪一下课表页再跳走。单例 + `StateFlow` 两种时机都能覆盖。
 *
 * 流程：
 * ```
 * 组件按钮 → Intent(context, MainActivity).putExtra(EXTRA_GOTO, "seat")
 *   → MainActivity.onCreate / onNewIntent 读 extra 写进来
 *   → IndexScreen 首次组合时作为 NavHost 起始页；已存在时 navigate 过去
 * ```
 */
object GotoRequest {

    /** 目标页的 route（取值见 `PageShowOnNav.toPageData().value`，如 `"seat"`）。 */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route

    fun request(route: String?) {
        if (route.isNullOrBlank()) return
        _route.value = route
    }

    /** 消费掉，避免返回键回到本页时又被跳一次。 */
    fun consume() {
        _route.value = null
    }
}
