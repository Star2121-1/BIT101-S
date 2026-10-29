package cn.bit101.android.data.repo.base

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 登录/身份验证失败的**冷却与退避**策略（纯函数，无 IO、无 Android 依赖）。
 *
 * ## 为什么需要它
 *
 * BIT101 服务端（`login.bit101.flwfdd.xyz`）代所有用户做学校统一身份认证。
 * 2026-10-30 该机被学校 WAF **整 IP 封禁**（一天 4000~7000 次 CAS 登录，被判撞库）。
 * 客户端若在失败后立刻重试，会把这台机器的请求量进一步放大，让封禁更难解除。
 *
 * 因此失败后**必须冷却**：身份验证从 24 小时起指数退避，普通请求从 30 分钟起。
 *
 * ## 与上游的关系
 *
 * 算法对齐上游 BIT101-Android 1.4.2 的 `CommunitySessionPolicy.retryAt`，
 * 但只保留本仓库实际需要的部分（不引入对方的会话分治字段）。
 *
 * ## 约定
 *
 * - 身份验证（`identity = true`）：24h → 48h → 96h → 192h（封顶 attempt=4）
 * - 普通请求（`identity = false`）：30min → 2h → 24h（封顶 attempt≥3）
 * - 服务端给了 `Retry-After` 时**取两者较大值**：本地估算是下限，服务端要求优先
 */
object LoginRetryPolicy {

    /** 身份验证的基准冷却：24 小时。 */
    const val IDENTITY_INTERVAL_MS: Long = 24 * 60 * 60_000L

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /**
     * 计算下一次允许尝试的时间点。
     *
     * @param now 当前时间（毫秒）
     * @param attempt 已连续失败次数（从 1 开始；0 或负数按 1 处理）
     * @param retryAfter 服务端 `Retry-After` 头的原始值，支持「秒数」与 RFC 1123 日期两种格式
     * @param identity 是否为「学校身份验证」这一类（它的冷却更长，因为代价最高）
     * @return 允许再次尝试的时间戳（毫秒）
     */
    fun retryAt(now: Long, attempt: Int, retryAfter: String? = null, identity: Boolean = true): Long {
        val local = now + localDelay(attempt, identity)
        val requested = parseRetryAfter(now, retryAfter)
        // 服务端要求晚于本地估算时听服务端的；早于则仍按本地下限冷却，避免被异常值拉低
        return maxOf(local, requested ?: 0L)
    }

    /** 冷却是否已过（可尝试）。 */
    fun shouldAttempt(now: Long, retryAt: Long): Boolean = now >= retryAt

    /**
     * 生成可直接落库的冷却截止时间（未在冷却中时返回 0，便于「0 表示无冷却」的既有约定）。
     */
    fun nextAttemptAtOrZero(now: Long, retryAt: Long): Long = if (retryAt <= now) 0L else retryAt

    private fun localDelay(attempt: Int, identity: Boolean): Long {
        val safeAttempt = attempt.coerceIn(1, 4)
        return if (identity) {
            // 24h * 2^(n-1)：24h / 48h / 96h / 192h
            IDENTITY_INTERVAL_MS * (1L shl (safeAttempt - 1))
        } else {
            when (safeAttempt) {
                1 -> 30 * MINUTE
                2 -> 2 * HOUR
                else -> 24 * HOUR
            }
        }
    }

    /**
     * 解析 `Retry-After`。两种合法格式：
     * - 秒数：`3600`
     * - HTTP 日期：`Wed, 21 Oct 2026 07:28:00 GMT`
     *
     * 解析不了就返回 null（**不抛异常**：这个头是服务端给的，格式脏不该影响登录流程）。
     */
    private fun parseRetryAfter(now: Long, retryAfter: String?): Long? {
        val raw = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val seconds = raw.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0) return null
            return runCatching { Math.addExact(now, Math.multiplyExact(seconds, 1000L)) }.getOrNull()
        }

        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
