package cn.bit101.api.service.school

import cn.bit101.api.helper.Logger
import cn.bit101.api.helper.emptyLogger
import cn.bit101.api.model.common.SchoolCookie
import cn.bit101.api.model.common.SchoolLoginResult
import cn.bit101.api.model.common.SmsCodeHandler
import cn.bit101.api.model.common.SmsCodeRequest
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.service.JxzxehallLogin

class SchoolLoginService(
    private val logger: Logger = emptyLogger,
) {
    suspend fun login(
        username: String,
        password: String,
        smsCodeHandler: SmsCodeHandler? = null,
    ): SchoolLoginResult {
        logger.info("SchoolLoginService", "开始登录 username=${username.take(2)}**** webvpnMode=${NetworkEnv.webvpnMode}")

        // ⚠️ P0：**提交密码之前**先确认 CAS service 是学校域名。
        // BIT-Login 库会把 302 里的 service 解码后直接拿去提交密码，不做任何校验 ——
        // 只要那个 302 指向 example.com，密码密文就落到外部主机手里（salt 对方给的，能解）。
        // 上游 1.4.4 补了同样的闸门（unexpected service URL is rejected before password submission）。
        CasServiceGuard.requireSafeService(probeJxzxehallAuthLocation())

        val initialSession = HttpClient()
        val sso = SsoLogin(
            session = initialSession,
            smsCodeCallback = smsCodeHandler?.let { handler ->
                { context ->
                    logger.info("SchoolLoginService", "请求短信验证码 maskedPhone=${context.maskedPhone} purpose=${context.purpose}")
                    handler.onSmsCode(
                        SmsCodeRequest(
                            phone = context.phone,
                            maskedPhone = context.maskedPhone,
                            purpose = context.purpose,
                        )
                    )
                }
            },
        )
        val login = JxzxehallLogin(sso)

        try {
            login.login(username, password)
            val cookies = login.getSession().cookieDetails().map {
                SchoolCookie(
                    name = it.name,
                    value = it.value,
                    domain = it.domain,
                    path = it.path,
                    secure = it.secure,
                    expiresEpochSeconds = it.expires,
                )
            }
            check(cookies.isNotEmpty()) { "学校登录成功但未返回 Cookie" }
            logger.info("SchoolLoginService", "登录成功 获取到 ${cookies.size} 个 Cookie")
            return SchoolLoginResult(cookies, NetworkEnv.webvpnMode)
        } catch (e: Throwable) {
            logger.err("SchoolLoginService", "登录失败: ${e.message}")
            throw e
        } finally {
            sso.session.close()
            if (sso.session !== initialSession) initialSession.close()
        }
    }

    /**
     * 取教学中心 302 的 `Location`（**不跟随重定向**），只用于校验 `service` 的域名。
     *
     * 与 `JxzxehallLogin` 内部的 preflight 同一个 URL、同一套 header，
     * 因此拿到的 `Location` 与库随后自己那次一致。代价是多一次轻量 GET
     * （不是 CAS 登录，不会加重认证压力）。
     */
    private suspend fun probeJxzxehallAuthLocation(): String? {
        val probe = HttpClient()
        return try {
            probe.get(
                Config.Urls.campus.getValue("jxzxehall_auth"),
                headers = Config.Headers.jxzxehall,
                allowRedirects = false,
            ).location()
        } finally {
            probe.close()
        }
    }
}
