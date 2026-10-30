package cn.bit101.android.data.repo.base

import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.eclass.EclassDdlItem
import java.time.LocalDateTime

/**
 * 延河课堂（eclass）的会话状态。
 *
 * ## 为什么不能只用 boolean
 *
 * 以前只有「可用 / 不可用」两态，于是**服务器 5xx、网络超时、真的没登录**
 * 全被报成「还没登录延河课堂」，引导用户去点「去登录」——
 * 而那时点进去只会看到 Keycloak 的 `An internal server error has occurred`。
 * 用户会以为是自己掉登录了，反复重登，问题却在学校那边。
 *
 * 2026-10-30 实测就是这个情况：`/user/index` 返回 500，
 * 而动态页显示「还没登录延河课堂」。
 */
enum class EclassSessionState {
    /** 会话可用，能正常拉数据 */
    ALIVE,

    /** 明确未登录：接口 401/403，或把我们挡回登录页 */
    NEEDS_LOGIN,

    /** 服务端故障（5xx）或网络不可达 —— **不是登录问题**，别让用户去重登 */
    UNAVAILABLE,
}

/**
 * 延河课堂（eclass）的取数入口。
 *
 * 职责边界：**只负责「拿到数据并映射成 DDL 条目」**，不碰数据库、不碰 UI。
 * 写入 `ddl_schedule` 由调用方（DDL 同步流程）负责 —— 与乐学源的接入方式保持一致。
 */
interface EclassRepo {

    /**
     * 会话是否仍然可用（只要 [EclassSessionState.ALIVE]）。
     *
     * 判据是「能否拉到课程列表」。**默认实现基于 [sessionState]**，
     * 需要区分「未登录」与「服务端故障」的调用方请直接用 [sessionState]。
     */
    suspend fun isSessionAlive(): Boolean = sessionState() == EclassSessionState.ALIVE

    /**
     * 会话状态三态版本 —— 能区分「没登录」和「学校服务器挂了」。
     *
     * 每次调用都会发真实网络请求（并先同步 WebView 的 cookie）。
     */
    suspend fun sessionState(): EclassSessionState

    /**
     * 拉取所有课程的作业，映射成 DDL 条目（按截止时间升序）。
     *
     * ⚠️ **单个课程失败不该拖垮整次同步** —— 逐课程 try/catch，
     * 一门课超时/无权限只丢它自己。这在课程多、网络不稳时很重要。
     */
    suspend fun fetchHomework(now: LocalDateTime = LocalDateTime.now()): List<EclassDdlItem>

    /**
     * 拉取所有课程的「动态」—— **不筛掉非作业项**（资料、公告、测试都算），
     * 按时间**倒序**（最近的在前），最多 [limit] 条。
     *
     * 与 [fetchHomework] 用的是同一份接口数据，区别只在筛选口径：
     * DDL 只要作业，动态页要"课程里发生的一切"。同样逐课程 try/catch。
     */
    suspend fun fetchActivities(limit: Int = DEFAULT_ACTIVITY_LIMIT): List<EclassActivityLogic.EclassActivity>

    companion object {
        /** 动态页最多显示多少条 —— 再多用户也不会往下翻，反而拖慢同步。 */
        const val DEFAULT_ACTIVITY_LIMIT = 60
    }

    /**
     * 把 WebView 里登录得到的 cookie 同步到 OkHttp 使用的 cookie store。
     *
     * 必须在任何请求之前调用 —— 用户刚在 WebView 里登录完时，
     * cookie 还只在 WebView 那边（详见 `WebViewCookieSync` 的说明）。
     */
    fun syncCookies()
}
