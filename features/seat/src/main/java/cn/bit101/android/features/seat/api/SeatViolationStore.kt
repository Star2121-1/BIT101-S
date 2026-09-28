package cn.bit101.android.features.seat.api

import android.content.Context
import cn.bit101.android.features.seat.SeatViolationLogic
import java.io.File

/**
 * 违约台账的小文件存储（`filesDir/seat_violations`）。
 *
 * 编解码与判定都在纯逻辑 [SeatViolationLogic] 里（有单测），这里只做读写这点副作用。
 * 与 `CampusNetTrafficStore` 同一套约定：单文件、`adb shell cat` 能直接看、坏了删掉重来。
 */
object SeatViolationStore {

    private const val FILE_NAME = "seat_violations"

    fun read(context: Context): Pair<List<SeatViolationLogic.Entry>, List<SeatViolationLogic.Watch>> =
        SeatViolationLogic.decode(runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull().orEmpty())

    fun write(
        context: Context,
        entries: List<SeatViolationLogic.Entry>,
        watches: List<SeatViolationLogic.Watch>,
    ) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(SeatViolationLogic.encode(entries, watches))
        }
    }

    /** 读原始文本（调试用）。 */
    fun readRaw(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull()
}
