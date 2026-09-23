package cn.bit101.android.data.eclass

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 延河课堂数据的「请刷新一下」广播。
 *
 * ## 为什么需要它
 *
 * 「动态」页和 DDL 页的数据都在各自的 ViewModel 里，而**设置页**想触发
 * 「重新拉取」时拿不到那些 VM（页面级作用域）—— 设置页唯一能做的是把
 * 数据拉回来自己用，用户回到动态页看到的还是旧数据。
 *
 * 解法：一个单例总线，设置页发事件，**活着的** VM 收到就刷新；
 * 没活着的 VM 下次 `init` 反正会重新取，不需要补发。
 *
 * ⚠️ `replay = 0` 是刻意的：事件只对**当下活着**的订阅者有意义，
 * 重放反而会让刚进页面的 VM 立刻连刷两次（init 一次 + 事件一次）。
 */
@Singleton
class EclassRefreshBus @Inject constructor() {

    /** 想刷新哪一份延河课堂数据。 */
    enum class Kind {
        /** 「动态」页的课程动态列表。 */
        ACTIVITY,

        /** DDL 页的延河课堂作业。 */
        DDL,
    }

    private val _requests = MutableSharedFlow<Kind>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requests = _requests.asSharedFlow()

    /** 发一个刷新请求（不阻塞、不抛异常 —— 没人订阅就算了）。 */
    fun request(kind: Kind) {
        _requests.tryEmit(kind)
    }
}
