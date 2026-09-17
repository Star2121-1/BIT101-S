package cn.bit101.android.features.seat

import android.util.Log

/**
 * 座位模块统一的日志出口。
 *
 * 直接调用 `android.util.Log` 有两个问题：
 * 1. release 包里仍会输出调试信息（本模块 `minifyEnabled false`，字符串也会保留在 APK 中）；
 * 2. 调用点散乱，容易把 token、完整响应体这类敏感内容打进日志。
 *
 * 这里统一按 [BuildConfig.DEBUG] 开关，并提供接受 lambda 的重载 ——
 * 这样 release 下连字符串拼接都不会发生。
 */
internal object SeatLog {

    private val enabled = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun d(tag: String, message: () -> String) {
        if (enabled) Log.d(tag, message())
    }

    fun w(tag: String, message: String) {
        if (enabled) Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) Log.e(tag, message, throwable)
    }

    /**
     * 日志里标识某个凭据而不泄露它：只保留前 [keep] 位并附上长度。
     * token 长度本身也是有用的诊断信息（能区分「没拿到」和「拿到了但被拒」）。
     */
    fun mask(secret: String, keep: Int = 6): String =
        if (secret.isEmpty()) "<empty>"
        else if (secret.length <= keep) "***(${secret.length})"
        else "${secret.take(keep)}…(${secret.length})"
}
