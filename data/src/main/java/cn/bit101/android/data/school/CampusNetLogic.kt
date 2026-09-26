package cn.bit101.android.data.school

/**
 * 深澜（Srun）`cgi-bin/rad_user_info` 响应的解析与格式化（纯函数）。
 *
 * 响应是一行逗号分隔的 CSV（经典 Srun 部署形态），实测样例（2026-09-24）：
 * ```
 * 1120241355,1790237818,1790242108,373674937,147058240,0,389049551406,7156454,
 * 10.195.136.157,0,,40.01,0,0,...,1.01.20220802
 * ```
 * 字段映射（与两个间隔 6s 的样本对照得出，仅 [2] 系统时间在走）：
 * `[0]` 账号、`[1]` 本次上线时间、`[2]` 服务器时间、`[3]/[4]` 本次收/发字节、
 * `[6]` 已用流量（按计费周期累计 = 官方口径的「本月」）、`[7]` 累计在线时长(秒)、
 * `[8]` IP、`[11]` 账户余额(元)。
 *
 * ⚠️ **免登录**：该接口在校园网内任意 IP 可访问（返回请求方所在 NAT 的在线
 * 会话）；校外访问不通 —— UI 据此提示「需校园网环境」。
 */
object CampusNetLogic {

    /** 限速阈值：本月 300 GB 后限速（套餐固定，故硬编码；与官方门户同口径）。 */
    const val LIMIT_BYTES = 300L * 1024 * 1024 * 1024

    /** 提前提醒线：270 GB（限速阈值的 90%）。 */
    const val WARN_BYTES = 270L * 1024 * 1024 * 1024

    /**
     * 解析响应并**区分**「未在线」与「响应不可识别」。
     *
     * Srun 在请求方没有在线会话时返回短文本 `not_online`（不是 CSV）——
     * 这与「不在校内 / 请求失败」是两件事，分开才能给出可处置的提示。
     */
    fun parseResult(body: String): CampusNetResult {
        parse(body)?.let { return CampusNetResult.Online(it) }
        if (body.contains("not_online", ignoreCase = true)) return CampusNetResult.NotOnline
        return CampusNetResult.Failed("响应无法识别")
    }

    /**
     * 解析 CSV；不是合法响应（`not_online` / 错误页 / 空串）时返回 null。
     */
    fun parse(body: String): CampusNetInfo? {
        val fields = body.trim().split(",").map { it.trim() }
        if (fields.size < 12) return null
        if (fields[0].isBlank() || fields[0].contains(' ')) return null

        return runCatching {
            CampusNetInfo(
                userName = fields[0],
                loginEpochSeconds = fields[1].toLong(),
                nowEpochSeconds = fields[2].toLong(),
                bytesIn = fields[3].toLong(),
                bytesOut = fields[4].toLong(),
                bytesTotal = fields[6].toLong(),
                durationSeconds = fields[7].toLong(),
                ip = fields[8],
                balanceYuan = fields[11].toDouble(),
            )
        }.getOrNull()
    }

    /**
     * 本月流量的完整表述：`366.4 GB / 300 GB（已超限速阈值）`。
     *
     * 限额常量就在本文件（提醒侧 `NetFlowChecker` 也引用它们），不需要再传参。
     */
    fun trafficStatusText(bytes: Long): String {
        val used = formatTraffic(bytes)
        val limit = formatTraffic(LIMIT_BYTES)
        return if (bytes >= LIMIT_BYTES) "$used / $limit（已超限速阈值）" else "$used / $limit"
    }

    /** 字节 → GB（1024 进制，保留 1 位小数）。 */
    fun formatTraffic(bytes: Long): String =
        "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    /** 秒 → 「x 天 x 小时」/「x 小时 x 分」/「x 分钟」。 */
    fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        return when {
            minutes < 60 -> "$minutes 分钟"
            minutes < 60 * 48 -> "${minutes / 60} 小时 ${minutes % 60} 分"
            else -> "${minutes / 60 / 24} 天 ${minutes / 60 % 24} 小时"
        }
    }

    /** epoch 秒 → 「MM-dd HH:mm」。 */
    fun formatTime(epochSeconds: Long): String =
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
