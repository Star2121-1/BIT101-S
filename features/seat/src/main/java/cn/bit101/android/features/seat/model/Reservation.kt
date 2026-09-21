package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 「我的预约」记录（`/api/index/subscribe` body `{"type":"1"}` 的 data 元素）。
 *
 * 实测字段：
 * - `id` = **预约记录 id**，取消预约时必须传它（`/api/Space/cancel` body `{"id": …}`）
 * - `space` = 座位 id（与 `/api/Seat/seat` 的 `id` 对应）
 * - `no` = 座位号、`areaName` = 「徐特立馆-三层-自然科学图书第一阅览室」
 * - `beginTime` / `endTime` = `"2026-09-18 11:03:27"` 形式
 * - `status` = `"2"` 表示未签到的有效预约
 */
data class ReservationRecord(
    val id: String,
    val seatId: String,
    val seatNo: String,
    val areaId: String? = null,
    val areaName: String = "",
    val beginTime: String = "",
    val endTime: String = "",
    val status: String = "",
    val statusName: String? = null,
) {
    /**
     * 预约时段的开始时刻；解析失败返回 null。
     *
     * ⚠️ 这是**预约时段**的开始，不是「实际刷卡签到时刻」——
     * 服务端不返回后者，所以「已用时长」等推算都以此为准。
     */
    fun beginLocalTime(): LocalDateTime? = parseDateTime(beginTime)

    /** 预约时段的结束时刻；解析失败返回 null。 */
    fun endLocalTime(): LocalDateTime? = parseDateTime(endTime)

    private val beginLocal: LocalDateTime?
        get() = beginLocalTime()

    /** 预约日期（用于判断是当日还是次日预约）。 */
    val reserveDate: LocalDate?
        get() = beginLocal?.toLocalDate()

    /**
     * 签到截止时间。
     *
     * 规则（`/api/index/booking_rules` 原文）：
     * - 预约**当日**：需在开始后的 **60 分钟内**刷卡入馆
     * - 预约**次日**：需在次日 **9:00 前**刷卡入馆
     */
    fun signInDeadline(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val begin = beginLocal ?: return null
        val today = now.toLocalDate()
        return when {
            begin.toLocalDate() == today -> begin.plusMinutes(60)
            begin.toLocalDate() == today.plusDays(1) -> begin.toLocalDate().atTime(9, 0)
            else -> null
        }
    }

    /** 签到提示文案；无法判断时间时返回 null。 */
    fun signInHint(now: LocalDateTime = LocalDateTime.now()): String? {
        val deadline = signInDeadline(now) ?: return null
        val allDay = beginTime.take(10)
        val f = DateTimeFormatter.ofPattern("HH:mm")
        return if (reserveDate == now.toLocalDate()) {
            if (deadline.isAfter(now)) "今日预约 · 请在 ${deadline.format(f)} 前刷卡签到"
            else "今日预约 · 签到已超时"
        } else {
            "次日预约 · 请在 $allDay 09:00 前刷卡签到"
        }
    }

    /** 是否仍未签到（status 2 即有效未签到）。 */
    val isActive: Boolean get() = status == "2"
}

internal fun parseDateTime(raw: String): LocalDateTime? = runCatching {
    LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrNull()

/** 解析 `/api/index/subscribe` 的 data 数组。 */
internal fun parseReservations(data: JSONArray): List<ReservationRecord> {
    val out = mutableListOf<ReservationRecord>()
    for (i in 0 until data.length()) {
        val r: JSONObject = data.getJSONObject(i)
        val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
        out.add(
            ReservationRecord(
                id = id,
                seatId = r.optString("space"),
                seatNo = r.optString("no"),
                areaId = r.optString("area_id").takeIf { it.isNotBlank() },
                areaName = r.optString("areaName"),
                beginTime = r.optString("beginTime"),
                endTime = r.optString("endTime"),
                status = r.optString("status"),
                statusName = r.optString("statusname").takeIf { it.isNotBlank() },
            )
        )
    }
    return out
}
