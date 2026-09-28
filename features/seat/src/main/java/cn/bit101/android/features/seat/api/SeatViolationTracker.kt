package cn.bit101.android.features.seat.api

import android.content.Context
import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.SeatStatusLogic
import cn.bit101.android.features.seat.SeatViolationLogic
import cn.bit101.android.features.seat.SeatViolationLogic.Entry
import cn.bit101.android.features.seat.SeatViolationLogic.Watch
import cn.bit101.android.features.seat.model.ReservationRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 违约台账的执行者：跟踪「我的预约」→ 判定违约 → 落账 → 通知。
 *
 * ## 谁在调它
 *
 * - `SeatReservationRepository.refresh()`：App 内与**组件后台刷新**都会走这里，
 *   所以即使几天不开 App，下次刷新也会把这段时间的违约补记进来。
 * - `SeatViewModel`：手动补记 / 撤销 / 清空。
 *
 * ## ⚠️ 为什么不能只在「列表里还没消失」时判
 *
 * 违约的预约会被服务端**释放**并从有效列表消失 —— 只按「这次还在不在」判断的话，
 * 最常见的一路恰恰会被**系统性漏记**。所以这里是**先登记、后判定**：
 * 见到一次预约就记进跟踪表，等过了「签到截止 + 宽限」仍未见签到才落账。
 */
@Singleton
class SeatViolationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var watches: List<Watch> = emptyList()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        val (e, w) = SeatViolationStore.read(context)
        entries = e
        watches = w
        loaded = true
        if (e.isNotEmpty()) SeatLog.d(TAG) { "loaded ${e.size} violation(s), ${w.size} watch(es)" }
    }

    /** 当前台账（只读快照）。 */
    fun snapshot(): List<Entry> {
        ensureLoaded()
        return entries
    }

    /** 已记的违约次数。 */
    fun count(): Int = snapshot().size

    /**
     * 用最新一次「我的预约」更新跟踪表，并给新确认的违约落账 + 发通知。
     *
     * @return 本次**新记**的违约（为空表示没有变化）
     */
    @Synchronized
    fun observe(
        records: List<ReservationRecord>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Entry> {
        ensureLoaded()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)

        val incoming = records.mapNotNull { record ->
            val deadline = record.signInDeadline(now) ?: return@mapNotNull null
            Watch(
                reservationId = record.id,
                seatLabel = SeatViolationLogic.seatLabelOf(record),
                deadlineMillis = deadline.atZone(zone).toInstant().toEpochMilli(),
                // 只有真正在用 / 暂离才算签到 —— 服务端 statusName 是权威口径
                signedIn = SeatStatusLogic.phaseOf(record).let {
                    it == SeatStatusLogic.Phase.IN_USE || it == SeatStatusLogic.Phase.LEAVE
                },
            )
        }

        val before = watches
        val merged = SeatViolationLogic.prune(
            SeatViolationLogic.upsert(watches, incoming),
            nowMillis,
        )
        val fresh = SeatViolationLogic.detect(merged, entries.map { it.reservationId }.toSet(), nowMillis)
        watches = merged

        if (fresh.isEmpty()) {
            // 跟踪表变了（新预约登记进来了）也要落盘，否则下次冷启动就丢了
            if (merged != before) SeatViolationStore.write(context, entries, merged)
            return emptyList()
        }

        entries = SeatViolationLogic.normalize(entries + fresh)
        SeatViolationStore.write(context, entries, merged)
        SeatLog.w(TAG, "recorded ${fresh.size} violation(s), total=${entries.size}")

        fresh.forEach { entry ->
            // 累计次数按「加了这条之后」算 —— 用户要看的是「我现在离 5 次还有多远」
            val total = entries.indexOfFirst { it.reservationId == entry.reservationId } + 1
            notify(entry, total)
        }
        return fresh
    }

    /**
     * 我们在 App 内主动取消了某次预约 → 它不再是违约候选。
     *
     * ⚠️ 必须显式标记：取消后记录会从列表消失，若不当作「已取消」，
     * 过了截止时刻就会被判成违约 —— 那是对用户正确行为的误罚。
     */
    @Synchronized
    fun markCancelled(reservationId: String) {
        ensureLoaded()
        val hit = watches.firstOrNull { it.reservationId == reservationId } ?: return
        watches = watches.map { if (it.reservationId == reservationId) it.copy(cancelled = true) else it }
        SeatViolationStore.write(context, entries, watches)
        SeatLog.d(TAG, "watch $reservationId marked cancelled (was signedIn=${hit.signedIn})")
    }

    /** 按座位 id 取消（座位图上的取消入口走到这里时只知道座位 id）。 */
    @Synchronized
    fun markCancelledBySeat(seatId: String, seatNo: String = "") {
        ensureLoaded()
        var changed = false
        watches = watches.map { w ->
            // 跟踪表里没存座位 id，用展示标签兜底匹配（"座位 018"）
            val match = (seatNo.isNotBlank() && w.seatLabel.contains(seatNo)) ||
                (seatId.isNotBlank() && w.reservationId == seatId)
            if (match) { changed = true; w.copy(cancelled = true) } else w
        }
        if (changed) SeatViolationStore.write(context, entries, watches)
    }

    /** 手动补记一次（我们漏了、或他处已经吃了一次）。 */
    @Synchronized
    fun addManual(nowMillis: Long = System.currentTimeMillis()): Entry {
        ensureLoaded()
        val entry = Entry(
            reservationId = "manual:$nowMillis",
            atMillis = nowMillis,
            seatLabel = "",
            reason = SeatViolationLogic.Reason.MANUAL,
        )
        entries = SeatViolationLogic.normalize(entries + entry)
        SeatViolationStore.write(context, entries, watches)
        return entry
    }

    /** 撤销一条（记错了 / 实际没违约）。 */
    @Synchronized
    fun undo(reservationId: String) {
        ensureLoaded()
        if (entries.none { it.reservationId == reservationId }) return
        entries = SeatViolationLogic.normalize(entries.filter { it.reservationId != reservationId })
        // ⚠️ 撤销后**不要**把它重新放回候选：否则下个刷新周期会立刻再记一次，
        // 用户会看到「删掉了又自己长回来」。
        watches = watches.map {
            if (it.reservationId == reservationId) it.copy(signedIn = true) else it
        }
        SeatViolationStore.write(context, entries, watches)
    }

    /** 清空台账（新学期 / 已确认权限恢复）。 */
    @Synchronized
    fun clear() {
        entries = emptyList()
        watches = emptyList()
        SeatViolationStore.write(context, entries, watches)
    }

    private fun notify(entry: Entry, total: Int) {
        runCatching {
            cn.bit101.android.features.notify.SeatViolationNotifier.notify(
                context = context,
                title = SeatViolationLogic.noticeTitle(total),
                text = SeatViolationLogic.noticeText(entry.seatLabel, total),
            )
        }.onFailure { SeatLog.w(TAG, "notify violation failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "SeatViolation"
    }
}
