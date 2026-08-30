package cn.bit101.android.features.seat.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import cn.bit101.android.config.user.base.LoginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import javax.inject.Inject

class SeatCasLogin @Inject constructor(
    private val loginStatus: LoginStatus,
) {
    companion object {
        private const val TAG = "SeatCasLogin"
        private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"
    }

    /** Opens seatlib in system browser. With existing SSO cookie, phpCAS session establishes automatically. */
    fun openCasLogin(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SEATLIB_BASE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "opened seatlib in browser")
        } catch (e: Exception) {
            Log.d(TAG, "failed to open browser: ${e.message}")
        }
    }

    /** Checks if seatlib has an active phpCAS session. */
    suspend fun hasActiveSession(): Boolean = withContext(Dispatchers.IO) {
        trySilentAuth().isNotEmpty()
    }

    /** Tries silent auth via existing cookies. Returns JWT token or empty string. */
    suspend fun trySilentAuth(): String = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                .cookieJar(createCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            // Visit homepage to populate cookies
            client.newCall(Request.Builder().url(SEATLIB_BASE).get().build()).execute().body?.close()
            // Check if phpCAS session is active
            val res = client.newCall(
                Request.Builder()
                    .url("$SEATLIB_BASE/api/cas/user")
                    .header("Content-Type", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val body = res.body?.string() ?: "{}"
            res.close()
            val json = JSONObject(body)
            val member = json.optJSONObject("member")
            if (member != null && !member.isNull("token")) member.optString("token", "") else ""
        }.getOrNull() ?: ""
    }

    fun createCookieJar() = loginStatus.cookieManager.let { cm ->
        object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { c ->
                    val hc = HttpCookie(c.name, c.value)
                    hc.domain = c.domain ?: url.host; hc.path = c.path; hc.secure = c.secure
                    if (c.expiresAt != Long.MAX_VALUE) hc.maxAge = maxOf(0L, (c.expiresAt - System.currentTimeMillis()) / 1000)
                    cm.cookieStore.add(URI.create("${url.scheme}://${url.host}"), hc)
                }
            }
            override fun loadForRequest(url: HttpUrl) = cm.cookieStore.get(URI.create("${url.scheme}://${url.host}"))
                .map { hc -> Cookie.Builder().name(hc.name).value(hc.value)
                    .domain(hc.domain ?: url.host).path(hc.path ?: "/").apply { if (hc.secure) secure() }.build() }
        }
    }
}
