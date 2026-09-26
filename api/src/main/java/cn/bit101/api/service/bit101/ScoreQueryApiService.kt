package cn.bit101.api.service.bit101

import cn.bit101.api.model.http.bit101.GetScoreDataModel
import cn.bit101.api.model.http.bit101.ScoreQueryModel
import cn.bit101.api.service.ApiService
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 成绩查询 —— BIT101 **认证主机**（`https://login.bit101.flwfdd.xyz`）上的异步挑战流程。
 *
 * ⚠️ 旧的 `GET /scores`（业务主机）已失效（实测恒 404，上游 BIT101-Android 亦然），
 * 教务成绩改由这套流程转发。三步：
 *
 * 1. [start]：`POST /api/jwb/bit101/score {username, password}` → `202`
 *    `{detail:{challenge_id, access_token, status, ready_services}}`
 * 2. [poll]：`GET /api/auth/{challengeId}`，头 `X-Challenge-Token: <access_token>`
 *    → 轮询到 `ready_services` 含 `jwb`（学校 SSO 可能先要求 `waiting_sms`）
 * 3. [fetch]：同样路径，带 `Authorization: Bearer <access_token>` 与
 *    `{challenge_id, username, password}` → `data` 是**二维数组（第 0 行表头）**，
 *    正好喂给 `ScoreLogic.parseTable`
 */
interface ScoreQueryApiService : ApiService {

    @POST("/api/jwb/bit101/score")
    suspend fun start(
        @Body body: ScoreQueryModel.Body,
    ): Response<ScoreQueryModel.ChallengeEnvelope>

    @GET("/api/auth/{challengeId}")
    suspend fun poll(
        @Path("challengeId") challengeId: String,
        @Header("X-Challenge-Token") challengeToken: String,
    ): Response<ScoreQueryModel.Status>

    @POST("/api/jwb/bit101/score")
    suspend fun fetch(
        @Body body: ScoreQueryModel.Body,
        @Header("Authorization") authorization: String,
    ): Response<GetScoreDataModel.Response>
}
