package cn.bit101.android.data.score

/**
 * 成绩表导出 CSV（纯字符串拼装，不碰文件与 IO —— 落盘在界面层做）。
 *
 * ## 列与顺序照抄官方网页版
 *
 * `课程名称, 成绩, 平均分, 学分, 本人成绩在班级中占, 本人成绩在专业中占, 开课学期, 课程性质`
 *
 * 抄它的理由不是「它权威」，而是**用户会拿两份表对着看** —— 列一致才好比对。
 * 但 [escape] 那条是**刻意不跟它**的，见注释。
 *
 * ## 取值走 `Row` 的语义访问器，不走列名精确匹配
 *
 * 官方那 8 个列名是按**当时的表头原文**写的。教务列名会漂移（「开课学期」在某些
 * 学期是「学期」），精确匹配一旦失配就静默导出空列。这里统一用
 * [ScoreTable.Row] 的访问器（`courseName` / `term` / `classRank` …），
 * 它们本来就是「按关键词优先级找列」的实现，界面显示什么就导出什么。
 */
object ScoreCsv {

    /** Excel 打开 UTF-8 CSV 要靠 BOM 猜编码，否则中文全是乱码。 */
    private const val BOM = "\uFEFF"

    private const val CRLF = "\r\n"

    private const val HEADER = "课程名称,成绩,平均分,学分,本人成绩在班级中占,本人成绩在专业中占,开课学期,课程性质"

    /**
     * @param rows **已经筛选过**的行（用户看到什么就导什么，与列表一致）。
     * @return 带 BOM、CRLF 换行的 CSV 文本。
     */
    fun of(rows: List<ScoreTable.Row>): String = buildString {
        append(BOM)
        append(HEADER)
        append(CRLF)
        rows.forEach { row ->
            append(
                listOf(
                    row.courseName,
                    row.scoreText,
                    row.averageScoreText,
                    row.creditText,
                    row.classRank,
                    row.majorRank,
                    row.term,
                    row.courseType,
                ).joinToString(",") { escape(it) },
            )
            append(CRLF)
        }
    }

    /**
     * CSV 转义（RFC 4180）。
     *
     * ⚠️ **官方网页版没做这一步** —— 它直接 `${i.课程名称},${i.成绩},…` 拼字符串。
     * 于是课名里只要有一个逗号（「高等数学（上）,习题课」这种）整份表就错列，
     * 而且是静默错、打开才发现。这里含 `,` / `"` / 换行 时整体加双引号，
     * 内部的双引号写成两个。
     */
    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
