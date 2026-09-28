package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.model.RenegeRecord

/**
 * 座位**违约**相关的纯逻辑。
 *
 * ## ⚠️ 认知更正：数据来自服务端，不是我们推算的
 *
 * v1.9.19 时查不到违约接口，只能在本地「推算」（记录每次预约有没有签到）。
 * **2026-09-28 用户给出 h5 页面 `#/my/contract` 后确认：接口一直存在** ——
 * `POST /api/Member/reneges {"type":1}`，返回图书馆自己的违约列表。
 *
 * 之所以当初没找到：**未登录时它和别的 seatlib 接口一样返回 `HTTP 200 + code 10001`**，
 * 盲探时很容易当成「没这个接口」。
 *
 * ⇒ 现在**以服务端为准**，本地只做两件事：
 * 1. 缓存「已经见过的违约条目」，用于判断**有没有新增**（新增就发一条通知）；
 * 2. 拉不到时把上次的列表显示出来，并标明是离线数据。
 *
 * 本地不再保存任何自己算出来的次数 —— 那是错的来源。
 *
 * ## 契约
 *
 * 规则（`docs/seatlib-contract.md` 第 10 节）：预约后未签到记违约 1 次，
 * **各类违约累计 5 次 → 暂停预约权 7 天**。
 */
object SeatViolationLogic {

    /** 累计到这个次数 → 暂停预约权（规则原文：各类违约累计 5 次）。 */
    const val LIMIT = 5

    /** 暂停的天数（规则原文：暂停预约权 7 日）。 */
    const val SUSPEND_DAYS = 7L

    /** 位置键盘值：服务端 `type`（1 = 座位、2 = 研讨室）。 */
    const val TYPE_SEAT = 1
    const val TYPE_SEMINAR = 2

    /** 距离暂停还剩几次（已到上限返回 0）。 */
    fun remaining(count: Int): Int = (LIMIT - count).coerceAtLeast(0)

    /**
     * 计数摘要。
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
     * 违约类别的展示名。
     *
     * ⚠️ 通知里必须区分 —— 把研讨室违约写成「座位违约」是在说一件没发生的事。
     */
    fun typeLabel(type: Int): String = if (type == TYPE_SEMINAR) "研讨室" else "座位"

    /** 通知标题。 */
    fun noticeTitle(total: Int, type: Int = TYPE_SEAT): String =
        if (total >= LIMIT) "预约权可能已被暂停" else "${typeLabel(type)}违约 $total/$LIMIT"

    /**
     * 新增违约的通知正文，如
     * `座位 018 签到已超时 · 已记 1 次违约，累计 2 次，再 3 次将暂停预约 7 天`。
     *
     * ⚠️ 写「累计 N 次」而不是只说「记了一次」—— 单次违约本身没什么感觉，
     * 真正让人紧张的是「离 5 次还有多远」。
     */
    fun noticeText(label: String, total: Int): String {
        val where = label.trim().ifBlank { "座位预约" }
        val tail = if (total >= LIMIT) {
            "累计 $total 次，按规则预约权暂停 $SUSPEND_DAYS 天"
        } else {
            "累计 $total 次，再 ${remaining(total)} 次将暂停预约 $SUSPEND_DAYS 天"
        }
        return "$where · 被记了 1 次违约，$tail"
    }

    /**
     * 找出**这次才出现**的违约条目。
     *
     * 用 [RenegeRecord.key] 做身份 —— 接口不给 id，只能靠「合并名 + 具体名 + 时间」合成。
     */
    fun newRecords(knownKeys: Set<String>, records: List<RenegeRecord>): List<RenegeRecord> =
        records.filter { it.key !in knownKeys }

    /**
     * 缓存文件的编解码：**一行一个 key**。
     *
     * ⚠️ 不做转义是**有意的**：`key` = `nameMerge|name|time`，三段都来自服务端，
     * 不含换行；万一真混进换行，最多是多出一行垃圾 key，不会破坏其它行。
     */
    fun encode(keys: Collection<String>): String =
        keys.filter { it.isNotBlank() }.distinct().sorted().joinToString("\n")

    fun decode(raw: String): Set<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
