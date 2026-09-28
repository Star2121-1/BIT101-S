package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条**违约记录**（图书馆侧的权威数据）。
 *
 * ## 来源
 *
 * `POST /api/Member/reneges` body `{"type": 1|2}`（`type` 1 = 座位、2 = 研讨室），
 * 就是 h5「我的中心 → 我的违约」那一页用的接口
 * （`seatlib.bit.edu.cn/h5/index.html#/my/contract`，分包 `assets/contract.*.js` +
 * `assets/my.*.js` 里读出来的）。返回 `{"code":1,"data":[…]}`
 * —— **列表直接在 `data` 上**，没有分页、没有嵌套。
 *
 * ⚠️ **这一条推翻了 v1.9.19 的假设**：当时查不到违约接口，只能在本地「推算」；
 * 实际上接口一直存在（未登录时与其他 seatlib 接口一致，是 **HTTP 200 + `code 10001`**，
 * 不是 401，所以盲探时很容易当成「没有这个接口」）。
 *
 * ## 字段
 *
 * 取自 h5 模板 `contract.*.js` 的渲染代码，**只有这五个**：
 * - `nameMerge` = 场馆的合并名（座位 tab 下形如「徐特立馆-三层-…」）
 * - `name` = 具体名（座位 tab 下是座位号）
 * - `time` = 违约时间（服务端给的字符串，不解析）
 * - `status` = `"1"` / `"2"`（h5 用不同颜色）
 * - `statusname` = 状态文案（服务端下发，**以它为准**）
 */
data class RenegeRecord(
    val nameMerge: String = "",
    val name: String = "",
    val time: String = "",
    val status: String = "",
    val statusName: String = "",
) {

    /**
     * 条目的身份键。
     *
     * ⚠️ 接口**不给 id**，所以只能自己合成。用「合并名 + 具体名 + 时间」——
     * 时间精确到分，同一个人在同一分钟内在同一处违约两次是不可能的，
     * 足够当去重/增量判断的键。
     */
    val key: String get() = "$nameMerge|$name|$time"

    /** 展示用的一行：`徐特立馆-三层-… 018`。 */
    fun label(): String {
        val a = nameMerge.trim()
        val b = name.trim()
        return when {
            a.isEmpty() -> b.ifBlank { "违约记录" }
            b.isEmpty() -> a
            // 座位 tab 下 nameMerge 往往已包含场馆信息，name 只是座位号
            else -> "$a $b"
        }
    }
}

/** 解析 `/api/Member/reneges` 的 `data` 数组。 */
internal fun parseRenegeRecords(data: JSONArray): List<RenegeRecord> {
    val out = mutableListOf<RenegeRecord>()
    for (i in 0 until data.length()) {
        val r: JSONObject = data.optJSONObject(i) ?: continue
        out.add(
            RenegeRecord(
                nameMerge = r.optString("nameMerge"),
                name = r.optString("name"),
                time = r.optString("time"),
                status = r.optString("status"),
                statusName = r.optString("statusname").takeIf { it.isNotBlank() }
                    ?: r.optString("statusName"),
            )
        )
    }
    return out
}
