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
 * ## 谁在调它
 *
 * `SeatReservationRepository.refresh()` —— App 内刷新与**组件后台刷新**都会经过那里，
 * 所以不开 App 也能收到通知。
 */
@Singleton
class SeatViolationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var keys: Set<String>? = null

    private fun known(): Set<String> {
        keys?.let { return it }
        val loaded = SeatViolationStore.read(context)
        keys = loaded
        if (loaded.isNotEmpty()) SeatLog.d(TAG) { "loaded ${loaded.size} known renege key(s)" }
        return loaded
    }

    /**
     * 用刚拉到的服务端列表更新「见过的集合」，并返回**这次才出现**的条目。
     *
     * @return 新增的违约（为空表示没有变化）
     */
    @Synchronized
    fun observe(records: List<RenegeRecord>): List<RenegeRecord> {
        val before = known()
        val fresh = SeatViolationLogic.newRecords(before, records)
        if (fresh.isEmpty()) return emptyList()

        val updated = before + records.map { it.key }
        keys = updated
        SeatViolationStore.write(context, updated)
        SeatLog.w(TAG, "new renege(s)=${fresh.size}, total=${records.size}")

        // 一次刷新可能同时冒出多条 —— 只发**一条**通知（固定 id，后发覆盖前发），
        // 否则会把通知栏刷屏。正文用「累计 N 次」，这才是用户要的信息。
        val head = fresh.first()
        runCatching {
            cn.bit101.android.features.notify.SeatViolationNotifier.notify(
                context = context,
                title = SeatViolationLogic.noticeTitle(records.size),
                text = SeatViolationLogic.noticeText(head.label(), records.size),
            )
        }.onFailure { SeatLog.w(TAG, "notify renege failed: ${it.message}") }
        return fresh
    }

    /** 丢掉「见过的集合」（调试/排查用；正常情况下不需要）。 */
    @Synchronized
    fun reset() {
        keys = emptySet()
        SeatViolationStore.write(context, emptySet())
    }

    private companion object {
        const val TAG = "SeatRenege"
    }
}
