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
 * `[6]` 累计流量、`[7]` 累计时长(秒)、`[8]` IP、`[11]` 账户余额(元)。
 *
 * ⚠️ **免登录**：该接口在校园网内任意 IP 可访问（返回请求方所在 NAT 的在线
 * 会话）；校外访问不通 —— UI 据此提示「需校园网环境」。
 */
object CampusNetLogic {

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

    /** 字节 → GB（1024 进制，保留 1 位小数）。 */
    fun formatTraffic(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 100) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }

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
