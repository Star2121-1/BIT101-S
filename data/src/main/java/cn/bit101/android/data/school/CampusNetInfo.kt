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
    /** 累计流量（字节，Srun 按计费周期累计——展示为「已用流量」）。 */
    val bytesTotal: Long,
    /** 累计在线时长（秒）。 */
    val durationSeconds: Long,
    val ip: String,
    /** 账户余额（元）——校园网网费不足提醒的数据源。 */
    val balanceYuan: Double,
)
