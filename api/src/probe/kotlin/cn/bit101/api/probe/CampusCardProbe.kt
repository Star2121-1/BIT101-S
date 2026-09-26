package cn.bit101.api.probe

import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.SsoLogin
import kotlinx.coroutines.runBlocking

/**
 * 一卡通接口探针（调试工具，独立源码集 `src/probe`，**不进 main**）。
 *
 * 用法：`gradlew :api:runProbe -Psid=<学号> -Ppwd=<密码>`（短信验证码写 `api/sms.txt`）
 *
 * ## 已经查明的结论（2026-09-26）——别再重复试
 *
 * 1. **服务端按 UA 分模板**：PC UA 抓 `home/openHomePageByCas` 得到桌面版（只有「我的账户」
 *    等链接），移动 UA 得到「**校园移动服务平台**」（余额 + 个人中心 + 6 个功能按钮）。
 * 2. **流水打不通**：移动版「查询流水」的 href 是 `/myaccount/openMyAccount?openid=...`，
 *    但带会话访问仍 **302 到 `cardpay/openCardRechargeLogin`**（该页只有「学工号 ×2 +
 *    充值金额 + 支付方式」，**没有任何查询入口，也没有密码框**）。
 * 3. **功能菜单是钉钉专属**：6 个按钮由 `GET /home/queryUserFunciton` 下发，而它返回
 *    `{"message":"请使用钉钉客户端访问","success":false}` —— 非钉钉容器拿不到菜单，
 *    所以**拿不到流水的真实入口**。POST 变体分别 302 到 errorPage / 充值页。
 *
 * ⇒ 结论：一卡通流水**只能在 i北理（钉钉）内查看**，App 端无法自动获取（技术 + 权限双重限制）。
 *   若日后学校开放 Web 端查询，用本探针重跑第 2 步即可验证。
 */
fun main() = runBlocking {
    val sid = System.getenv("probe.sid") ?: error("usage: -Psid=<学号> -Ppwd=<密码>")
    val pwd = System.getenv("probe.pwd") ?: error("usage: -Psid=<学号> -Ppwd=<密码>")

    NetworkEnv.ensureInitialized()

    val session = HttpClient()
    val smsFile = java.io.File("sms.txt")

    val sso = SsoLogin(
        session = session,
        smsCodeCallback = { context ->
            println("[SMS] 已发送到 ${context.maskedPhone}，请把验证码写入 api/sms.txt")
            var code: String? = null
            while (code.isNullOrBlank()) {
                Thread.sleep(2000)
                if (smsFile.exists() && smsFile.readText().trim().isNotEmpty()) {
                    code = smsFile.readText().trim()
                    smsFile.delete()
                }
            }
            code
        },
    )

    val service = "https://dkykt.info.bit.edu.cn/home/openHomePageByCas"
    sso.login(sid, pwd, callbackUrl = service)
    println("[OK] CAS 登录成功")

    val mobileUa = "Mozilla/5.0 (Linux; Android 13; SM-S9110) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // 1) 余额（移动模板）
    val home = session.get(service, mapOf("User-Agent" to mobileUa))
    val homeBody = home.bodyText
    val openid = Regex("openid=([A-F0-9]{16,})").find(homeBody)?.groupValues?.get(1)
    println("[HOME] status=${home.status} len=${homeBody.length} openid=$openid")
    Regex("(余额|账户余额)[^0-9]{0,10}([\\d,]+\\.\\d{1,2})").findAll(homeBody)
        .forEach { println("[BAL] ${it.groupValues[1]} = ${it.groupValues[2]}") }
    Regex("href=\"([^\"]*openMyAccount[^\"]*)\"[^>]*>([^<]{0,12})").findAll(homeBody)
        .forEach { println("[LINK] ${it.groupValues[2].trim()} -> ${it.groupValues[1]}") }

    // 2) 流水页可达性（预期：302 到卡务充值页 —— 见文件头结论）
    if (openid != null) {
        runCatching {
            val r = session.get(
                "https://dkykt.info.bit.edu.cn/myaccount/openMyAccount?openid=$openid",
                mapOf("User-Agent" to mobileUa),
            )
            println("[FLOW] status=${r.status} url=${r.url} len=${r.bodyText.length}")
        }.onFailure { println("[FLOW] 失败: ${it.message}") }
    }

    // 3) 功能菜单（预期：{"message":"请使用钉钉客户端访问"}）
    if (openid != null) {
        runCatching {
            val r = session.get(
                "https://dkykt.info.bit.edu.cn/home/queryUserFunciton" +
                    "?openid=$openid&usertype=1&idserial=$sid",
                mapOf("User-Agent" to mobileUa),
            )
            println("[MENU] status=${r.status} body=${r.bodyText.take(200)}")
        }.onFailure { println("[MENU] 失败: ${it.message}") }
    }

    // 4) 校园网（Srun 自助）：仅校园网内可达
    runCatching {
        val r = session.get("http://10.0.0.55/cgi-bin/rad_user_info")
        println("[NET] status=${r.status} body=${r.bodyText.take(200)}")
    }.onFailure { println("[NET] 失败: ${it.message}") }

    session.close()
}
