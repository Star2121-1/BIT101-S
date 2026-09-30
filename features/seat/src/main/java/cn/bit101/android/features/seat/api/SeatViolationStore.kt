package cn.bit101.android.features.seat.api

import android.content.Context
import cn.bit101.android.data.common.TextFileCache
import cn.bit101.android.features.seat.SeatViolationLogic

/**
 * 「已经见过的违约条目」的小文件存储（`filesDir/seat_renege_keys_<type>`）。
 *
 * ## 只存 key，不存次数
 *
 * 违约**次数的唯一真相在图书馆服务端**（`POST /api/Member/reneges`）——
 * 缓存它反而会在「服务端已经消掉一条」时显示旧数字，属于误导。
 * 这里只留「这条我已经见过」，用来判断**有没有新增**（新增才发通知）。
 *
 * ## ⚠️ 为什么要按 `type` 分文件
 *
 * 座位违约（`type=1`）与研讨室违约（`type=2`）是**两份互不相干的列表**，
 * 合用一个文件会串味：座位那批 key 写进去之后，研讨室第一次拉回来的条目
 * 会被当成「没见过」→ 历史违约被整批当成新增通知。分成两个文件后，
 * 每一类各自有「首次见面只建基线、不通知」的机会（见 [SeatViolationTracker.observe]）。
 *
 * 编码在纯逻辑 [SeatViolationLogic.encode] / [SeatViolationLogic.decode]（有单测），
 * 这里只做读写这点副作用。坏了删掉即可，最坏结果是重发一次通知。
 */
object SeatViolationStore {

    /** 每类违约各自一个文件（理由见类 KDoc）。 */
    private fun fileNameOf(type: Int) = "seat_renege_keys_$type"

    fun read(context: Context, type: Int): Set<String> =
        SeatViolationLogic.decode(TextFileCache.read(context, fileNameOf(type)).orEmpty())

    fun write(context: Context, type: Int, keys: Set<String>) =
        TextFileCache.write(context, fileNameOf(type), SeatViolationLogic.encode(keys))

    /**
     * 这一类是否**建立过基线**。
     *
     * 判据是「能读到这份文件」而不是「内容非空」—— 空集合也是一份有效基线
     * （确实一条违约都没有），不能因为空就每轮都当首次。
     *
     * ⚠️ 读不出来（文件不在 / 权限 / IO 异常）**算没建过基线**：代价只是漏一次通知，
     * 比反过来把一批历史违约当成新增、连着发一串通知要好。
     */
    fun initialized(context: Context, type: Int): Boolean =
        TextFileCache.read(context, fileNameOf(type)) != null
}
