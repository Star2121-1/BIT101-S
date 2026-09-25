package cn.bit101.android.data.repo

import android.util.Log
import cn.bit101.android.data.school.CampusNetLogic
import cn.bit101.android.data.school.CampusNetResult
import cn.bit101.android.data.repo.base.CampusNetRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CampusNetRepo] 的实现：GET `http://10.0.0.55/cgi-bin/rad_user_info`。
 *
 * 经典 Srun 接口，免登录、免 cookie，返回**请求方所在 NAT** 的在线会话；
 * 仅校园网内可达。
 *
 * ⚠️ 失败要分两类送出（见 [CampusNetResult]）：HTTP 通了但 `not_online`
 * 与连不上是两件事。`http://` 明文能通是因为 `network_security_config.xml`
 * 只对 `10.0.0.55` 放行了明文（2026-09-24 踩过：全局禁明文时请求被系统直接拒）。
 */
@Singleton
internal class DefaultCampusNetRepo @Inject constructor() : CampusNetRepo {

    private companion object {
        const val URL = "http://10.0.0.55/cgi-bin/rad_user_info"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchOnlineInfo(): CampusNetResult = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i("CampusNetRepo", "status=" + resp.code + " len=" + body.length + " body=" + body.take(120))
                if (resp.code == 200) {
                    CampusNetLogic.parseResult(body)
                } else {
                    CampusNetResult.Failed("HTTP " + resp.code)
                }
            }
        }.getOrElse { CampusNetResult.Failed(it.message ?: "未知异常") }
    }
}
