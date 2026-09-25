package cn.bit101.android.data.school

/**
 * 校园网在线信息（深澜 Srun `cgi-bin/rad_user_info` 的解析产物）。
 */
data class CampusNetInfo(
    val userName: String,
    /** 本次上线时间（epoch 秒）。 */
    val loginEpochSeconds: Long,
    /** 服务器时间（epoch 秒）。 */
    val nowEpochSeconds: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    /** 已用流量（字节）。Srun 的 `[6]` 是**按计费周期累计**，官方门户就显示为「本月」用量。 */
    val bytesTotal: Long,
    /**
     * 本月在线时长（秒）。
     *
     * ⚠️ 同样是**按计费周期**累计，但计时是**按设备**的 —— 同时用多台设备会各记一份，
     * 所以这个值可能超过一个月（用户 2026-09-26 确认的口径）。
     */
    val durationSeconds: Long,
    val ip: String,
    /** 账户余额（元）——校园网网费不足提醒的数据源。 */
    val balanceYuan: Double,
)
