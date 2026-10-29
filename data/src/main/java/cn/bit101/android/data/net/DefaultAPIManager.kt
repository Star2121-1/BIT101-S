package cn.bit101.android.data.net

import cn.bit101.android.data.BuildConfig
import android.util.Log
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.net.base.APIManager
import cn.bit101.android.data.repo.base.LoginRefreshResult
import cn.bit101.android.data.repo.base.LoginRepo
import cn.bit101.api.Bit101ApiFactory
import cn.bit101.api.helper.Logger
import cn.bit101.api.option.DEFAULT_API_OPTION
import cn.bit101.api.option.DEFAULT_WEB_VPN_URLS
import cn.bit101.api.option.DEV_URLS
import cn.bit101.api.option.PROD_URLS
import kotlinx.coroutines.runBlocking
import net.gotev.cookiestore.okhttp.JavaNetCookieJar
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider

/**
 * Android 的 Logger，用于在 API 模块使用 Android 的 Log
 */
/**
 * Android 侧的 Logger：API 模块统一用它而不是直接用 Android 的 Log
 *
 * ⚠️ **debug 才真正写 logcat**（外部评审 R-3）：这些日志的正文可能含学号、
 * cookie、响应片段；release 的 logcat 任何有 root 的应用都能读 ⇒ 不该留。
 * 保留方法签名是为了**调用点不用改**（logger 是接口，api 模块不依赖 Android）。
 */
private val androidLogger = object : Logger {
    override fun err(tag: String?, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    override fun warn(tag: String?, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    override fun info(tag: String?, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    override fun debug(tag: String?, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
}

internal class DefaultAPIManager @Inject constructor(
    private val loginStatus: LoginStatus,
    private val loginRepoProvider: Provider<LoginRepo>,
    /**
     * ⚠️ 清登录态**只能走它**：`loginStatus.clear()` 只清 BIT101 自己，
     *    座位侧的 JWT 与任务会留下 —— 表现为「BIT101 显示已登出，
     *    座位还在拿过期 JWT 打接口」。详见 [SessionCleanup]。
     */
    private val sessionCleanup: SessionCleanup,
) : APIManager {
    private val cookiesJar = JavaNetCookieJar(loginStatus.cookieManager)

    private val schoolClient = OkHttpClient.Builder()
        .cookieJar(cookiesJar)
        .build()

    private val bit101Client = OkHttpClient.Builder()
        .cookieJar(cookiesJar)
        .addInterceptor { chain ->
            val fakeCookie = runBlocking {
                loginStatus.fakeCookie.get()
            }

            val request = chain.request().newBuilder()
                .addHeader("fake-cookie", fakeCookie)
                .build()

            // 记录 BIT101 服务端可达性（供主界面降级横幅）；网络异常/5xx 视为不可用
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                loginStatus.bit101Available.value = false
                throw e
            }
            if (response.isSuccessful) {
                loginStatus.bit101Available.value = true
            } else if (response.code in 500..599) {
                loginStatus.bit101Available.value = false
            }

            if (response.code == 401) {
                // 本 client 只走 BIT101（flwfdd）侧。fakeCookie 失效**不代表**学校/座位会话也失效：
                // 不能因为社区接口 401 就把学校登录、座位 JWT 与监控任务一起清掉。
                if (chain.request().header(AUTH_RETRIED_HEADER) != null) {
                    // 重试后仍 401 ⇒ BIT101 侧不可用，交回调用方降级展示，不清学校会话
                    loginStatus.bit101Available.value = false
                    return@addInterceptor response
                }

                // 静默刷新（不弹短信验证码）。refresh 现在以**学校会话**为准，BIT101 失败不影响结果。
                // 使用非阻塞 tryRefreshLogin：互斥锁被持锁流程（checkLogin/login/refreshLogin）占用时
                // 立即返回 BUSY，避免在该流程内部重入同一把非重入锁而死锁
                val refreshed = runBlocking {
                    loginRepoProvider.get().tryRefreshLogin()
                }
                when (refreshed) {
                    LoginRefreshResult.SUCCESS -> {
                        loginStatus.bit101Available.value = true
                        val newFakeCookie = runBlocking {
                            loginStatus.fakeCookie.get()
                        }
                        val retryRequest = chain.request().newBuilder()
                            .addHeader("fake-cookie", newFakeCookie)
                            .addHeader(AUTH_RETRIED_HEADER, "1")
                            .build()
                        return@addInterceptor chain.proceed(retryRequest)
                    }

                    // 持锁流程自身负责刷新与登录态决策，这里直接返回 401，绝不清除登录态
                    LoginRefreshResult.BUSY ->
                        return@addInterceptor response

                    // 确定性认证失败（学校会话已确认失效）→ 清；与旧行为保持一致
                    LoginRefreshResult.FAILED -> {
                        loginStatus.bit101Available.value = false
                        runBlocking { sessionCleanup.clearAll() }
                    }

                    // NEEDS_INTERACTIVE / TRANSIENT：只说明「这次不知道」或需要用户介入，
                    // 不应清学校/座位会话
                    else -> {
                        loginStatus.bit101Available.value = false
                        return@addInterceptor response
                    }
                }
            }

            response
        }
        .build()

    private var webVpn = runBlocking { loginStatus.webVpn.get() }

    private val localUrls = if(BuildConfig.DEBUG) DEV_URLS else PROD_URLS
    private val webVpnUrls = if(BuildConfig.DEBUG) DEV_URLS else DEFAULT_WEB_VPN_URLS

    /**
     * 只随 BIT101 后端同源请求发出的客户端标识（上游 1.4.5 起服务端按此区分客户端）。
     *
     * ⚠️ 刻意**不发 `X-BIT101-Version-Code`**：上游测试里明确断言它为 null。
     *    服务端要的是展示版本号，不是号段（我们的号段是 1001+ 的功能线，语义不同）。
     */
    private val bit101Headers = mapOf(
        "X-BIT101-Platform" to "android",
        "X-BIT101-Version" to BuildConfig.VERSION_NAME,
    )

    private var _api = createApi(webVpn)

    private fun createApi(webVpn: Boolean) = Bit101ApiFactory.create(
        DEFAULT_API_OPTION.copy(
            bit101Client = bit101Client,
            schoolClient = schoolClient,
            localUrls = localUrls,
            webVpnUrls = webVpnUrls,
            webVpn = webVpn,
            bit101Headers = bit101Headers,
        ),
        logger = androidLogger,
    )

    override suspend fun switch(webVpn: Boolean) {
        if(webVpn != this.webVpn) {
            this.webVpn = webVpn
            _api = createApi(webVpn)
        }
        loginStatus.webVpn.set(webVpn)
    }

    override val api
        get() = _api

    private companion object {
        const val AUTH_RETRIED_HEADER = "X-BIT101-AUTH-RETRIED"
    }
}
