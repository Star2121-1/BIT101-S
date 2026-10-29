package cn.bit101.api.option

import okhttp3.OkHttpClient

data class ApiOption(
    val bit101Client: OkHttpClient,
    val schoolClient: OkHttpClient,

    val webVpnUrls: ApiUrlOption,
    val localUrls: ApiUrlOption,

    val webVpn: Boolean = false,

    /**
     * 只发给 **BIT101 后端同源请求**的自定义头（如 `X-BIT101-Platform` / `X-BIT101-Version`）。
     *
     * ⚠️ 由 [cn.bit101.api.Bit101ApiFactory] 按 origin 精确匹配注入：
     * 非 BIT101 域名的请求会被 `removeHeader` —— **绝不能把这些头漏给学校站点**。
     */
    val bit101Headers: Map<String, String> = emptyMap(),
)