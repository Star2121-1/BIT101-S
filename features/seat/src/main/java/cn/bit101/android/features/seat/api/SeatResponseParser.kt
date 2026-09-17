package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.SeatTreeNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/*
 * seatlib 的响应解析。
 *
 * 这些函数被抽成顶层纯函数是为了能直接用**真实抓取的响应体**做单元测试 ——
 * 解析层是最容易出错、也最容易被误以为正确的一层（字段类型与文档不符时会静默给出
 * 空数据或错误数据，而不报错）。
 *
 * 下面所有「实测」注释都来自 2026-09-18 对 seatlib.bit.edu.cn 的真实请求。
 */

/**
 * 未登录的业务码。
 *
 * ⚠️ 实测：seatlib **不使用 HTTP 401** 表达认证失败。
 * `POST /api/Seat/confirm` 与 `/api/Space/cancel` 未登录时返回
 * `HTTP 200` + `{"code":10001,"message":"您尚未登录"}`。
 */
internal const val CODE_NOT_LOGGED_IN = 10001

/**
 * 服务端同一字段的类型并不统一 —— 实测 `/api/Seat/tree` 里 `type` 是字符串 `"1"`，
 * 而 `isValid` 有时是字符串 `"1"` 有时是数字 `1`。
 * 这里显式转换，不依赖 org.json 的隐式强转（不同实现行为不一致）。
 */
internal fun JSONObject.intOrZero(key: String): Int = when (val value = opt(key)) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/** 错误文案：成功响应用 `msg`，错误响应用 `message`，实测两者都会出现。 */
internal fun JSONObject.errorText(): String =
    optString("message").ifBlank { optString("msg") }.ifBlank { "未知错误" }

/**
 * 把业务响应映射为「认证失效」异常；不是认证问题则返回 null。
 *
 * 除已知码 10001 外还兜底匹配「尚未登录」文案 ——
 * 服务端对「未登录」与「登录已过期」可能返回不同的码，
 * 而后者无法在没有真实过期 token 的情况下实测到。
 */
internal fun seatAuthFailure(code: Int, message: String): IOException? =
    if (code == CODE_NOT_LOGGED_IN || message.contains("尚未登录")) IOException(SeatApi.TOKEN_EXPIRED)
    else null

/**
 * 解析 `/api/Seat/tree` 的 `data`，按 children 递归展平为节点列表。
 * 实测层级：校区(type=0) → 楼层(type=0) → 区域(type=1)。
 */
internal fun parseSeatTree(data: JSONArray): List<SeatTreeNode> {
    val nodes = mutableListOf<SeatTreeNode>()
    fun walk(arr: JSONArray, parentId: String?) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.getString("id")
            nodes.add(
                SeatTreeNode(
                    id = id,
                    name = item.getString("name"),
                    type = item.intOrZero("type"),
                    parentId = parentId
                )
            )
            item.optJSONArray("children")?.let { walk(it, id) }
        }
    }
    walk(data, null)
    return nodes
}

/**
 * 解析 `/api/Seat/date` 的 `data`。
 *
 * ⚠️ 实测：`times[].id` / `start` / `end` **恒为 null**（带不带 build_id 都一样），
 * 只有 `day` 是有效的。因此调用方拿到的时段参数总会回落到默认值 ——
 * 回落在这是**正常路径**，不是边界情况。每多一个 time 条目就产出一条 SeatDate，
 * 所以同一天可能出现多条记录。
 */
internal fun parseSeatDates(data: JSONArray): List<SeatDate> {
    val dates = mutableListOf<SeatDate>()
    for (i in 0 until data.length()) {
        val day = data.getJSONObject(i)
        val times = day.optJSONArray("times") ?: continue
        for (j in 0 until times.length()) {
            val t = times.getJSONObject(j)
            dates.add(
                SeatDate(
                    day = day.optString("day", ""),
                    segmentId = if (t.isNull("id")) "" else t.optString("id", ""),
                    start = if (t.isNull("start")) "" else t.optString("start", ""),
                    end = if (t.isNull("end")) "" else t.optString("end", "")
                )
            )
        }
    }
    return dates
}

/**
 * 解析 `/api/Seat/seat` 的 `data`。
 *
 * 实测 `status` 是**字符串**：`"1"` = 可预约、`"2"` = 我已预约、其它 = 被占用。
 * 座位号 `no` 是补零字符串（`"001"`），比较时需用 `seatNumberEquals`。
 */
internal fun parseSeats(data: JSONArray, areaId: String): List<Seat> {
    val seats = mutableListOf<Seat>()
    for (i in 0 until data.length()) {
        val s = data.getJSONObject(i)
        seats.add(
            Seat(
                id = s.getString("id"),
                no = s.getString("no"),
                status = when (s.optString("status")) {
                    "1" -> SeatStatus.AVAILABLE
                    "2" -> SeatStatus.RESERVED
                    else -> SeatStatus.OCCUPIED
                },
                areaId = areaId
            )
        )
    }
    return seats
}
