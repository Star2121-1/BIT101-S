package cn.bit101.android.data.school

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 图书馆「我的借阅」解析与提醒判定的单测。
 *
 * 输入形态**照抄 2026-09-28 真实抓到的响应**（见 `docs/lib-borrow-contract.md`）：
 * 接口不是干净 JSON —— 它是「JSON 套 HTML，HTML 里的 `<script>` 再内嵌一段 JS 数组」。
 */
class LibBorrowLogicTest {

    private val gson = Gson()

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 28, 20, 0, 0)

    // ── 构造真实形态的响应 ────────────────────────────────────────────

    /** 一个单元格：真实响应里每格都自带 `key`（字段名）。 */
    private fun cell(index: Int, key: String, value: String): String =
        "\"$index\":" + gson.toJson(
            linkedMapOf(
                "pageType" to 1,
                "subs" to null,
                "srcKey" to null,
                "value" to value,
                "key" to key,
                "url" to "",
                "others" to null,
            )
        )

    /** 一行：数字键 + `id`，顺序与真实响应一致。 */
    private fun row(vararg cells: Pair<String, String>): String {
        val body = cells.mapIndexed { i, (k, v) -> cell(i, k, v) }.joinToString(",")
        return "{\"pckedDetail\":true,\"url\":null,\"content\":null,\"pop\":null,$body,\"pageType\":null,\"id\":null}"
    }

    /** 完整响应：`data.div` 是一段 HTML，里面 `checkHasData( ... )` 的实参就是数据。 */
    private fun response(vararg rows: String, code: Int = 1): String {
        val arrayLiteral = rows.joinToString(",", "[", "]")
        val div = "<style>.x{}</style><div class=\"eng\"><script>o.init();this.checkHasData($arrayLiteral);" +
            "bind:function(){}</script></div>"
        return "{\"code\":$code,\"data\":{\"div\":${gson.toJson(div)},\"hide\":false}}"
    }

    private fun oneRecord(): String = row(
        "题名" to "Excel & AI数据计算处理与分析之深度学习",
        "作者" to "(日) 涌井良幸, 涌井贞美著",
        "ISBN" to "978-7-5153-6161-1",
        "馆藏地" to "良乡自然科学图书第二阅览室",
        "借阅日" to "2024-08-20 11:24:28",
        "应还日" to "2024-09-19 11:24:28",
    )

    // ── 解析 ──────────────────────────────────────────────────────────

    @Test
    fun `解析真实响应的一条借阅`() {
        val records = LibBorrowLogic.parseRecords(response(oneRecord()))
        assertEquals(1, records?.size)
        val r = records!!.single()
        assertEquals("Excel & AI数据计算处理与分析之深度学习", r.title)
        assertEquals("(日) 涌井良幸, 涌井贞美著", r.author)
        assertEquals("978-7-5153-6161-1", r.isbn)
        assertEquals("良乡自然科学图书第二阅览室", r.location)
        assertEquals(LocalDateTime.of(2024, 9, 19, 11, 24, 28), r.dueAt)
        assertEquals(LocalDateTime.of(2024, 8, 20, 11, 24, 28), r.borrowedAt)
    }

    /**
     * ⚠️ 最容易错的一处：**没有在借的书**时，服务端返回的是 `checkHasData([])`，
     * 必须解析成**空列表**（确定没有），而不是 null（失败）。
     * 两者混了就会把「0 本」当成「没取到」，或者反过来。
     */
    @Test
    fun `空数组解析成空列表而不是失败`() {
        assertEquals(emptyList<BorrowRecord>(), LibBorrowLogic.parseRecords(response()))
    }

    /** 锚点都找不到 ⇒ **门户改版**，必须报失败，绝不能当成「0 本」。 */
    @Test
    fun `找不到锚点必须报失败而不是 0 条`() {
        val div = "<style>.y{}</style><div>空空如也</div>"
        val body = "{\"code\":1,\"data\":{\"div\":${gson.toJson(div)}}}"
        assertNull(LibBorrowLogic.parseRecords(body))
    }

    @Test
    fun `响应不是 JSON 时返回 null`() {
        assertNull(LibBorrowLogic.parseRecords("<html>504 Gateway Timeout</html>"))
        assertNull(LibBorrowLogic.parseRecords(""))
    }

    @Test
    fun `code 不为 1 时返回 null`() {
        assertNull(LibBorrowLogic.parseRecords(response(oneRecord(), code = 0)))
    }

    @Test
    fun `没有 data 或 div 时返回 null`() {
        assertNull(LibBorrowLogic.parseRecords("{\"code\":1,\"data\":null}"))
        assertNull(LibBorrowLogic.parseRecords("{\"code\":1,\"data\":{}}"))
    }

    /** 单元格顺序被打乱也要按 key 取值 —— 门户调列序不该把我们弄坏。 */
    @Test
    fun `字段顺序打乱仍能正确取值`() {
        val shuffled = row(
            "应还日" to "2026-09-30 10:00:00",
            "馆藏地" to "中关村自然科学阅览室",
            "题名" to "操作系统概念",
            "ISBN" to "978-7-111-12345-6",
            "作者" to "Silberschatz",
            "借阅日" to "2026-08-31 10:00:00",
        )
        val r = LibBorrowLogic.parseRecords(response(shuffled))!!.single()
        assertEquals("操作系统概念", r.title)
        assertEquals("Silberschatz", r.author)
        assertEquals("2026-09-30 10:00:00", r.dueRaw)
        assertEquals(LocalDateTime.of(2026, 9, 30, 10, 0, 0), r.dueAt)
    }

    /** 日期串坏了不能丢整条：书名/馆藏地仍然有用。 */
    @Test
    fun `日期解析失败不丢记录`() {
        val broken = row(
            "题名" to "线性代数",
            "馆藏地" to "良乡",
            "应还日" to "待定",
        )
        val r = LibBorrowLogic.parseRecords(response(broken))!!.single()
        assertEquals("线性代数", r.title)
        assertNull(r.dueAt)
        assertEquals("待定", r.dueRaw)
    }

    @Test
    fun `多条记录按出现顺序解析`() {
        val first = row("题名" to "A", "应还日" to "2026-09-30 08:00:00")
        val second = row("题名" to "B", "应还日" to "2026-10-10 08:00:00")
        val records = LibBorrowLogic.parseRecords(response(first, second))!!
        assertEquals(listOf("A", "B"), records.map { it.title })
    }

    /** 值里带引号 / 花括号也要能正确切出数组（括号配对不能被字符串里的符号骗到）。 */
    @Test
    fun `值里含引号与括号时不误切`() {
        val tricky = row("题名" to "C++ 与 [设计] \"模式\"", "应还日" to "2026-09-30 08:00:00")
        val r = LibBorrowLogic.parseRecords(response(tricky))!!.single()
        assertEquals("C++ 与 [设计] \"模式\"", r.title)
    }

    // ── 会话 ──────────────────────────────────────────────────────────

    @Test
    fun `uid 为空即未登录`() {
        assertEquals(false, LibBorrowLogic.isLoggedIn("{\"code\":1,\"data\":{\"uid\":null}}"))
        assertEquals(false, LibBorrowLogic.isLoggedIn("{\"code\":1,\"data\":{\"uid\":\"\"}}"))
        assertEquals(true, LibBorrowLogic.isLoggedIn("{\"code\":1,\"data\":{\"uid\":\"343326964\"}}"))
    }

    @Test
    fun `会话响应无法识别时返回 null 而不是猜测`() {
        assertNull(LibBorrowLogic.isLoggedIn("<html>portal</html>"))
        assertNull(LibBorrowLogic.isLoggedIn("{\"code\":1}"))
    }

    @Test
    fun `从页面里取 sversion`() {
        val html = "<script>var pageId = 330841;\n var sversion = '20260928388';\n var x=1;</script>"
        assertEquals("20260928388", LibBorrowLogic.parseSversion(html))
        assertNull(LibBorrowLogic.parseSversion("<html>no version</html>"))
    }

    @Test
    fun `数据接口 URL 形状`() {
        val url = LibBorrowLogic.dataUrl(LibBorrowLogic.APP_ID_CURRENT, "20260928388")
        assertTrue(url.startsWith("https://mylib.bit.edu.cn/application/1715437/data?"))
        assertTrue(url.contains("pageId=330841"))
        assertTrue(url.contains("websiteId=162085"))
        assertTrue(url.contains("sversion=20260928388"))
    }

    // ── 到期判定与提醒 ────────────────────────────────────────────────

    private fun record(title: String, due: String) = BorrowRecord(title = title, dueRaw = due)
        .let { it.copy(dueAt = LibBorrowLogic.parseTime(due)) }

    @Test
    fun `距应还日按日期算`() {
        assertEquals(2L, LibBorrowLogic.daysLeft(record("A", "2026-09-30 23:59:59"), now))
        assertEquals(0L, LibBorrowLogic.daysLeft(record("A", "2026-09-28 08:00:00"), now))
        assertEquals(-3L, LibBorrowLogic.daysLeft(record("A", "2026-09-25 08:00:00"), now))
        assertNull(LibBorrowLogic.daysLeft(BorrowRecord(title = "无到期"), now))
    }

    @Test
    fun `提醒范围是逾期加三天内且按最急排序`() {
        val records = listOf(
            record("四天后", "2026-10-02 09:00:00"),
            record("今天", "2026-09-28 18:00:00"),
            record("已逾期", "2026-09-20 09:00:00"),
            record("两天后", "2026-09-30 09:00:00"),
        )
        val alerts = LibBorrowLogic.alertRecords(records, now)
        assertEquals(listOf("已逾期", "今天", "两天后"), alerts.map { it.title })
    }

    @Test
    fun `状态文案`() {
        assertEquals("已逾期 3 天", LibBorrowLogic.statusText(record("A", "2026-09-25 08:00:00"), now))
        assertEquals("今天到期", LibBorrowLogic.statusText(record("A", "2026-09-28 08:00:00"), now))
        assertEquals("明天到期", LibBorrowLogic.statusText(record("A", "2026-09-29 08:00:00"), now))
        assertEquals("2 天后到期", LibBorrowLogic.statusText(record("A", "2026-09-30 08:00:00"), now))
    }

    @Test
    fun `应还日展示为月日`() {
        assertEquals("9月19日", LibBorrowLogic.dueText(record("A", "2024-09-19 11:24:28")))
        assertEquals("待定", LibBorrowLogic.dueText(BorrowRecord(dueRaw = "待定")))
    }

    @Test
    fun `卡片摘要`() {
        assertEquals("当前没有在借的书", LibBorrowLogic.cardSummary(emptyList(), now))
        assertEquals(
            "有 1 本已逾期，尽快归还",
            LibBorrowLogic.cardSummary(listOf(record("A", "2026-09-20 09:00:00")), now),
        )
        assertEquals(
            "有 1 本快到应还日",
            LibBorrowLogic.cardSummary(listOf(record("A", "2026-09-30 09:00:00")), now),
        )
        assertEquals(
            "共 1 本，暂未临近应还",
            LibBorrowLogic.cardSummary(listOf(record("A", "2026-10-30 09:00:00")), now),
        )
    }

    /**
     * ⚠️ **隐私边界**：通知正文**不写书名**（通知会在锁屏上显示）。
     * 与「出分通知只有课名、不含分数」同一条纪律 —— 改动此处前先想清楚。
     */
    @Test
    fun `通知正文不含书名`() {
        val alerts = listOf(
            record("非常私人的一本书名", "2026-09-20 09:00:00"),
            record("另一本也别说", "2026-09-30 09:00:00"),
        )
        val body = LibBorrowLogic.noticeBody(alerts, now)
        assertFalse(body.contains("非常私人的一本书名"))
        assertFalse(body.contains("另一本也别说"))
        assertTrue(body.contains("有 1 本已逾期"))
        assertTrue(body.contains("有 1 本将在 3 天内到期"))
        assertTrue(body.contains("9月30日"))
    }

    @Test
    fun `通知标题区分逾期与否`() {
        assertEquals(
            "图书馆借阅已逾期",
            LibBorrowLogic.noticeTitle(listOf(record("A", "2026-09-20 09:00:00")), now),
        )
        assertEquals(
            "图书馆借阅即将到期",
            LibBorrowLogic.noticeTitle(listOf(record("A", "2026-09-30 09:00:00")), now),
        )
    }

    @Test
    fun `没有要提醒的就不发通知`() {
        val alerts = LibBorrowLogic.alertRecords(listOf(record("A", "2026-10-30 09:00:00")), now)
        assertTrue(alerts.isEmpty())
        assertEquals("", LibBorrowLogic.noticeBody(alerts, now))
    }

    @Test
    fun `去重键由书名加 ISBN 加应还日构成`() {
        val a = BorrowRecord(title = "A", isbn = "1", dueRaw = "2026-09-30")
        val b = BorrowRecord(title = "A", isbn = "1", dueRaw = "2026-10-30")
        assertTrue(a.key != b.key)
        assertEquals(a.key, BorrowRecord(title = "A", isbn = "1", dueRaw = "2026-09-30").key)
    }
}
