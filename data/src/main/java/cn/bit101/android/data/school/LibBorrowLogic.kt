package cn.bit101.android.data.school

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 图书馆「我的借阅」的解析与提醒判定（**纯逻辑，全部可单测**）。
 *
 * 全部结论来自 2026-09-28 的实测（真实登录 + 浏览器录制），
 * 契约全文见仓库 `docs/lib-borrow-contract.md`。
 *
 * ## 数据是怎么来的（一句话）
 *
 * `mylib.bit.edu.cn` 是**超星智慧门户**：借阅页 (`/page/330841/show`) 的引擎
 * 会向 `/application/<appId>/data` 要数据，**响应体里是一段服务端渲染好的 HTML**，
 * 行数据以 JS 数组字面量的形式内嵌在 `checkHasData( ... )` 的实参里。
 * 所以我们只能**从字符串里把那截数组抠出来再当 JSON 解析** —— 这里就是干这个的。
 *
 * ## 两个实测到的硬判据（别改）
 *
 * 1. 有数据：`checkHasData([{...},{...}])`；没有数据：`checkHasData([])`。
 *    两者**都有** `checkHasData(` 这个锚点，所以「锚点找不到」= 门户改版了，
 *    必须当成**失败**上报，绝不能当成「0 本」。
 * 2. 每个单元格自带字段名（`{"value": ..., "key": "应还日"}`），
 *    所以按字段名取值、**不依赖列顺序**。
 */
object LibBorrowLogic {

    /** 数据域（个人空间）。 */
    const val HOST = "https://mylib.bit.edu.cn"

    /**
     * 在 App 内 WebView 打开这个地址即可完成登录 ——
     * 它会 302 到学校 CAS（`*.bit.edu.cn` 域内，不会跳出 App），
     * 回来后门户会话 cookie 就落在全局 CookieManager 里了。
     */
    const val LOGIN_URL = "https://lib.bit.edu.cn/login"

    /** 「我的借阅」页面。用它取 `sversion`，顺带把会话坐实。 */
    const val PAGE_URL = "$HOST/page/330841/show"

    /** 会话判据：`data.uid` 为空即未登录（未登录时 HTTP 同样是 200）。 */
    const val USER_INFO_URL = "$HOST/engine2/header/user-info"

    /** 引擎 id：「我的借阅」（= 当前在借）。 */
    const val APP_ID_CURRENT = "1715437"

    /** 引擎 id：「历史借阅」。 */
    const val APP_ID_HISTORY = "1697337"

    const val PAGE_ID = "330841"
    const val WEBSITE_ID = "162085"
    const val WFW_FID = "2398"

    /** 到期前多少天开始提醒（可被设置覆盖，这里给默认值）。 */
    const val DUE_SOON_DAYS = 3

    /** 内嵌数组的锚点。找不到它 = 门户改版，必须报失败而不是 0 条。 */
    private const val ARRAY_MARKER = "checkHasData("

    private const val KEY_TITLE = "题名"
    private const val KEY_AUTHOR = "作者"
    private const val KEY_ISBN = "ISBN"
    private const val KEY_LOCATION = "馆藏地"
    private const val KEY_BORROWED = "借阅日"
    private const val KEY_DUE = "应还日"

    private val SVERSION = Regex("""var\s+sversion\s*=\s*'([^']+)'""")
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SHOW_DAY = DateTimeFormatter.ofPattern("M'月'd'日'")

    /** 数据接口。`sversion` 由 [parseSversion] 从页面上取（门户用它做缓存失效）。 */
    fun dataUrl(appId: String, sversion: String): String =
        "$HOST/application/$appId/data" +
            "?sversion=$sversion&mobile=1&wfwfid=$WFW_FID&websiteId=$WEBSITE_ID&pageId=$PAGE_ID"

    // ── 会话与页面 ────────────────────────────────────────────────────

    /**
     * 判定是否已登录。返回 `null` = 响应无法判定（别猜）。
     */
    fun isLoggedIn(body: String): Boolean? {
        val root = body.parseObject() ?: return null
        val data = root.obj("data") ?: return null
        val uid = data.get("uid") ?: return false
        if (uid.isJsonNull) return false
        return uid.str().isNotBlank()
    }

    /** 从页面 HTML 里抠 `var sversion = '...'`。 */
    fun parseSversion(html: String): String? =
        SVERSION.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ── 借阅记录 ──────────────────────────────────────────────────────

    /**
     * 解析 `/application/<appId>/data` 的响应体。
     *
     * @return 记录列表（**空列表 = 服务端明确说没有**）；
     *         `null` = **解析失败**（响应不是 JSON、没有 div、或找不到锚点 ⇒ 门户改版）。
     */
    fun parseRecords(body: String): List<BorrowRecord>? {
        val root = body.parseObject() ?: return null
        if (root.get("code").str() != "1") return null
        val data = root.obj("data") ?: return null
        val div = data.get("div").str()
        if (div.isEmpty()) return null

        val array = extractEmbeddedArray(div) ?: return null
        return array.mapNotNull { element -> element.asJsonObjectOrNull()?.let(::toRecord) }
    }

    /**
     * 把内嵌在 `checkHasData( ... )` 里的数组抠出来。
     *
     * 返回 `null` = **锚点都没有**（门户改版）；
     * 返回空列表 = 锚点在、但确实是 `[]`（没有数据）。两者语义不同，别合并。
     */
    private fun extractEmbeddedArray(div: String): List<JsonElement>? {
        val marker = div.indexOf(ARRAY_MARKER)
        if (marker < 0) return null
        val start = div.indexOf('[', marker)
        if (start < 0) return null
        val end = matchBracket(div, start) ?: return null

        val parsed = runCatching { JsonParser.parseString(div.substring(start, end + 1)) }.getOrNull()
        val arr = parsed as? JsonArray ?: return null
        return arr.toList()
    }

    /** 从 [start]（必须是 `[`）出发，返回配对的 `]` 下标；兼顾字符串与转义。 */
    private fun matchBracket(s: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * 一行 → [BorrowRecord]。**按字段名取值**，取不到的字段留空、不丢整条。
     */
    private fun toRecord(row: JsonObject): BorrowRecord {
        val cells = mutableMapOf<String, String>()
        row.entrySet().forEach { (_, value) ->
            val cell = value.asJsonObjectOrNull() ?: return@forEach
            val key = cell.get("key").str()
            if (key.isNotEmpty()) cells[key] = cell.get("value").str()
        }
        val dueRaw = cells[KEY_DUE].orEmpty()
        return BorrowRecord(
            title = cells[KEY_TITLE].orEmpty(),
            author = cells[KEY_AUTHOR].orEmpty(),
            isbn = cells[KEY_ISBN].orEmpty(),
            location = cells[KEY_LOCATION].orEmpty(),
            borrowedAt = parseTime(cells[KEY_BORROWED]),
            dueAt = parseTime(dueRaw),
            dueRaw = dueRaw,
        )
    }

    /** `yyyy-MM-dd HH:mm:ss`；退一步接受纯日期。解析不出返回 null。 */
    fun parseTime(raw: String?): LocalDateTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text, TIME_FMT) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, DAY_FMT).atStartOfDay() }.getOrNull()
    }

    // ── 到期判定（提醒的核心）──────────────────────────────────────────

    /** 距应还日还有几天（**按日期算**，负数 = 已逾期；无应还日返回 null）。 */
    fun daysLeft(record: BorrowRecord, now: LocalDateTime): Long? =
        record.dueAt?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it.toLocalDate()) }

    /**
     * 需要提醒的：**已逾期** 或 **距到期不超过 [soonDays] 天**。
     * 按应还时间升序（最急的在前）。
     */
    fun alertRecords(
        records: List<BorrowRecord>,
        now: LocalDateTime,
        soonDays: Int = DUE_SOON_DAYS,
    ): List<BorrowRecord> =
        records.filter { r ->
            val left = daysLeft(r, now) ?: return@filter false
            left <= soonDays
        }.sortedBy { it.dueAt }

    /** 一条记录的状态文案。 */
    fun statusText(record: BorrowRecord, now: LocalDateTime): String {
        val left = daysLeft(record, now) ?: return record.dueRaw
        return when {
            left < 0 -> "已逾期 ${-left} 天"
            left == 0L -> "今天到期"
            left == 1L -> "明天到期"
            else -> "$left 天后到期"
        }
    }

    /** 应还日的展示文本（解析不出就退回原文）。 */
    fun dueText(record: BorrowRecord): String =
        record.dueAt?.format(SHOW_DAY) ?: record.dueRaw

    // ── 文案 ──────────────────────────────────────────────────────────

    /** 卡片副标题：借阅总览。 */
    fun cardSummary(records: List<BorrowRecord>, now: LocalDateTime): String {
        if (records.isEmpty()) return "当前没有在借的书"
        val alerts = alertRecords(records, now)
        val overdue = alerts.count { (daysLeft(it, now) ?: 0) < 0 }
        return when {
            overdue > 0 -> "有 $overdue 本已逾期，尽快归还"
            alerts.isNotEmpty() -> "有 ${alerts.size} 本快到应还日"
            else -> "共 ${records.size} 本，暂未临近应还"
        }
    }

    /**
     * 通知正文。
     *
     * ⚠️ **不写书名** —— 与「出分通知只有课名、不含分数」同一条隐私边界：
     * 通知会在锁屏上显示，书目比分数更私人。
     */
    fun noticeBody(alerts: List<BorrowRecord>, now: LocalDateTime): String {
        if (alerts.isEmpty()) return ""
        val overdue = alerts.filter { (daysLeft(it, now) ?: 0) < 0 }
        val soon = alerts.filter { (daysLeft(it, now) ?: 0) >= 0 }
        val parts = mutableListOf<String>()
        if (overdue.isNotEmpty()) parts += "有 ${overdue.size} 本已逾期，请尽快归还"
        if (soon.isNotEmpty()) {
            val earliest = soon.first()
            parts += "有 ${soon.size} 本将在 ${DUE_SOON_DAYS} 天内到期，最早 ${dueText(earliest)} 应还"
        }
        return parts.joinToString("；")
    }

    /** 通知标题。 */
    fun noticeTitle(alerts: List<BorrowRecord>, now: LocalDateTime): String =
        if (alerts.any { (daysLeft(it, now) ?: 0) < 0 }) "图书馆借阅已逾期" else "图书馆借阅即将到期"

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 取子对象；**不是对象（含 JSON null）都返回 null** ——
     *  ⚠️ 不能直接用 gson 的 `getAsJsonObject`：成员是 `null` 时它会抛
     *  `ClassCastException`（实测踩到，单测已锁）。 */
    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun String.parseObject(): JsonObject? =
        runCatching { JsonParser.parseString(this) }.getOrNull().asJsonObjectOrNull()

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
        (this as? JsonObject)?.takeIf { it.size() > 0 }

    private fun JsonElement?.str(): String =
        (this as? JsonPrimitive)?.asString?.takeIf { it != "null" }.orEmpty()
}
