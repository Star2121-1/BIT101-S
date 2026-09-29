package cn.bit101.android.data.school

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 某一天的**教学安排覆盖**。
 *
 * 学校每学年会发 2~4 条《…教学安排调整的通知》（节假日放假 + 调休补课）。
 * 我们的课表是「星期几 + 周次」的**循环模板**，表达不了「某一天按另一个星期几上课」，
 * 所以需要这样一层「按日覆盖」。
 */
sealed interface DayPlan {
    /** 放假 / 补休：那天没有教学安排。 */
    data object NoClass : DayPlan

    /**
     * 补课：那天按 [targetWeekday] 的课表上课。
     *
     * 取值同 [DayOfWeek.value]：1=周一 … 7=周日。
     */
    data class MakeUp(val targetWeekday: Int) : DayPlan
}

/**
 * 一天的安排。
 *
 * [note] 是**学校原文的措辞**（如「补休·无教学安排」「按 10月8日（周四）课表上课」），
 * 用于展示 —— 让用户能自己核对，而不是只信我们的结论。
 */
data class TeachingAdjustmentEntry(
    val date: LocalDate,
    val plan: DayPlan,
    val note: String,
    val sourceTitle: String,
    val sourceUrl: String,
)

/** 一条「教学安排调整」通知（供 UI 列出与打开原文）。 */
data class TeachingNotice(val title: String, val url: String)

/**
 * 一次取数的完整结果。
 *
 * ⚠️ [notices] 是**所有**教学安排调整通知，含**没能解析出日期**的那些 ——
 * 那几条也要让用户看到（附原文链接），否则「学校发了通知但 App 没反应」无法解释。
 * [entries] 才是能落到课表上的东西。
 */
data class TeachingAdjustments(
    val entries: List<TeachingAdjustmentEntry>,
    val notices: List<TeachingNotice>,
    val fetchedAtMillis: Long,
)

/**
 * 教学安排调整的**纯解析逻辑**（不依赖 Android，可单测）。
 *
 * ## 数据来源
 * `jxzx.bit.edu.cn/jxyx/kctz/`（教学运行与考务中心 → 教学运行 → 课程调整），
 * **免登录**。标题形如《关于XXXX年中秋节、国庆节教学安排调整的通知》。
 *
 * ## ⚠️ 两条路，都不是万能的（实测 2022~2026 各年通知）
 * - **日历表格**：正文里是「周一~周日 + 日期 + 单元格标注」的表，逐格干净 ⇒ 最可靠
 * - **纯文字**：有的通知没有表（只需文字说明）⇒ 只能靠固定句式抽
 *
 * ## ⚠️⚠️ 铁律：只产出**明确写出来**的安排，认不出就不产出
 * 宁可少一条，也不要**猜**。一个静默错误的课表（比如把上课日标成放假）比没有这个功能糟得多。
 * 因此这里只认四种句式 + 表格标注，**没有宽松兜底**。
 */
object TeachingAdjustmentLogic {

    // ------------------------------------------------------------ 解析

    /** 从列表页抽出所有通知标题与链接（[baseUrl] 是列表页地址，用于补全相对链接）。 */
    fun parseNoticeList(listHtml: String, baseUrl: String): List<TeachingNotice> {
        val base = baseUrl.substringBeforeLast('/', "")
        return LIST_LINK.findAll(listHtml)
            .mapNotNull { m ->
                val title = m.groupValues[2].let(::visibleText)
                if (title.length < 6) return@mapNotNull null
                TeachingNotice(title, joinUrl(base, m.groupValues[1]))
            }
            .distinctBy { it.url }
            .toList()
    }

    /** 标题是不是「教学安排调整」类通知。 */
    fun isAdjustmentNotice(title: String): Boolean = title.contains("教学安排调整")

    /**
     * 解析一条通知正文，得到「日期 → 安排」。
     *
     * 表格优先（更可靠）；表格没覆盖的日期再用文字兜底；同一天冲突时以表格为准。
     */
    fun parseNotice(html: String, title: String, url: String): List<TeachingAdjustmentEntry> {
        val text = visibleText(html)
        val pub = PUBLISH.find(text)?.let { it.groupValues[1].toIntOrNull() }
        val pubMonth = PUBLISH.find(text)?.let { it.groupValues[2].toIntOrNull() }
        val found = LinkedHashMap<LocalDate, Item>()

        fun put(date: LocalDate?, plan: DayPlan, note: String, fromTable: Boolean) {
            if (date == null) return
            val old = found[date]
            // 表格更可靠：已有表格项时不覆盖；文字不覆盖表格
            if (old != null && old.fromTable) return
            found[date] = Item(plan, note.trim().take(56), fromTable)
        }

        // ── ① 日历表格 ──────────────────────────────────────────
        TABLE.findAll(html).forEach { table ->
            val monthCtx = MONTH_CTX.findAll(visibleText(html.substring(0, table.range.first))).lastOrNull()
                ?: return@forEach
            val month = monthCtx.groupValues[2].toIntOrNull() ?: return@forEach
            val year = yearFor(month, pub, pubMonth) ?: return@forEach

            ROW.findAll(table.value).forEach { row ->
                CELL.findAll(row.value).forEach { cell ->
                    val cellText = visibleText(cell.value)
                    val m = CELL_DAY.find(cellText) ?: return@forEach
                    val day = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val rest = m.groupValues[2].trim()
                    if (day !in 1..31 || rest.isEmpty()) return@forEach

                    val makeup = RE_MAKEUP_CELL.find(rest)?.let { weekdayOf(it.groupValues[1]) }
                    if (makeup != null) {
                        put(safeDate(year, month, day), DayPlan.MakeUp(makeup), rest, true)
                    } else if (NOCLASS_WORDS.any { rest.contains(it) }) {
                        put(safeDate(year, month, day), DayPlan.NoClass, rest, true)
                    }
                }
            }
        }

        // ── ② 文字兜底 ──────────────────────────────────────────
        // 区间放假：M月D日（周X）至 [M月]D日（周X）放假…
        RE_RANGE.findAll(text).forEach { m ->
            val m1 = m.groupValues[1].toIntOrNull() ?: return@forEach
            val d1 = m.groupValues[2].toIntOrNull() ?: return@forEach
            val m2 = m.groupValues[3].toIntOrNull() ?: m1
            val d2 = m.groupValues[4].toIntOrNull() ?: return@forEach
            val year = yearFor(m1, pub, pubMonth) ?: return@forEach
            daysBetween(year, m1, d1, m2, d2).forEach { put(it, DayPlan.NoClass, m.groupValues[5], false) }
        }
        // 单日放假 / 补休（正则已把关键词写进捕获组，不会抓到别的日子）
        for (rex in listOf(RE_DAY_HOLIDAY, RE_DAY_XIUXI)) {
            rex.findAll(text).forEach { m ->
                val mm = m.groupValues[1].toIntOrNull() ?: return@forEach
                val dd = m.groupValues[2].toIntOrNull() ?: return@forEach
                val year = yearFor(mm, pub, pubMonth) ?: return@forEach
                put(safeDate(year, mm, dd), DayPlan.NoClass, m.groupValues[3], false)
            }
        }
        // 补课：…（周X）…按照…（周X）…教学计划安排上课
        RE_MAKEUP_PROSE.findAll(text).forEach { m ->
            val mm = m.groupValues[1].toIntOrNull() ?: return@forEach
            val dd = m.groupValues[2].toIntOrNull() ?: return@forEach
            val target = weekdayOf(m.groupValues[4]) ?: return@forEach
            val year = yearFor(mm, pub, pubMonth) ?: return@forEach
            put(safeDate(year, mm, dd), DayPlan.MakeUp(target), m.groupValues[3], false)
        }

        return found.entries
            .map { (date, v) -> TeachingAdjustmentEntry(date, v.plan, v.note, title, url) }
            .sortedBy { it.date }
    }

    /** 取某天的安排（无覆盖时返回 null ⇒ 调用方保持原样）。 */
    fun planOf(entries: List<TeachingAdjustmentEntry>, date: LocalDate): DayPlan? =
        entries.firstOrNull { it.date == date }?.plan

    // ------------------------------------------------------------ 文本抽取

    /**
     * 把 HTML 变成可正则的纯文本。
     *
     * ⚠️ 门户会把一个词拆进不同标签，抽完文本会留下**词内空格**
     * （实测「中秋假 期」「无 教学安排」「（星期日） 放假」）⇒
     * 必须去掉「汉字↔汉字」之间的空格，否则关键词匹配会静默失败。
     * 数字与汉字之间的空格**要保留**（「共 3 天」）。
     */
    internal fun visibleText(html: String): String = html
        .replace(SCRIPT_STYLE, " ")
        .replace(TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .replace(CJK_SPACE, "")
        .replace(Regex("\\s*年\\s*"), "年")
        .replace(Regex("\\s*月\\s*"), "月")
        .replace(Regex("\\s*日\\s*"), "日")
        .trim()

    // ------------------------------------------------------------ 小工具

    /** 发布日期所在年 + 月份回绕（例：12 月发的通知里写 1 月 → 次年）。 */
    private fun yearFor(month: Int, pubYear: Int?, pubMonth: Int?): Int? {
        if (pubYear == null || pubMonth == null) return null
        return if (month < pubMonth) pubYear + 1 else pubYear
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun daysBetween(year: Int, m1: Int, d1: Int, m2: Int, d2: Int): List<LocalDate> {
        val start = safeDate(year, m1, d1) ?: return emptyList()
        val end = safeDate(year, m2, d2) ?: return emptyList()
        if (end.isBefore(start)) return emptyList()
        val out = ArrayList<LocalDate>()
        var cur = start
        while (!cur.isAfter(end)) {
            out += cur
            cur = cur.plusDays(1)
        }
        return out
    }

    private fun weekdayOf(s: String): Int? =
        Regex(WD).find(s)?.value?.last()?.let { WEEKDAY[it] }

    private fun joinUrl(base: String, path: String): String =
        if (path.startsWith("http")) path
        else base.trimEnd('/') + "/" + path.trimStart('.', '/')

    // ------------------------------------------------------------ 常量

    /** 解析中的一天（[fromTable] 用于「表格优先」的合并规则）。 */
    private data class Item(val plan: DayPlan, val note: String, val fromTable: Boolean)

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val TABLE = Regex("(?is)<table.*?</table>")
    private val ROW = Regex("(?is)<tr.*?</tr>")
    private val CELL = Regex("(?is)<t[hd][^>]*>.*?</t[hd]>")
    private val LIST_LINK = Regex("(?is)href=\"([^\"]+\\.htm)\"[^>]*>\\s*([^<]{6,80}?)\\s*<")
    private val CELL_DAY = Regex("^(\\d{1,2})\\s*(.*)$")
    private val MONTH_CTX = Regex("(\\d{4})年(\\d{1,2})月")
    private val PUBLISH = Regex("发布日期[:：]\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    /** 两个汉字之间的空白：门户拆标签留下的，必须去掉。 */
    private val CJK_SPACE = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")

    /** `星期X` / `周X`（`X` 取最后一个字，兼容「星期四」与「周四」）。 */
    private const val WD = "(?:星期|周)[一二三四五六日天]"

    /** 句子内部：止于句号 / 分号 / 引号。 */
    private const val IN = "[^。；\"”]"

    /** 区间放假：`M月D日（周X）至 [M月]D日（周X）放假…` */
    private val RE_RANGE = Regex("(\\d{1,2})月(\\d{1,2})日（$WD）至\\s*(?:(\\d{1,2})月)?(\\d{1,2})日（$WD）(放假$IN{0,20})")

    /** 单日放假（**必须紧跟「放假」**，否则会抓到一整句、误标别的日子）。 */
    private val RE_DAY_HOLIDAY = Regex("(\\d{1,2})月(\\d{1,2})日（$WD）(放假$IN{0,20})")

    /** 单日补休 / 无教学安排。 */
    private val RE_DAY_XIUXI = Regex("(\\d{1,2})月(\\d{1,2})日（$WD）(补休$IN{0,30})")

    /**
     * 补课：`M月D日（周X）…按照…（周X）…教学计划安排上课`。
     *
     * ⚠️ 「按照」后面的星期几**有时带括号有时不带**（正文「（星期四）」、表格「周四」），
     * 所以括号必须可选。
     */
    private val RE_MAKEUP_PROSE = Regex("(\\d{1,2})月(\\d{1,2})日（$WD）($IN{0,60}?)按照$IN{0,30}?（?($WD)）?$IN{0,20}?教学计划安排上课")

    /** 表格单元格里的补课：`上课 按 10月8日（周四）课表上课`。 */
    private val RE_MAKEUP_CELL = Regex("按$IN{0,30}?（?($WD)）?$IN{0,30}?(?:课表|教学计划)")

    private val NOCLASS_WORDS = listOf("放假", "假期", "补休", "无教学安排")

    private val WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )
}
