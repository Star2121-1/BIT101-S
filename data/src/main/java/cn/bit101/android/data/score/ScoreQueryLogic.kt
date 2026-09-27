package cn.bit101.android.data.score

/**
 * 成绩查询（BIT101 异步挑战流程）的纯逻辑：轮询终止条件与检查限频。
 *
 * 放在这里而不是仓库里，是为了能单测 —— 这套流程没法和真实服务端对敲地测。
 */
object ScoreQueryLogic {

    /** 教务服务标识：挑战的 `ready_services` 里出现它才可取数。 */
    const val SERVICE_JWB = "jwb"

    /** 轮询间隔（网页版是 350ms，这里给宽一点）。 */
    const val POLL_INTERVAL_MS = 400L

    /** 轮询次数上限（25 × 400ms ≈ 10s，超时就算这次失败）。 */
    const val POLL_MAX_ATTEMPTS = 25

    /**
     * 两次成绩检查的最小间隔（12 小时）。
     *
     * ⚠️ 这条**必须有**：新流程每次检查都要用学号密码走一遍学校统一身份认证，
     * 而检查的触发点是「App 启动 + 每日任务」——不限频就是每次开 App 都登录一次，
     * 直接撞学校风控（2026-09-26 实测：短时间内多次登录会被要求短信二次验证）。
     */
    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** 教务服务是否就绪。 */
    fun isReady(readyServices: List<String>?): Boolean =
        readyServices?.contains(SERVICE_JWB) == true

    /**
     * 挑战**被学校拒绝**（`status = failed`）。
     *
     * ⚠️ 与「过期/超时」分开的意义在于**文案与动作完全不同**：
     * 这条的典型原因是学号或密码不对（2026-09-27 实测：假凭据时 `start` 直接回
     * `failed` 并带 `error = 用户名或密码错误 [...]`），**重试永远不会成功** ——
     * 必须让用户去改密码，而不是「稍后重试」。
     */
    fun isRejected(status: String?): Boolean = status == "failed"

    /** 挑战已过期（`expired`）—— 这次没赶上，下次还有机会。 */
    fun isExpired(status: String?): Boolean = status == "expired"

    /** 挑战已终结（再轮询也没用）。 */
    fun isTerminal(status: String?): Boolean = isRejected(status) || isExpired(status)

    /**
     * 学校要求短信二次验证 —— 后台任务没有交互通道，只能放弃这一次。
     *
     * 这也是本功能当前最大的不确定性：是否触发由学校风控决定，App 侧只能兜住不崩。
     */
    fun needsSms(status: String?): Boolean = status == "waiting_sms"

    /** 距上次检查是否已够久（首次检查 -1 视为该查）。 */
    fun shouldCheck(lastCheckMillis: Long, nowMillis: Long): Boolean =
        lastCheckMillis < 0 || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MS
}
