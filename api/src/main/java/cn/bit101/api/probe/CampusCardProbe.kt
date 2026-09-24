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
        session.close()
}
