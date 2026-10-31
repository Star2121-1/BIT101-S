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

    /**
     * **全部学期**的成绩（`Get all scores`）。
     *
     * 存在的意义：`/api/jwb/bit101/score` 不带 `kksj` 时很可能只回**当前学期**
     * （2026-10-31 实测该账号回空数组 ⇒ 要么没出分、要么只查了当前学期，无法区分）。
     * 这个接口一次拿全部，是「查历史成绩」最直接的兜底。
     *
     * ⚠️ 返回格式**未实测**，理论上与 [fetch] 同构（同一套解析链）；
     * 解析不出来时 `ScoreTable.parse` 会返回 null，页面报「格式变了」而不是崩。
     */
    @POST("/api/jwb/all_score")
    suspend fun allScore(
        @Body body: ScoreQueryModel.AllScoreBody,
        @Header("Authorization") authorization: String,
    ): Response<GetScoreDataModel.Response>
}
