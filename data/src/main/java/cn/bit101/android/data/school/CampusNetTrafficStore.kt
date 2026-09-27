package cn.bit101.android.data.school

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 校园网流量历史的小文件存储（`filesDir/campus_net_traffic_history`）。
 *
 * 编码与趋势计算都在纯逻辑 [CampusNetTrafficLogic] 里（有单测），这里只做读写这点副作用。
 * 与 `CampusCardBalanceStore` 同一套约定：单文件、`adb shell cat` 能直接看、坏了删掉重来。
 */
object CampusNetTrafficStore {

    private const val FILE_NAME = "campus_net_traffic_history"

    fun samples(context: Context): List<TrafficSample> =
        CampusNetTrafficLogic.decode(readRaw(context))

    /** 读原始文本（调试用）。 */
    fun readRaw(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull()

    /**
     * 记一笔流量采样并返回**记完之后**的趋势。
     *
     * @param bytes 本次读到的本周期累计用量；null（未在线 / 失败）时只算已有历史，不新增采样
     */
    fun record(
        context: Context,
        bytes: Long?,
        atMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): TrafficTrend {
        val existing = samples(context)

        val updated = if (bytes == null) existing
        else CampusNetTrafficLogic.append(existing, bytes, atMillis, zone)

        // 内容没变（同日同值 / 没解析出数据）就别写盘 —— 本页每次进都会刷新一次
        if (updated != existing) {
            runCatching {
                File(context.filesDir, FILE_NAME).writeText(CampusNetTrafficLogic.encode(updated))
            }
        }

        return CampusNetTrafficLogic.trend(updated, LocalDate.now(zone), zone)
    }
}
