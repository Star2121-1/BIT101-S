package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条**研讨间**预约（图书馆的「空间预约」里除座位之外的另一类资源）。
 *
 * ## 来源与结论
 *
 * `POST /api/Member/seminar`（body 空 `{}`）→ `{"code":1,"data":[…]}`。
 * 2026-09-28 从 h5 的分包里读出来的（**静态资源，不用登录**）：
 * 列表页 `assets/seminar.*.js`，接口声明在 `assets/my.*.js`。
 *
 * ★ **研讨间不是另一套系统** —— 它与座位是同一个后端、同一套形状：
 *
 * | 座位 | 研讨间 |
 * |---|---|
 * | `/api/Seat/tree` | `/api/Seminar/tree` |
 * | `/api/Seat/date` | `/api/Seminar/date` |
 * | `/api/Seat/seat` | `/api/Seminar/seminar` |
 * | `/api/Seat/confirm` | `/api/Seminar/confirm` |
 * | `/api/Member/seat` | `/api/Member/seminar` |
 * | 取消 `/api/Space/cancel` | **同一个** `/api/Space/cancel` |
 *
 * ⇒ 所以「我的研讨间预约」可以直接复用座位的仓库/页签，不需要另起一套。
 *
 * ## 字段
 *
 * 取自 `seminar.*.js` 的渲染代码：`nameMerge`（房间合并名）、`day`、`start`、`end`、
 * `status`、`statusname`，外加取消要用的 `id`。
 *
 * ⚠️ `status` 语义（h5 只对 `"2"` 特判）：**`"2"` = 还能取消**（那一条渲染出「取消预约」按钮），
 * 其余值一律直接显示服务端下发的 `statusname` —— 与座位一样，**文案以服务端为准**。
 */
data class SeminarRecord(
    val id: String = "",
    val nameMerge: String = "",
    val day: String = "",
    val start: String = "",
    val end: String = "",
    val status: String = "",
    val statusName: String = "",
) {

    /** 是否还能取消（服务端口径：`status == "2"`）。 */
    val cancellable: Boolean get() = status == STATUS_CANCELLABLE

    /** 展示用的一行：`2026-09-28 14:00-16:00`。 */
    fun timeText(): String {
        val d = day.trim()
        val s = start.trim().take(5)
        val e = end.trim().take(5)
        return listOf(d, if (s.isEmpty()) "" else "$s-$e")
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    companion object {
        /** h5 里唯一被特判的状态：渲染出「取消预约」按钮。 */
        const val STATUS_CANCELLABLE = "2"
    }
}

/** 解析 `/api/Member/seminar` 的 `data` 数组。 */
internal fun parseSeminarRecords(data: JSONArray): List<SeminarRecord> {
    val out = mutableListOf<SeminarRecord>()
    for (i in 0 until data.length()) {
        val r: JSONObject = data.optJSONObject(i) ?: continue
        out.add(
            SeminarRecord(
                id = r.optString("id"),
                nameMerge = r.optString("nameMerge"),
                day = r.optString("day"),
                start = r.optString("start"),
                end = r.optString("end"),
                status = r.optString("status"),
                statusName = r.optString("statusname").takeIf { it.isNotBlank() }
                    ?: r.optString("statusName"),
            )
        )
    }
    return out
}
