package cn.bit101.android.data.score

import com.google.gson.JsonArray
import com.google.gson.JsonElement

/**
 * 成绩表的**纯逻辑**：解析 + 差分 + 文案。
 *
 * ## 为什么这么防御
 *
 * `/scores` 的真实响应形状**未经验证**（App 此前从未调用过它，模型注释只说
 * 「二维表、第一行表头」，但字段却声明成 `ArrayList<String>`，自相矛盾）。
 * 在拿到真实响应之前，这里的原则是：
 *
 * 1. **绝不猜列含义** —— 找不到表头/课程列/成绩列就返回空，宁可不提醒
 * 2. 解析失败也返回空 —— 成绩提醒是「尽力而为」，崩掉或误报都不可接受
 * 3. **文案里绝不出现分数**（用户明确要求的隐私边界，见 `docs/codebase-survey.md`）
 *
 * 等真机抓到真实响应后，把确认的形状补进测试并收紧实现。
 */
object ScoreLogic {

    /** 一门课的成绩。 */
    data class ScoreEntry(
        /** 课程名（快照的去重键之一）。 */
        val course: String,
        /** 分数原文；解析不出列时为 null。 */
        val score: String?,
        /** 学期（如 `2025-2026-2`）；表头里没有该列时为 null。 */
        val term: String?,
    ) {
        /** 快照去重键：课程 + 学期（分数**不在键里** —— 改分不重新提醒，避免刷屏）。 */
        val key: String get() = "${course.trim()}|${term?.trim().orEmpty()}"
    }

    /** 按优先级找列：先试更具体的词，再退回宽泛的词；带排除词避开「课程**编号**」这类。 */
    private val COURSE_KEYWORDS = listOf("课程名", "课程")
    private val COURSE_EXCLUDE = listOf("编号", "代码", "号")
    private val SCORE_KEYWORDS = listOf("成绩", "分数", "总评", "得分")
    private val TERM_KEYWORDS = listOf("学期", "学年")

    /**
     * 解析成绩表。
     *
     * 兼容两种行形状（真实形状未知）：
     * - 每行是数组：`[["课程","成绩",…], ["高等数学","92",…], …]`
     * - 每行是字符串：`["高等数学 92 4 …", …]`（按空白切列）
     *   ⚠️ 局限：含空格的课程名（如英文课名）会被切开 —— 但**错误方向是安全的**：
     *   列错位多半导致表头对不上（返回空、不提醒），而不是把资料当作业
     *
     * @return 解析出的课程成绩；无法确定表头/列时返回**空列表**
     */
    fun parseTable(root: JsonElement?): List<ScoreEntry> {
        val rows = rowsOf(root)
        if (rows.isEmpty()) return emptyList()

        // 表头行：第一个包含任一关键词的行。找不到就不猜 —— 返回空。
        val headerIndex = rows.indexOfFirst { row -> row.any { cell -> isHeaderCell(cell) } }
        if (headerIndex == -1) return emptyList()
        val header = rows[headerIndex]

        val courseCol = findColumn(header, COURSE_KEYWORDS, COURSE_EXCLUDE)
        if (courseCol < 0) return emptyList()
        val scoreCol = findColumn(header, SCORE_KEYWORDS)
        val termCol = findColumn(header, TERM_KEYWORDS)

        return rows.drop(headerIndex + 1).mapNotNull { row ->
            val course = row.getOrNull(courseCol)?.trim().orEmpty()
            if (course.isBlank()) return@mapNotNull null
            ScoreEntry(
                course = course,
                score = scoreCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.ifBlank { null } },
                term = termCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.ifBlank { null } },
            )
        }
    }

    /**
     * 按**关键词优先级**找列。
     *
     * ⚠️ 真实表头（2026-09-26 实测）是
     * `['序号','开课学期','课程编号','课程名称','成绩','成绩标识',...]` ——
     * 直接 `contains("课程")` 取第一个会命中**课程编号**（拿到 `09000410` 当课名）。
     * 所以先试「课程名」，再退回「课程」并排除编号/代码。
     */
    private fun findColumn(header: List<String>, keywords: List<String>, exclude: List<String> = emptyList()): Int {
        for (keyword in keywords) {
            val index = header.indexOfFirst { cell ->
                cell.contains(keyword) && exclude.none { cell.contains(it) }
            }
            if (index >= 0) return index
        }
        return -1
    }

    private fun rowsOf(root: JsonElement?): List<List<String>> {
        val arr = root as? JsonArray ?: return emptyList()
        if (arr.size() == 0) return emptyList()
        return arr.map { row ->
            when {
                row.isJsonArray -> row.asJsonArray.mapNotNull { cell ->
                    (cell as? com.google.gson.JsonPrimitive)?.takeIf { it.isString || it.isNumber }?.asString
                }
                (row as? com.google.gson.JsonPrimitive)?.isString == true ->
                    row.asString.trim().split(Regex("\\s+")).map { it.trim() }
                else -> emptyList()
            }
        }
    }

    private fun isHeaderCell(cell: String): Boolean =
        (COURSE_KEYWORDS + SCORE_KEYWORDS + TERM_KEYWORDS).any { cell.contains(it) }

    // ------------------------------------------------------------ 差分

    /**
     * 与快照差分，找出**新出分**的课程。
     *
     * - 首次运行（[snapshot] 为 null）→ **建立基线但不提醒**：否则第一次打开
     *   就会把历史成绩全报一遍，等于轰炸
     * - 只认「快照里没有的键」；改分（键已存在）不提醒 —— 改分通知要展示分数差
     *   才有意义，那会突破「不显示分数」的隐私边界
     *
     * @return 需要提醒的新成绩；无新成绩或首次建基线时返回空
     */
    fun diff(snapshot: Map<String, String>?, entries: List<ScoreEntry>): List<ScoreEntry> {
        if (entries.isEmpty()) return emptyList()
        if (snapshot == null) return emptyList() // 首次运行：只建基线
        val known = snapshot.keys
        return entries.filter { it.key !in known }
    }

    /** 快照序列化：`{"课程|学期": "92"}`（分数只存本地，不上通知）。 */
    fun snapshotOf(entries: List<ScoreEntry>): Map<String, String> =
        entries.associate { it.key to it.score.orEmpty() }

    // ------------------------------------------------------------ 文案（隐私边界）

    /**
     * 通知正文。⚠️ **绝不包含分数** —— 这是用户明确的隐私决定。
     *
     * - 一门：`「操作系统」成绩已发布`
     * - 多门：`操作系统、数据结构 等 3 门课成绩已发布`（最多列 3 个课名）
     */
    fun summaryText(new: List<ScoreEntry>): String {
        val names = new.map { it.course }.filter { it.isNotBlank() }.distinct()
        return when (names.size) {
            0 -> ""
            1 -> "「${names[0]}」成绩已发布"
            else -> "${names.take(3).joinToString("、")} 等 ${names.size} 门课成绩已发布"
        }
    }
}
