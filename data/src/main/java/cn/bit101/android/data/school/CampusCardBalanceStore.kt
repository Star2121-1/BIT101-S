package cn.bit101.android.data.school

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一卡通余额历史的小文件存储（`filesDir/campus_card_balance_history`）。
 *
 * 编码与趋势计算都在纯逻辑 [CampusCardBalanceLogic] 里（有单测），
 * 这里只做「读文件 / 写文件」这点副作用。
 *
 * 用 `filesDir` 而不是 DataStore / Room：
 * - 它是**采样流水**而不是"设置项"，不该混进设置存储
 * - 单文件几十行、只我们自己读，坏了直接删掉重来，没有迁移负担
 */
object CampusCardBalanceStore {

    private const val FILE_NAME = "campus_card_balance_history"

    fun samples(context: Context): List<BalanceSample> =
        CampusCardBalanceLogic.decode(readRaw(context))

    /** 读原始文本（调试用：`adb shell cat files/…` 看到的就是它）。 */
    fun readRaw(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull()

    /**
     * 记一笔余额并返回**记完之后**的趋势。
     *
     * @param amount 本次解析到的余额；null（没解析出来 / 未登录）时只算已有历史，不新增采样
     */
    fun record(
        context: Context,
        amount: Double?,
        atMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BalanceTrend {
        val existing = samples(context)

        val updated = if (amount == null) existing
        else CampusCardBalanceLogic.append(existing, amount, atMillis, zone)

        // 内容没变（同一天、余额也一样）就别写盘 —— 本页每次进都会刷新一次
        if (updated != existing) {
            runCatching {
                File(context.filesDir, FILE_NAME).writeText(CampusCardBalanceLogic.encode(updated))
            }
        }

        return CampusCardBalanceLogic.trend(updated, LocalDate.now(zone), zone)
    }
}
