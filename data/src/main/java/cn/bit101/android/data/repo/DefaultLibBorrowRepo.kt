package cn.bit101.android.data.repo

import android.util.Log
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.net.WebViewCookieSync
import cn.bit101.android.data.repo.base.LibBorrowRepo
import cn.bit101.android.data.school.LibBorrowLogic
import cn.bit101.android.data.school.LibBorrowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.gotev.cookiestore.okhttp.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibBorrowRepo] 的实现：抓图书馆「我的借阅」。
 *
 * ## 一次抓取的顺序（顺序有讲究，别调换）
 *
 * 1. 同步 WebView 的 cookie 进 OkHttp；
 * 2. `GET /engine2/header/user-info` 判登录态 —— **这一步不能省**：
 *    未登录时借阅接口一样返回 HTTP 200，而其渲染出的 div 里同样是
 *    `checkHasData([])`，**直接解析会得出「0 本在借」这个危险结论**，
 *    于是真正该提醒的时候反而告诉用户「你没借书」；
 * 3. 抓一次借阅页取 `sversion`（门户的缓存失效参数，接口要带）；
 * 4. 请求 `/application/<appId>/data` 并解析。
 *
 * 解析规则与到期判定都在 [LibBorrowLogic]（纯函数，有单测）。
 */
@Singleton
internal class DefaultLibBorrowRepo @Inject constructor(
    private val loginStatus: LoginStatus,
) : LibBorrowRepo {

    private companion object {
        const val TAG = "LibBorrowRepo"

        /**
         * 门户部署版本号的**兜底值**：正常路径从借阅页里现取，
         * 只有页面抓取失败时才用它（它只是缓存失效参数，不影响数据正确性）。
         */
        const val FALLBACK_SVERSION = "20260928388"
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(loginStatus.cookieManager))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun syncCookies() {
        WebViewCookieSync.sync(
            loginStatus.cookieManager,
            listOf(LibBorrowLogic.PAGE_URL, LibBorrowLogic.LOGIN_URL),
        )
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.onFailure { Log.w(TAG, "GET $url 失败: ${it.message}") }.getOrNull()

    override suspend fun fetchCurrent(): LibBorrowResult = fetch(LibBorrowLogic.APP_ID_CURRENT)

    override suspend fun fetchHistory(): LibBorrowResult = fetch(LibBorrowLogic.APP_ID_HISTORY)

    private suspend fun fetch(appId: String): LibBorrowResult = withContext(Dispatchers.IO) {
        try {
            syncCookies()

            val who = get(LibBorrowLogic.USER_INFO_URL)
                ?: return@withContext LibBorrowResult.Failed("会话检查请求失败")
            when (LibBorrowLogic.isLoggedIn(who)) {
                false -> return@withContext LibBorrowResult.LoggedOut
                null -> return@withContext LibBorrowResult.Failed("会话响应无法识别")
                true -> Unit
            }

            val sversion = get(LibBorrowLogic.PAGE_URL)
                ?.let(LibBorrowLogic::parseSversion)
                ?: FALLBACK_SVERSION

            val body = get(LibBorrowLogic.dataUrl(appId, sversion))
                ?: return@withContext LibBorrowResult.Failed("借阅接口请求失败")

            val records = LibBorrowLogic.parseRecords(body)
                ?: return@withContext LibBorrowResult.Failed("借阅响应无法识别（门户可能已改版）")

            Log.i(TAG, "appId=$appId sversion=$sversion 共 ${records.size} 条")
            LibBorrowResult.Ok(records)
        } catch (e: Exception) {
            Log.w(TAG, "fetch appId=$appId 异常: ${e.message}")
            LibBorrowResult.Failed(e.message ?: "未知异常")
        }
    }
}
