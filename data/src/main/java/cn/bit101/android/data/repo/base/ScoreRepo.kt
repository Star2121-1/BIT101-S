package cn.bit101.android.data.repo.base

import cn.bit101.android.data.score.ScoreLogic.ScoreEntry
import com.google.gson.JsonElement

/**
 * 成绩数据（BIT101 `/scores`）。
 *
 * 主要为「出分提醒」服务：拉一次成绩表 → 与本地快照差分 → 报告新课。
 * 分数本身**只存在本地快照文件里**，不出现在任何通知文案中（用户定的隐私边界）。
 */
interface ScoreRepo {

    /** 拉原始成绩表；未登录/失败返回 null（调用方静默跳过）。 */
    suspend fun fetchTable(): JsonElement?

    /**
     * 成绩页的一次加载结果。
     *
     * ⚠️ 与 [fetchTable] 的区别：那个只为「提醒」服务，失败给 null 就够；成绩页要
     * 把**失败原因**说清楚（未登录 / 要短信 / 密码错 / 网络），否则用户只能干瞪眼。
     *
     * ⚠️ 这里回的是**已解析的** [cn.bit101.android.data.score.ScoreTable.Table]，
     * 不是 `JsonElement` —— UI 层不该因为「我要显示成绩」而背上 Gson 依赖。
     */
    sealed interface Load {
        /** 真的从网上拉到了。 */
        data class Fresh(val table: cn.bit101.android.data.score.ScoreTable.Table) : Load

        /**
         * 没拉到网，但有上次的缓存 —— 页面照常显示，顶部提示「可能是旧数据」。
         *
         * @param reason 为什么这次没拉成（null 表示「还没到该刷的时候」）
         */
        data class Cached(
            val table: cn.bit101.android.data.score.ScoreTable.Table,
            val cachedAtMillis: Long,
            val reason: cn.bit101.android.data.score.ScoreCheckStore.Code? = null,
            val detail: String? = null,
        ) : Load

        /**
         * 什么都没有（首次使用就失败）。[code] 决定页面给什么动作：
         * [cn.bit101.android.data.score.ScoreCheckStore.Code.NOT_LOGGED_IN] ⇒ 去登录；
         * `AUTH_FAILED` ⇒ 去改密码；`NEED_SMS` ⇒ 只能等/去网页；`FAILED` ⇒ 重试。
         */
        data class Failed(
            val code: cn.bit101.android.data.score.ScoreCheckStore.Code,
            val detail: String? = null,
        ) : Load

        /**
         * 数据拿到了，但**表认不出**（找不到「课程名称 / 成绩」这些列）。
         *
         * 与 [Failed] 分开：这不是网络问题，重试也没用 —— 是教务换了表格格式，
         * 页面该引导用户去网页版看，而不是让他一直点重试。
         */
        data class Malformed(val detail: String? = null) : Load
    }

    /**
     * 成绩页用的加载：优先缓存，必要时才真的走一次学校 SSO。
     *
     * @param force 用户主动下拉刷新 / 点「重新获取」时传 true，跳过新鲜度判断。
     *
     * ⚠️ 缓存不只是性能：每次取数都要用学号密码走一遍学校统一身份认证，
     * 频繁登录会被风控要求短信二次验证（2026-09-26 实测）。所以**默认必须吃缓存**，
     * 只有明确刷新或缓存过期才打网。
     */
    suspend fun load(force: Boolean = false): Load

    /**
     * 同步并差分。
     *
     * @param force 跳过 12h 限频（用户点「立即检查一次」时用）。⚠️ 每次检查都要走一遍学校登录，别在自动路径上用。
     * @return 本次**新出分**的课程。首次运行只建立基线、返回空；
     *   解析失败 / 未登录也返回空 —— 出分提醒是尽力而为，绝不做错。
     */
    suspend fun syncAndDiff(force: Boolean = false): List<ScoreEntry>

    /**
     * 最近一次检查的结果（设置页展示）。
     *
     * ⚠️ 检查要经学校 SSO，**可能被风控拦成「要短信验证」**，而它是后台静默跑的 ——
     * 没有这个状态，「为什么没提醒」就无从判断。见 [cn.bit101.android.data.score.ScoreCheckStore]。
     */
    suspend fun lastCheck(): cn.bit101.android.data.score.ScoreCheckStore.Status?
}
