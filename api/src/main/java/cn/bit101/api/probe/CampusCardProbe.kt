package cn.bit101.api.probe

import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.SsoLogin
import kotlinx.coroutines.runBlocking

/**
 * 一卡通 CAS 登录探针（临时工具）。
 *
 * 用法：`gradlew :api:runProbe -Psid=<学号> -Ppwd=<密码>`
 * SMS 二次验证：触发时程序轮询工作目录 `sms.txt`，写入验证码即继续。
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
                println("[SMS] 已发送到 ${context.maskedPhone}，请把验证码写入 api/sms.txt（UTF-8 纯数字）")
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

        val resp = session.get(service)
        val body = resp.bodyText
        println("[HOME] status=${resp.status} finalUrl=${resp.url}")
        println("[HOME] len=${body.length}")
        Regex("(过渡余额|账户余额|余额|芯片余额)[^0-9]{0,16}([\\d,]+\\.\\d{1,2})")
            .findAll(body)
            .forEach { println("[BAL] ${it.groupValues[1]} = ${it.groupValues[2]}") }
        println("[HEAD] ${body.take(1500)}")

        // 一卡通首页全部链接（找「流水/交易/消费」页）
        Regex("href=\"([^\"]+)\"[^>]*>([^<]{0,20})").findAll(body).forEach {
            println("[LINK] ${it.groupValues[2].trim()} -> ${it.groupValues[1]}")
        }

        // 「我的账户」页：预期含消费流水
        val openid = Regex("openid=([A-F0-9]+)").find(body)?.groupValues?.get(1)
        if (openid != null) {
            runCatching {
                val myUrl = "https://dkykt.info.bit.edu.cn/myaccount/openMyAccount?openid=$openid"
                // 先过一遍充值登录页（可能只是建立临时会话 cookie），再回查
                val warm = session.get("https://dkykt.info.bit.edu.cn/cardpay/openCardRechargeLogin?temporaryopen=true")
                println("[WARM] status=${warm.status}")
                val acc = session.get(myUrl)
                println("[ACC] status=${acc.status} len=${acc.bodyText.length} finalUrl=${acc.url}")
                println("[ACC] head=${acc.bodyText.take(2000)}")
                Regex("(?:href|action)=\"([^\"]+)\"[^>]*>([^<]{0,20})").findAll(acc.bodyText).forEach {
                    println("[ACC-LINK] ${it.groupValues[2].trim()} -> ${it.groupValues[1]}")
                }
                // 带学工号再试一次（cardpay 体系似乎只认学号）
                val acc2 = session.get("https://dkykt.info.bit.edu.cn/myaccount/openMyAccount?openid=$openid&idserial=1120241355")
                println("[ACC2] status=${acc2.status} len=${acc2.bodyText.length} finalUrl=${acc2.url}")
                println("[ACC2] head=${acc2.bodyText.take(800)}")
            }.onFailure { println("[ACC] 失败: ${it.message}") }
        }

        // 校园网自助（10.0.0.55，Srun）：看 302 落到哪、是什么形态
        runCatching {
            val r = session.get("http://10.0.0.55/")
            println("[NET] status=${r.status} finalUrl=${r.url}")
            println("[NET] head=${r.bodyText.take(600)}")
        }.onFailure { println("[NET] 失败: ${it.message}") }
        session.close()
}
