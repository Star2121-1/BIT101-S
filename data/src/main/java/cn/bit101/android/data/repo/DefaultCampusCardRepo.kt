package cn.bit101.android.data.repo

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.net.AppHttpClients
import cn.bit101.android.data.net.WebViewCookieSync
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.school.CampusCardLogic
import cn.bit101.android.data.school.CampusCardSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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

    // ⚠️ 首页地址统一在 [CampusCardLogic.HOME_URL] —— 它同时是「校园服务」页
    //    「登录一卡通」按钮的目标，两边必须是同一个字符串（2026-09-30 SSO 审计）

    // ⚠️ 交给 [AppHttpClients.school]：cookie jar + 超时 + 跟随重定向是全 App 同一套
    private val client = AppHttpClients.school(loginStatus.cookieManager)

    private fun syncCookies() {
        WebViewCookieSync.sync(loginStatus.cookieManager, listOf(CampusCardLogic.HOME_URL))
    }

    override suspend fun fetchSnapshot(): CampusCardSnapshot = withContext(Dispatchers.IO) {
        syncCookies()

        val (html, finalUrl, failed) = runCatching {
            client.newCall(
                Request.Builder()
                    .url(CampusCardLogic.HOME_URL)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute().use { resp ->
                Triple(resp.body?.string().orEmpty(), resp.request.url.toString(), false)
            }
        }.getOrElse { Triple("", CampusCardLogic.HOME_URL, true) }

        // ⚠️ 请求失败**不能**当成「未登录」：那会让 UI 说「点开登录」（用户明明登录过），
        // 真正该说的是「获取失败，重试」
        (if (failed) CampusCardSnapshot(emptyList(), "", loggedIn = false, failed = true)
        else CampusCardLogic.parse(html, finalUrl)).also {
            // 调试期：真机跑一次就能从 logcat 看到首页真实形态，据此写精确解析
            android.util.Log.i(
                "CampusCardRepo",
                "finalUrl=$finalUrl len=${it.htmlSnippet.length} loggedIn=${it.loggedIn} " +
                    "entries=${it.entries} head=${it.htmlSnippet.take(200)}",
            )
        }
    }
}
