package cn.bit101.api.model.http.bit101

/**
 * 成绩查询（异步挑战流程）的请求 / 响应模型。
 *
 * 契约来自 BIT101 网页版（`bit101.cn/assets/Score-*.js`）的逆向 + 2026-09-26 实测：
 * 先 `POST /api/jwb/bit101/score` 起认证挑战，轮询 `/api/auth/{id}` 等 `jwb` 就绪，
 * 再带 `Authorization: Bearer` 请求同一路径拿数据。
 */
class ScoreQueryModel private constructor() {

    /**
     * 请求体。`username` / `password` 是**学校统一身份认证**的学号密码
     * （与网页版表单填的完全一致）。
     *
     * [challengeId] 只有「取数据」那一步才带；起挑战时留空（Gson 不序列化 null）。
     */
    data class Body(
        val username: String,
        val password: String,
        val challengeId: String? = null,
        /**
         * **开课时间 / 开课学期**（教务那边叫 `kksj`）—— 这是「按学期查」的参数。
         *
         * 契约来自认证主机的 OpenAPI（`/openapi.json` 的 `JwbScoreRequest`，
         * 2026-10-31 核实）。留 `null` 时不序列化，行为由服务端决定
         * （**实测前不确定**是不是等价于「全部学期」）。
         *
         * ⚠️ 取值格式**未实测**：教务界面上这个下拉一般是 `2025-2026-2` 这种，
         * 也可能要 `2025-2026学年第二学期`。别在下拉里硬编码，先按接口返回的
         * 学期串原样回传（见 `ScoreTable.Table.terms`）。
         */
        val kksj: String? = null,
        /**
         * 是否附带**每门课的详情**（平均分 / 班级占比 / 专业占比 / 最高分…）。
         *
         * 与 [detailed] 的区别**服务端没文档**：两个字段都在 `JwbScoreRequest` 里，
         * `detail` 是 `bool`（默认 false），`detailed` 是 `bool|null`。
         * 大概率 `detailed` 是后来的同义字段（或 `detail` 的弃用替身），
         * 所以两个一起传 true —— 多传一个不存在的字段服务端会忽略。
         */
        val detail: Boolean = false,
        val detailed: Boolean? = null,
    )

    /**
     * `/api/jwb/all_score`（「Get all scores」）的请求体。
     *
     * 单独一个类而不是复用 [Body]：这个接口的契约里**没有 `kksj` / `detail`**，
     * 只有 `detailed`（OpenAPI 的 `JwbAllScoreRequest`）。虽然 FastAPI 默认会忽略
     * 多余字段，但契约不一致的字段传过去只会让将来排障更乱。
     */
    data class AllScoreBody(
        val username: String,
        val password: String,
        val challengeId: String? = null,
        val detailed: Boolean? = null,
    )

    /** 挑战详情（`202` 响应体里的 `detail` 字段）。 */    data class Challenge(
        val challengeId: String? = null,
        val accessToken: String? = null,
        /** `running` / `waiting_sms` / `authenticated` / `failed` / `expired` */
        val status: String? = null,
        val readyServices: List<String>? = null,
        val requestedServices: List<String>? = null,
        val expiresIn: Int? = null,
        /**
         * 失败原因（仅 `status = failed` 时有值），如
         * `用户名或密码错误 [status=401, risk=ustc-token, flow=replaced, captcha=no]`。
         *
         * ⚠️ **必须暴露给用户**：这套流程最常见的失败就是「学号或密码不对」，
         * 而归成「网络失败，稍后重试」的话，用户会一直重试而永远不会成功
         * （2026-09-27 实测确认：`start` 就返回 `failed` + 这条 error）。
         */
        val error: String? = null,
    )

    /** `202` 的外层包装：`{"detail":{...}}`。 */
    data class ChallengeEnvelope(val detail: Challenge? = null)

    /** 轮询响应（字段在顶层）。 */
    data class Status(
        val status: String? = null,
        val readyServices: List<String>? = null,
        /** 失败原因；与 [Challenge.error] 同义（轮询也会带上）。 */
        val error: String? = null,
    )
}
