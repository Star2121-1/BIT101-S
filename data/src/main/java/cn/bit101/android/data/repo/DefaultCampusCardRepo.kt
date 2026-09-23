package cn.bit101.android.data.repo

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.net.WebViewCookieSync
import cn.bit101.android.data.repo.base.CampusCardRepo
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
 * [CampusCardRepo] 的实现：一卡通首页（`dkykt.info.bit.edu.cn`）抓取 + 启发式解析。
 *
 * ## 为什么是「启发式」
 *
 * 一卡通首页在 CAS 登录后渲染的接口还没有抓包实测（2026-09-24），
 * 首版先 GET 首页 HTML、按常见文案（余额 / 过渡余额 / …）正则提取候选值，
 * 并把原文摘要带回给 UI/日志 —— 真机跑一次就知道真实形态，下一版再写精确解析。
 *
 * ## 会话
 *
 * 与 eclass 相同：用户在 App 内 WebView 登录一次（CAS 跳转留在
 * `*.bit.edu.cn` 域内），cookie 落在全局 CookieManager；每次抓取前
 * 先 [syncCookies] 同步进 OkHttp 的 jar。未登录时首页会 302 到 CAS
 * 登录页 —— 快照的 [CampusCardSnapshot.loggedIn] 据此判定。
 */
@Singleton
internal class DefaultCampusCardRepo @Inject constructor(
    private val loginStatus: LoginStatus,
) : CampusCardRepo {

    private companion object {
        const val HOME_URL = "https://dkykt.info.bit.edu.cn/home/openHomePageByCas"

        /** 判定「落在 CAS 登录页」的特征串（CAS gate 的标题/表单）。 */
        val LOGIN_PAGE_HINTS = listOf("统一身份认证", "UsernamePassword", "短信验证码", "/gate")

        /**
         * 余额类字段启发式：`余额` / `过渡余额` 等 + 紧跟的金额。
         * 金额容忍千分位逗号；标签截掉标点，UI 直接显示。
         */
        private val ENTRY_REGEX = Regex(
            pattern = "(过渡余额|账户余额|余额|芯片余额)[^\\d<>]{0,8}([\\d,]+\\.\\d{1,2})",
        )
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

        val html = runCatching {
            client.newCall(
                Request.Builder()
                    .url(HOME_URL)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        }.getOrDefault("")

        val loggedIn = html.isNotEmpty() && LOGIN_PAGE_HINTS.none { html.contains(it) }

        // ⚠️ 同一金额可能被模板渲染两遍（桌面端 + 移动端两段 HTML），
        // 按标签去重，只留第一个
        val entries = ENTRY_REGEX.findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .distinctBy { it.first }

        CampusCardSnapshot(
            entries = entries,
            htmlSnippet = html.take(1200),
            loggedIn = loggedIn,
        ).also {
            // 调试期：真机跑一次就能从 logcat 看到首页真实形态，据此写精确解析
            android.util.Log.d(
                "CampusCardRepo",
                "len=${html.length} loggedIn=$loggedIn entries=$entries head=${html.take(300)}",
            )
        }
    }
}
