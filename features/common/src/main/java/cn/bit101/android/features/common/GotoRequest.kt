package cn.bit101.android.features.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 从外部（桌面组件点条目 / 通知）发起的「打开 App 并跳到指定位置」请求。
 *
 * ## 为什么放在 `features:common`
 *
 * 消费方横跨两个模块：`IndexScreen` 在 `:features`（切底栏页），
 * `ScheduleScreen` 在 `features:schedule`（切课表页里的 tab）。
 * 而 `features:schedule` **不能反向依赖** `:features` —— 所以这个共享的请求载体
 * 只能放在两边都依赖的 `features:common`。
 *
 * ## 为什么用全局单例而不是一路传参
 *
 * 跳转请求**可能在 Activity 已经存在时才到**（用户点组件条目 → `onNewIntent`），
 * 那时 Compose 树早就建好了，重新走一遍构造参数没有意义；而新建的情况下又需要在
 * NavHost 建图**之前**就知道目标页，否则会先闪一下课表页再跳走。
 * 单例 + `StateFlow` 两种时机都能覆盖。
 *
 * ## 流程
 * ```
 * 组件条目 → Intent(context, MainActivity)
 *              .putExtra(EXTRA_GOTO, "schedule")     // 底栏页（见 PageShowOnNav）
 *              .putExtra(EXTRA_TAB, 1)               // 可选：停在第几个 tab（见 ScheduleTabs）
 *              .putExtra(EXTRA_FOCUS, "<uid>")       // 可选：定位到哪一条
 *   → MainActivity.onCreate / onNewIntent 读 extra 写进来
 *   → IndexScreen 切底栏页（消费 route）
 *   → ScheduleScreen 切 tab（消费 tab）→ DDL / 动态列表滚到那一条（消费 key）
 * ```
 *
 * ## ⚠️ 为什么 route 与 Focus 分开、还分两次消费
 *
 * route 由 `IndexScreen` 消费，**可能早于** `ScheduleScreen` 组合；而 tab 是 `TabPager`
 * 在切页时就要用掉的，定位键却要留给**切页之后**才组合的列表。
 * 如果三者放在一起、一次清干净，用户点组件条目就只会跳页、定位不到那一条。
 * 所以：[consume] 只清 route、[consumeTab] 只清 tab、[consumeKey] 只清定位键。
 */
object GotoRequest {

    /** 目标页的 route（取值见 `PageShowOnNav.toPageData().value`，如 `"schedule"`）。 */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route

    /**
     * 附加的「聚焦」信息：停在第几个 tab、定位到哪一条。
     *
     * 只对课表页有意义，所以由 `ScheduleScreen` 与页内的列表消费。
     */
    data class Focus(
        /** 第几个 tab，取值见 `ScheduleTabs`；null = 不改 tab（停在默认的课表页）。 */
        val tab: Int? = null,
        /** 定位键：DDL 用 uid、动态用 `eclass:{id}`；null = 只切 tab 不定位。 */
        val key: String? = null,
    )

    private val _focus = MutableStateFlow<Focus?>(null)

    /**
     * 待处理的聚焦请求（**响应式**：`ScheduleScreen` 已经组合时也要能收到，
     * 所以给的是 Flow 而不是一次性取值）。
     */
    val focus: StateFlow<Focus?> = _focus

    fun request(route: String?) {
        if (route.isNullOrBlank()) return
        _route.value = route
    }

    /**
     * 带附加信息的请求（组件条目点击走这条）。
     *
     * [tab] 与 [key] 都没给时等价于 [request]。
     */
    fun request(route: String?, tab: Int?, key: String? = null) {
        if (route.isNullOrBlank()) return
        _route.value = route
        _focus.value = Focus(tab = tab, key = key)
            .takeIf { tab != null || !key.isNullOrBlank() }
    }

    /** 消费掉 route，避免返回键回到本页时又被跳一次。 */
    fun consume() {
        _route.value = null
    }

    /**
     * 消费掉「切 tab」那一部分，**保留定位键**。
     *
     * 为什么分两次消费：`TabPager` 收到 tab 就开始切页了，而真正要滚到某一条的
     * 列表（DDL / 动态）在切页**之后**才组合、才拿得到键。一次清干净的话，
     * 用户点组件条目只会跳到对的 tab，但定位不到那一条。
     */
    fun consumeTab() = update { it.copy(tab = null) }

    /** 消费掉「定位键」（列表已经滚过去之后调用）。 */
    fun consumeKey() = update { it.copy(key = null) }

    /** 两个字段都空了就把整个请求清掉，避免下次进课表页又跳一下。 */
    private inline fun update(block: (Focus) -> Focus) {
        val current = _focus.value ?: return
        val next = block(current)
        _focus.value = next.takeIf { it.tab != null || !it.key.isNullOrBlank() }
    }

    /** 清掉整个聚焦请求（确认不用处理时调用）。 */
    fun clearFocus() {
        _focus.value = null
    }
}
