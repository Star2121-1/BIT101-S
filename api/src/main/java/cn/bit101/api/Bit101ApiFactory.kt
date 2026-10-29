package cn.bit101.api

import cn.bit101.api.converter.ConverterFactory
import cn.bit101.api.helper.Logger
import cn.bit101.api.helper.emptyLogger
import cn.bit101.api.option.ApiOption
import cn.bit101.api.option.DEFAULT_API_OPTION
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import okhttp3.HttpUrl
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

object Bit101ApiFactory {
    private val gson = GsonBuilder()
        .setFieldNamingStrategy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    fun create(
        option: ApiOption = DEFAULT_API_OPTION,
        logger: Logger = emptyLogger,
    ): Bit101Api {

        val urls = if(option.webVpn) option.webVpnUrls
        else option.localUrls

        // BIT101 自有请求头：**只对后端同源加**，其它 origin 一律移除。
        // 服务端按 X-BIT101-Platform / X-BIT101-Version 区分客户端；
        // 但这些头绝不能出现在学校域名的请求里（既无意义，也是信息泄漏）。
        val backendUrl = requireNotNull(HttpUrl.parse(urls.bit101Url)) {
            "bit101Url 不是合法 URL：${urls.bit101Url}"
        }
        val clientHeaders = Interceptor { chain ->
            val request = chain.request()
            // ⚠️ 这里必须用 Java 方法调用（`url()` / `scheme()` / `host()`）而不是属性语法：
            // 本项目用的是 okhttp 3.x 的 Java 类，`Request.url` / `HttpUrl.scheme` 这些
            // 同名字段是 package-private，Kotlin 的属性语法会解析到字段上并编译失败。
            val requestUrl = request.url()
            // 只比 scheme + host：BIT101 的入口都是 https 默认端口；
            // 显式比 port 同样受字段可见性限制，而 scheme 已能挡住跨协议泄漏。
            val sameOrigin = requestUrl.scheme() == backendUrl.scheme() &&
                requestUrl.host() == backendUrl.host()
            val builder = request.newBuilder()
            option.bit101Headers.forEach { (name, value) ->
                if (sameOrigin) builder.header(name, value) else builder.removeHeader(name)
            }
            chain.proceed(builder.build())
        }
        val bit101Client = option.bit101Client.newBuilder()
            .addNetworkInterceptor(clientHeaders)
            .build()

        val converterFactory = ConverterFactory(
            stringConverterFactory = ScalarsConverterFactory.create(),
            gsonConverterFactory = GsonConverterFactory.create(gson),
        )

        return Bit101Api(
            bit101Retrofit = Retrofit.Builder()
                .client(bit101Client)
                .addConverterFactory(converterFactory)
                .baseUrl(urls.bit101Url)
                .build(),

            // 认证/代理主机（成绩等教务数据的异步挑战流程走这里）
            loginRetrofit = Retrofit.Builder()
                .client(bit101Client)
                .addConverterFactory(converterFactory)
                .baseUrl(urls.loginBit101Url)
                .build(),

            appRetrofit = Retrofit.Builder()
                .addConverterFactory(converterFactory)
                .baseUrl(urls.androidUrl)
                .build(),

            jwmsRetrofit = Retrofit.Builder()
                .client(option.schoolClient)
                .addConverterFactory(converterFactory)
                .baseUrl(urls.jwmsUrl)
                .build(),

            jwcRetrofit = Retrofit.Builder()
                .client(option.schoolClient)
                .addConverterFactory(converterFactory)
                .baseUrl(urls.jwcUrl)
                .build(),

            // 延河课堂（eclass）。同样挂 schoolClient —— 它的会话是**纯 cookie**，
            // 与学校其它站点共用同一个 cookie store，不需要额外拦截器。
            eclassRetrofit = Retrofit.Builder()
                .client(option.schoolClient)
                .addConverterFactory(converterFactory)
                .baseUrl(urls.eclassUrl)
                .build(),

            logger = logger,
        )
    }
}
