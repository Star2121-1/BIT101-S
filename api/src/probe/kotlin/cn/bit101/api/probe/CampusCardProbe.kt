package cn.bit101.api.probe

import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.SsoLogin
import kotlinx.coroutines.runBlocking

/**
 * 校内站点 CAS 登录探针（调试工具，**独立源码集 `src/probe`，不进 main**）。
 *
 * 用法：`gradlew :api:runProbe -Psid=<学号> -Ppwd=<密码>`
 * SMS 二次验证：触发时轮询工作目录 `sms.txt`，写入验证码即继续。
 *
 * 现覆盖：一卡通（dkykt）登录 → 余额 → 首页链接 → 流水页可达性 → 校园网 Srun。
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
    println("[OK] CAS 登录成功 cookies=${session.cookieMap().keys}")

    // 一卡通首页：余额 + 链接
    val resp = session.get(service)
    val body = resp.bodyText
    val openid = Regex("openid=([A-F0-9]+)").find(body)?.groupValues?.get(1)
    println("[HOME] status=${resp.status} finalUrl=${resp.url} len=${body.length}")
    Regex("(过渡余额|账户余额|余额|芯片余额)[^0-9]{0,16}([\\d,]+\\.\\d{1,2})")
        .findAll(body)
        .forEach { println("[BAL] ${it.groupValues[1]} = ${it.groupValues[2]}") }
    Regex("href=\"([^\"]+)\"[^>]*>([^<]{0,20})").findAll(body).forEach {
        println("[LINK] ${it.groupValues[2].trim()} -> ${it.groupValues[1]}")
    }

    // 流水页可达性 —— 结论：302 到卡务系统（cardpay）充值页，Web 端不可得
    if (openid != null) {
        runCatching {
            val my = "https://dkykt.info.bit.edu.cn/myaccount/openMyAccount?openid=$openid"
            session.get("https://dkykt.info.bit.edu.cn/cardpay/openCardRechargeLogin?temporaryopen=true")
            val acc = session.get(my)
            println("[ACC] status=${acc.status} finalUrl=${acc.url} len=${acc.bodyText.length}")
        }.onFailure { println("[ACC] 失败: ${it.message}") }
    }

    // 校园网（Srun 自助）：仅校园网内可达
    runCatching {
        val r = session.get("http://10.0.0.55/cgi-bin/rad_user_info")
        println("[NET] status=${r.status} body=${r.bodyText.take(200)}")
    }.onFailure { println("[NET] 失败: ${it.message}") }

    session.close()
}
