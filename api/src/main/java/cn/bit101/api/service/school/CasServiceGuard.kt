package cn.bit101.api.service.school

import cn.bit101.api.model.common.SchoolDomains
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * CAS `service` 参数的**安全闸门**。
 *
 * ## 为什么需要
 *
 * `JxzxehallLogin`（BIT-Login 库）的流程是：先 GET 教学中心拿 302 的 `Location`，
 * 从里面取出 `service`，**解码后直接**交给 `SsoLogin.login(callbackUrl = ...)` 提交密码。
 * 它**没有做任何域名校验**。
 *
 * 也就是说：只要那个 302 把 `service` 指到 `https://example.com/`，
 * 我们就会把学生密码的 AES 密文提交过去 —— 而 salt 是对方给的，对方能直接解密。
 *
 * 上游 BIT101-Android 1.4.4 正是为此补了
 * `unexpected service URL is rejected before password submission`。
 *
 * ## 策略
 *
 * 只放行同时满足两条的 URL：
 * 1. scheme 必须是 `https`
 * 2. host 必须落在 `*.bit.edu.cn`（含根域）—— 复用 [SchoolDomains.isSchoolDomain]
 *
 * 不满足就抛 [UnsafeServiceException]，**调用方必须在此之后才提交密码**。
 */
object CasServiceGuard {

    /** service URL 不合法（非学校域 / 非 https / 解析失败）。消息用于日志，不含密码等敏感内容。 */
    class UnsafeServiceException(message: String) : IllegalStateException(message)

    /**
     * 校验 `Location` 头里的 `service` 参数，返回**解码后**的 URL。
     *
     * @param locationHeader 教学中心 302 的 `Location` 头原值
     * @throws UnsafeServiceException 缺少 Location / 缺 service / 非 https / 非学校域
     */
    fun requireSafeService(locationHeader: String?): String {
        val location = locationHeader?.trim().orEmpty()
        if (location.isEmpty()) {
            throw UnsafeServiceException("教学中心未返回 Location，无法确认 CAS service")
        }

        val rawService = location.substringAfter("?service=", "").substringBefore('&')
        if (rawService.isEmpty()) {
            throw UnsafeServiceException("教学中心跳转缺少 service 参数")
        }

        val decoded = percentDecode(rawService)
        val uri = runCatching { URI(decoded) }.getOrNull()
            ?: throw UnsafeServiceException("service 不是合法 URL：$decoded")

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw UnsafeServiceException("service 不是 https：$decoded")
        }
        if (!SchoolDomains.isSchoolDomain(uri.host)) {
            throw UnsafeServiceException("service 指向非学校域名（${uri.host}），拒绝提交密码")
        }

        return decoded
    }

    /**
     * 百分号解码，**不把 `+` 当空格**。
     *
     * 为什么不用 `java.net.URLDecoder`：那是 `application/x-www-form-urlencoded` 语义，
     * 会把 `+` 解成空格；而这里的 `service` 是由 Python `quote` 生成的（`+` 会被编码成 `%2B`），
     * 用 URLDecoder 反而会在含 `+` 的路径上解错。
     */
    private fun percentDecode(raw: String): String {
        if ('%' !in raw) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val byte = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
