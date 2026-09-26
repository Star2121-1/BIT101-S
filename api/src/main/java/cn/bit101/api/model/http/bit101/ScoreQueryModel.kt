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
    )

    /** 挑战详情（`202` 响应体里的 `detail` 字段）。 */
    data class Challenge(
        val challengeId: String? = null,
        val accessToken: String? = null,
        /** `running` / `waiting_sms` / `authenticated` / `failed` / `expired` */
        val status: String? = null,
        val readyServices: List<String>? = null,
        val requestedServices: List<String>? = null,
        val expiresIn: Int? = null,
    )

    /** `202` 的外层包装：`{"detail":{...}}`。 */
    data class ChallengeEnvelope(val detail: Challenge? = null)

    /** 轮询响应（字段在顶层）。 */
    data class Status(
        val status: String? = null,
        val readyServices: List<String>? = null,
    )
}
