package cn.bit101.android.features.seat.api

import android.content.Context
import cn.bit101.android.features.seat.SeatViolationLogic
import java.io.File

/**
 * 「已经见过的违约条目」的小文件存储（`filesDir/seat_renege_keys`）。
 *
 * ## 只存 key，不存次数
 *
 * 违约**次数的唯一真相在图书馆服务端**（`POST /api/Member/reneges`）——
 * 缓存它反而会在「服务端已经消掉一条」时显示旧数字，属于误导。
 * 这里只留「这条我已经见过」，用来判断**有没有新增**（新增才发通知）。
 *
 * 编码在纯逻辑 [SeatViolationLogic.encode] / [SeatViolationLogic.decode]（有单测），
 * 这里只做读写这点副作用。坏了删掉即可，最坏结果是重发一次通知。
 */
object SeatViolationStore {

    private const val FILE_NAME = "seat_renege_keys"

    fun read(context: Context): Set<String> =
        SeatViolationLogic.decode(
            runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull().orEmpty()
        )

    fun write(context: Context, keys: Set<String>) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(SeatViolationLogic.encode(keys))
        }
    }

    /** 读原始文本（调试用）。 */
    fun readRaw(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull()
}
