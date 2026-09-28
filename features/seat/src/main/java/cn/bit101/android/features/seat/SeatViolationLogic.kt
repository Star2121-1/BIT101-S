package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.model.ReservationRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 座位**违约计数**的纯逻辑。
 *
 * ## 为什么要有它
 *
 * 规则（`docs/seatlib-contract.md` 第 10 节）：预约后未签到记违约 1 次，
 * **各类违约累计 5 次 → 暂停预约权 7 天**。而座位系统**不提供任何违约次数接口**，
 * 官方前端也从不提示 —— 用户往往在被暂停的那一刻才知道自己已经攒了 5 次。
 *
 * 所以这里做一份**本地台账**：把我们观察到的每一次「预约了但没签到」记下来，
 * 并据此在临近上限时预警。
 *
 * ## ⚠️ 它是推算，不是权威数据
 *
 * - 服务端**不返回签到时刻**，`/api/index/subscribe` 只给 `status`（`"2"` = 未签到）
 *   与 `statusname`。判据因此是「**签到截止时刻已过 + 我们从未看到它进入使用中/暂离**」。
 * - 用户在网页端/别的设备取消预约时我们看不到 → 可能**多记**；
 *   服务端提前释放记录而我们没再观察到 → 也可能**漏记**。
 *   ⇒ UI 必须给**手动修正**入口（撤销 / 补记），并且文案写明是「按规则推算」。
 *
 * ## 判定用的宽限期
 *
 * 截止时刻刚过时**不立刻判**：服务端状态更新有延迟，且刷卡可能是「卡点」发生的。
 * 过了 [GRACE_MINUTES] 分钟仍未见签到才落账 —— 宁可晚一点，也不要误报。
 */
object SeatViolationLogic {

    /** 累计到这个次数 → 暂停预约权（规则原文：各类违约累计 5 次）。 */
    const val LIMIT = 5

    /** 暂停的天数（规则原文：暂停预约权 7 日）。 */
    const val SUSPEND_DAYS = 7L

    /** 签到截止后的宽限（分钟）：给服务端状态更新与「卡点刷卡」留余地。 */
    const val GRACE_MINUTES = 30L

    const val GRACE_MILLIS: Long = GRACE_MINUTES * 60_000L

    /**
     * 跟踪记录保留多久。
     *
     * 只是避免文件无限增长 —— 超过这个时间的预约早已过了判定窗口
     * （要么已签到、要么已落账）。
     */
    const val WATCH_TTL_MILLIS: Long = 7 * 24 * 60 * 60 * 1000L

    /** 一条违约记录的来源。 */
    enum class Reason(val code: String) {
        /** 我们观察到「未签到 + 已过截止」 */
        MISSED_SIGN_IN("missed"),

        /** 用户手动补记（我们漏记了、或在别处已经吃了一次） */
        MANUAL("manual"),
        ;

        companion object {
            fun of(code: String): Reason? = entries.firstOrNull { it.code == code }
        }
    }

    /** 台账里的一条违约。 */
    data class Entry(
        /** 预约记录 id；手动补记的是 `manual:<毫秒>` */
        val reservationId: String,
        /** 判定违约的时刻（= 签到截止 + 宽限，确定性值，不随打开时间漂移） */
        val atMillis: Long,
        val seatLabel: String,
        val reason: Reason = Reason.MISSED_SIGN_IN,
    )

    /**
     * 一次「还没结论」的预约 —— 我们盯着它，看它最终有没有签到。
     *
     * ⚠️ 必须持久化：违约的预约会被服务端**释放**并从有效列表里消失，
     * 只靠「这次刷新还在不在列表里」判断会**系统性漏记**（最常见的恰恰是这一路）。
     */
    data class Watch(
        val reservationId: String,
        val seatLabel: String,
        val deadlineMillis: Long,
        val signedIn: Boolean = false,
        val cancelled: Boolean = false,
    )

    // ------------------------------------------------------------------ 组装

    /** 展示用的座位描述（与 [SeatStatusLogic] 的口径一致：优先座位号）。 */
    fun seatLabelOf(record: ReservationRecord): String =
        if (record.seatNo.isBlank()) record.areaName.ifBlank { "已预约座位" }
        else "座位 ${record.seatNo}"

    /**
     * 把一次预约登记进跟踪表。已签到 / 已取消的标记**只升不降** ——
     * 服务端偶尔会短暂退回旧状态，降回去会把已签到的误判成违约。
     */
    fun upsert(watches: List<Watch>, incoming: List<Watch>): List<Watch> {
        val byId = watches.associateBy { it.reservationId }.toMutableMap()
        incoming.forEach { w ->
            val old = byId[w.reservationId]
            byId[w.reservationId] = if (old == null) w else old.merge(w)
        }
        return byId.values.sortedBy { it.deadlineMillis }
    }

    private fun Watch.merge(other: Watch): Watch = copy(
        signedIn = signedIn || other.signedIn,
        cancelled = cancelled || other.cancelled,
    )

    /**
     * 从跟踪表里找出**已经可以落账**的违约。
     *
     * @param countedIds 台账里已有的预约 id（同一条预约只记一次）
     * @param nowMillis 当前时刻
     */
    fun detect(
        watches: List<Watch>,
        countedIds: Set<String>,
        nowMillis: Long,
    ): List<Entry> = watches
        .filter { !it.signedIn && !it.cancelled }
        .filter { it.reservationId !in countedIds }
        .filter { nowMillis >= it.deadlineMillis + GRACE_MILLIS }
        .map {
            Entry(
                reservationId = it.reservationId,
                // ⚠️ 记「截止 + 宽限」而不是「现在」：App 三天没打开也不会把
                // 违约时刻写成打开的那一天，台账才能与人对得上。
                atMillis = it.deadlineMillis + GRACE_MILLIS,
                seatLabel = it.seatLabel,
            )
        }

    /** 丢掉已过期的跟踪记录（见 [WATCH_TTL_MILLIS]），按截止时刻判断。 */
    fun prune(watches: List<Watch>, nowMillis: Long): List<Watch> =
        watches.filter { nowMillis - it.deadlineMillis < WATCH_TTL_MILLIS }

    /** 台账按预约 id 去重（正常不会重复，手工编辑文件可能产生）。 */
    fun normalize(entries: List<Entry>): List<Entry> =
        entries.distinctBy { it.reservationId }.sortedBy { it.atMillis }

    /** 距离暂停还剩几次（已到上限返回 0）。 */
    fun remaining(count: Int): Int = (LIMIT - count).coerceAtLeast(0)

    // ------------------------------------------------------------------ 文案

    /**
     * 计数摘要（列表页卡片用）。
     *
     * ⚠️ 0 次也要给一句完整的话 —— 空白会被读成「没加载出来」。
     */
    fun summaryText(count: Int): String = when {
        count <= 0 -> "暂无违约记录 · 累计 $LIMIT 次将暂停预约 $SUSPEND_DAYS 天"
        count < LIMIT -> {
            val left = remaining(count)
            "已违约 $count 次 · 再 $left 次将暂停预约 $SUSPEND_DAYS 天"
        }
        else -> "已违约 $count 次 · 按规则预约权暂停 $SUSPEND_DAYS 天"
    }

    /**
     * 单次违约的通知正文，如
     * `座位 018 签到超时 · 已记 1 次违约，累计 2 次，再 3 次将暂停预约 7 天`。
     *
     * ⚠️ 写「累计 N 次」而不是只说「记了一次」—— 单次违约本身没什么感觉，
     * 真正让人紧张的是「离 5 次还有多远」，这才是这条通知的价值。
     */
    fun noticeText(seatLabel: String, total: Int): String {
        val seat = seatLabel.trim().ifBlank { "座位预约" }
        val tail = if (total >= LIMIT) {
            "累计 $total 次，按规则预约权暂停 $SUSPEND_DAYS 天"
        } else {
            "累计 $total 次，再 ${remaining(total)} 次将暂停预约 $SUSPEND_DAYS 天"
        }
        return "$seat 签到已超时 · 已记 1 次违约，$tail"
    }

    /** 通知标题。 */
    fun noticeTitle(total: Int): String =
        if (total >= LIMIT) "预约权可能已被暂停" else "座位违约 $total/$LIMIT"

    /** 最近一次违约的人话时间，如 `9月27日 11:33 · 座位 018`。 */
    fun lastText(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val last = normalize(entries).lastOrNull() ?: return null
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(last.atMillis), zone)
        val day = "${t.monthValue}月${t.dayOfMonth}日 ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        return if (last.seatLabel.isBlank()) day else "$day · ${last.seatLabel}"
    }

    // ------------------------------------------------------------------ 编解码

    /** 落盘格式：每行一条，`E|` 是违约、`W|` 是跟踪中。分隔符统一 `|`。 */
    fun encode(entries: List<Entry>, watches: List<Watch>): String = buildString {
        normalize(entries).forEach { e ->
            append("E|").append(esc(e.reservationId)).append('|')
                .append(e.atMillis).append('|')
                .append(esc(e.seatLabel)).append('|')
                .append(e.reason.code).append('\n')
        }
        watches.sortedBy { it.deadlineMillis }.forEach { w ->
            append("W|").append(esc(w.reservationId)).append('|')
                .append(esc(w.seatLabel)).append('|')
                .append(w.deadlineMillis).append('|')
                .append(if (w.signedIn) 1 else 0).append('|')
                .append(if (w.cancelled) 1 else 0).append('\n')
        }
    }

    /** 解析 [encode] 的产物；坏行直接跳过（这是本地缓存，不值得为它崩）。 */
    fun decode(raw: String): Pair<List<Entry>, List<Watch>> {
        val entries = mutableListOf<Entry>()
        val watches = mutableListOf<Watch>()
        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "E" -> {
                    if (p.size < 5) return@forEach
                    val millis = p[2].toLongOrNull() ?: return@forEach
                    val reason = Reason.of(p[4]) ?: return@forEach
                    entries += Entry(unesc(p[1]), millis, unesc(p[3]), reason)
                }
                "W" -> {
                    if (p.size < 6) return@forEach
                    val deadline = p[3].toLongOrNull() ?: return@forEach
                    watches += Watch(
                        reservationId = unesc(p[1]),
                        seatLabel = unesc(p[2]),
                        deadlineMillis = deadline,
                        signedIn = p[4] == "1",
                        cancelled = p[5] == "1",
                    )
                }
            }
        }
        return normalize(entries) to watches
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    private fun unesc(s: String): String = s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")
}
