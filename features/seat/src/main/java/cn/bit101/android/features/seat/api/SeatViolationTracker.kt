package cn.bit101.android.features.seat.api

import android.content.Context
import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.SeatViolationLogic
import cn.bit101.android.features.seat.model.RenegeRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 违约记录的「新增检测」：把服务端列表与本地见过的 key 比一比，有新的就发通知。
 *
 * ## 为什么还要有它（既然数据已经来自服务端）
 *
 * 服务端只给**当前列表**，不告诉你「哪一条是刚加上去的」。而违约有实际后果
 * （累计 5 次 → 暂停预约权 7 天，见 `docs/seatlib-contract.md` 第 10 节），
 * 用户需要**在被记的当下**知道，而不是自己想起来去翻「我的违约」。
 *
 * ## 它**不是**数据源
 *
 * 界面上的次数一律用服务端返回的列表长度，本地这份 key 集合只服务于通知；
 * 所以文件丢了/坏了，最坏结果是**重发一次通知**，不会显示错数字。
 *
 * ## ⚠️ 两条容易写错的地方（自检时发现的，别再踩）
 *
 * 1. **首次见面只建基线、不通知**。刚装上 App 的人，服务端列表里可能已经有几条
 *    历史违约 —— 不区分就会一开 App 就「通知」他犯了根本没发生过的错。
 * 2. **座位（`type=1`）与研讨室（`type=2`）的基线必须各存各的**。共用一个集合时，
 *    座位那批 key 会让研讨室的第一次拉取「看起来已经有基线」，于是研讨室的历史违约
 *    被整批当成新增（见 [SeatViolationStore] 的注释）。
 *
 * ## 谁在调它
 *
 * `SeatReservationRepository.refresh()` —— App 内刷新与**组件后台刷新**都会经过那里，
 * 所以不开 App 也能收到通知。
 */
@Singleton
class SeatViolationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 按 `type` 分开的内存缓存，避免每次都读盘。 */
    private val cache = mutableMapOf<Int, Set<String>>()

    @Synchronized
    private fun known(type: Int): Set<String> = cache.getOrPut(type) {
        SeatViolationStore.read(context, type).also {
            if (it.isNotEmpty()) SeatLog.d(TAG) { "type=$type known keys=${it.size}" }
        }
    }

    /**
     * 用刚拉到的服务端列表更新「见过的集合」，并返回**这次才出现**的条目。
     *
     * @param type 服务端的 `type`：1 = 座位、2 = 研讨室
     * @return 新增的违约（**首次建基线时恒为空**）
     */
    @Synchronized
    fun observe(records: List<RenegeRecord>, type: Int): List<RenegeRecord> {
        val firstSync = !SeatViolationStore.initialized(context, type)
        val before = known(type)
        val after = before + records.map { it.key }

        if (firstSync || after != before) {
            cache[type] = after
            SeatViolationStore.write(context, type, after)
        }

        if (firstSync) {
            // 基线：记住「现在有这些」，但一条都不算新增
            SeatLog.d(TAG) { "baseline type=$type: ${records.size} record(s), no notify" }
            return emptyList()
        }

        val fresh = SeatViolationLogic.newRecords(before, records)
        if (fresh.isEmpty()) return emptyList()
        SeatLog.w(TAG, "new renege type=$type fresh=${fresh.size} total=${records.size}")

        // 一次刷新可能同时冒出多条 —— 只发**一条**通知（固定 id，后发覆盖前发），
        // 否则会把通知栏刷屏。正文用「累计 N 次」，这才是用户要的信息。
        val head = fresh.first()
        runCatching {
            cn.bit101.android.features.notify.SeatViolationNotifier.notify(
                context = context,
                title = SeatViolationLogic.noticeTitle(records.size, type),
                text = SeatViolationLogic.noticeText(head.label(), records.size),
            )
        }.onFailure { SeatLog.w(TAG, "notify renege failed: ${it.message}") }
        return fresh
    }

    private companion object {
        const val TAG = "SeatRenege"
    }
}
