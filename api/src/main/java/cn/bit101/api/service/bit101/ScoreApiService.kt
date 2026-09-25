package cn.bit101.api.service.bit101

import cn.bit101.api.model.http.*
import cn.bit101.api.model.http.bit101.GetScoreDataModel
import cn.bit101.api.model.http.bit101.GetScoreReport
import cn.bit101.api.service.ApiService
import retrofit2.Response
import retrofit2.http.*

/**
 * ⚠️ **本接口已失效（2026-09-26 实测）**：`GET /scores` 恒返回 **404**，
 * `/scores/report` 同样是死接口（上游 BIT101-Android 亦然，非本 fork 的回归）。
 *
 * 成绩数据已改由 **异步挑战流程**提供 —— 从 BIT101 网页版（`bit101.cn/assets/Score-*.js`）
 * 逆向出的真实契约：
 *
 * 1. `POST https://login.bit101.flwfdd.xyz/api/jwb/bit101/score`，body `{username, password}`
 *    → `202` `{detail:{challenge_id, access_token, requested_services:["jwb"], status, expires_in}}`
 * 2. `GET /api/auth/{challenge_id}`，header `X-Challenge-Token: <access_token>`
 *    → 轮询 `{status, ready_services}`；`ready_services` 含 `jwb` 即就绪
 * 3. 带 `Authorization: Bearer <access_token>` 再请求第 1 步的路径，拿到
 *    `data.data` = **二维数组**（第 0 行是表头，其余是数据行 —— 与 [ScoreLogic] 的
 *    「按表头关键词定位列」设计一致）
 *
 * ⛔ 未接入的原因：实测第 2 步返回 `status = waiting_sms` —— 学校 SSO 会触发**短信二次验证**，
 * 而后台定时检查没有交互通道（再叠加 CAS 风控，连续登录会被静默拒绝）。
 * 要做需要先解决「短信验证码从哪来」。
 */
interface ScoreApiService : ApiService {
    @GET("/scores")
    suspend fun getScores(
        @Query("detail") detail: String? = null
    ): Response<GetScoreDataModel.Response>

    @GET("/scores/report")
    suspend fun getScoreReport(): Response<GetScoreReport.Response>
}