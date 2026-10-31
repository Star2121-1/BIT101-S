package cn.bit101.android.data.score

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScoreCsv] 的契约测试。
 *
 * 盯两件事：**列与官方一致**（用户会拿两份表对比）、**转义不出错**
 * （官方那版不转义，课名带逗号就整行错列，且打开才发现）。
 */
class ScoreCsvTest {

    /** 按官方 8 列造表；额外两列用来验证「取值走访问器而不是精确列名」。 */
    private fun parse(vararg rows: List<String>): List<ScoreTable.Row> {
        val header = listOf(
            "开课学期", "课程编号", "课程名称", "成绩", "平均分",
            "学分", "课程性质", "本人成绩在班级中占", "本人成绩在专业中占",
        )
        val json = (listOf(header) + rows).joinToString(",", "[", "]") { row ->
            row.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
        }
        return ScoreTable.parse(JsonParser.parseString(json))!!.rows
    }

    private val oneRow = {
        parse(
            listOf("2025-2026-2", "MA10001", "高等数学A", "90", "82", "4", "必修", "10%", "25%"),
        )
    }

    @Test
    fun 表头与官方网页版列序一致() {
        val lines = ScoreCsv.of(oneRow()).split("\r\n")

        assertEquals(
            "\uFEFF课程名称,成绩,平均分,学分,本人成绩在班级中占,本人成绩在专业中占,开课学期,课程性质",
            lines[0],
        )
    }

    @Test
    fun 取值正确且按官方列序排列() {
        val line = ScoreCsv.of(oneRow()).split("\r\n")[1]

        assertEquals("高等数学A,90,82,4,10%,25%,2025-2026-2,必修", line)
    }

    /** 课名带逗号时必须加引号，否则整行错列（官方那版就是错的）。 */
    @Test
    fun 含逗号的字段要加引号() {
        val rows = parse(
            listOf("2025-2026-2", "MA1", "高等数学（上）,习题课", "90", "82", "4", "必修", "10%", "25%"),
        )

        val line = ScoreCsv.of(rows).split("\r\n")[1]

        assertTrue("含逗号的课名必须被引号包住，实际：$line", line.startsWith("\"高等数学（上）,习题课\","))
    }

    @Test
    fun 含双引号的字段要把引号写成两个() {
        val rows = parse(
            listOf("2025-2026-2", "MA1", "讲座\"名师\"系列", "90", "82", "4", "必修", "10%", "25%"),
        )

        val line = ScoreCsv.of(rows).split("\r\n")[1]

        assertTrue("双引号要转义成两个，实际：$line", line.startsWith("\"讲座\"\"名师\"\"系列\","))
    }

    /**
     * 表里没有「平均分 / 班级占比 / 专业占比」这三列时，导出空字段。
     *
     * ⚠️ 官方网页版在这种情况下会在 CSV 里写出字面量 `undefined`（模板字符串行为），
     * 那是缺陷。我们导空串 —— Excel 里是空白单元格，不是一行看不明所以的 "undefined"。
     */
    @Test
    fun 缺列时导出空字段而不是undefined() {
        val header = listOf("开课学期", "课程名称", "成绩", "学分", "课程性质")
        val json = listOf(
            header,
            listOf("2025-2026-2", "大学物理", "85", "3", "必修"),
        ).joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") { "\"$it\"" } }
        val rows = ScoreTable.parse(JsonParser.parseString(json))!!.rows

        val line = ScoreCsv.of(rows).split("\r\n")[1]

        assertEquals("大学物理,85,,3,,,2025-2026-2,必修", line)
        assertTrue("不该出现 undefined：$line", !line.contains("undefined"))
    }

    @Test
    fun 空列表只导出表头() {
        val lines = ScoreCsv.of(emptyList()).split("\r\n")

        assertEquals("只有表头一行 + 行尾换行", 2, lines.size)
        assertEquals("", lines[1])
    }

    @Test
    fun 每行以CRLF结尾() {
        val text = ScoreCsv.of(oneRow())

        assertTrue("末行也要有换行，Excel 才不会吃掉最后一行", text.endsWith("\r\n"))
        assertTrue("必须是 BOM 开头，否则 Excel 中文乱码", text.startsWith("\uFEFF"))
    }
}
