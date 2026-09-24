package cn.bit101.android.data.repo

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.net.WebViewCookieSync
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.school.CampusCardLogic
import cn.bit101.android.data.school.CampusCardSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.gotev.cookiestore.okhttp.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CampusCardRepo] 的实现：一卡通首页（`dkykt.info.bit.edu.cn`）抓取。
 *
 * ## 会话
 *
 * 与 eclass 相同：用户在 App 内 WebView 登录一次（CAS 跳转留在
 * `*.bit.edu.cn` 域内），cookie 落在全局 CookieManager；每次抓取前
 * 先把 WebView 的 cookie 同步进 OkHttp 的 jar。
 *
 * 登录判定与解析规则见 [CampusCardLogic]（纯函数，有单测）。
 */
@Singleton
internal class DefaultCampusCardRepo @Inject constructor(
    private val loginStatus: LoginStatus,
) : CampusCardRepo {

    private companion object {
        const val HOME_URL = "https://dkykt.info.bit.edu.cn/home/openHomePageByCas"
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(loginStatus.cookieManager))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun syncCookies() {
        WebViewCookieSync.sync(loginStatus.cookieManager, listOf(HOME_URL))
    }

    override suspend fun fetchSnapshot(): CampusCardSnapshot = withContext(Dispatchers.IO) {
        syncCookies()

        val (html, finalUrl) = runCatching {
            client.newCall(
                Request.Builder()
                    .url(HOME_URL)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute().use { resp ->
                resp.body?.string().orEmpty() to resp.request.url.toString()
            }
        }.getOrDefault("" to HOME_URL)

        CampusCardLogic.parse(html, finalUrl).also {
            // 调试期：真机跑一次就能从 logcat 看到首页真实形态，据此写精确解析
            android.util.Log.i(
                "CampusCardRepo",
                "finalUrl=$finalUrl len=${it.htmlSnippet.length} loggedIn=${it.loggedIn} " +
                    "entries=${it.entries} head=${it.htmlSnippet.take(200)}",
            )
        }
    }
}
