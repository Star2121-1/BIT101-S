package cn.bit101.android.data.score

import cn.bit101.android.data.score.ScoreLogic.ScoreEntry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩表解析与差分的单测。
 *
 * ⚠️ `/scores` 的真实响应形状**未经验证**（App 此前从未调用过它），
 * 所以这些测试锁定的是**防御行为**：
 * - 认得出的形状 → 正确解析
 * - 认不出的形状 → 返回空，**绝不猜**
 * - 文案里**绝不出现分数**（用户定的隐私边界）
 *
 * 等真机抓到真实响应后，把实际形状补成一条测试并收紧实现。
 */
class ScoreLogicTest {

    // ------------------------------------------------------------ 解析

    @Test
    fun `标准二维表带表头`() {
        val json = """
            [["学期","课程","成绩","学分"],
             ["2025-2026-1","高等数学","92","5"],
             ["2025-2026-1","操作系统","88","4"]]
        """.trimIndent()

        val entries = ScoreLogic.parseTable(JsonParser.parseString(json))

        assertEquals(2, entries.size)
        assertEquals("高等数学", entries[0].course)
        assertEquals("92", entries[0].score)
        assertEquals("2025-2026-1", entries[0].term)
        assertEquals("高等数学|2025-2026-1", entries[0].key)
    }

    /** 表头列的顺序可能不同 —— 列位置必须由表头决定，不能写死下标。 */
    @Test
    fun `列顺序不同也能解析`() {
        val json = """
            [["成绩","课程名","学年"],
             ["92","高等数学","2025-2026-1"]]
        """.trimIndent()

        val entries = ScoreLogic.parseTable(JsonParser.parseString(json))

        assertEquals(1, entries.size)
        assertEquals("高等数学", entries[0].course)
        assertEquals("92", entries[0].score)
        assertEquals("2025-2026-1", entries[0].term)
    }

    /** 模型注释与字段类型自相矛盾 —— 也兼容「每行一个字符串」的形状。 */
    @Test
    fun `每行是字符串时按空白切列`() {
        val json = """
            ["学期 课程 成绩",
             "2025-2026-1  高等数学  92"]
        """.trimIndent()

        val entries = ScoreLogic.parseTable(JsonParser.parseString(json))

        assertEquals(1, entries.size)
        assertEquals("高等数学", entries[0].course)
        assertEquals("92", entries[0].score)
    }

    @Test
    fun `没有表头就不猜`() {
        // 没有「课程/成绩/学期」字样的表 → 列含义未知，返回空
        val json = """[["a","b","c"],["1","2","3"]]"""
        assertTrue(ScoreLogic.parseTable(JsonParser.parseString(json)).isEmpty())
    }

    @Test
    fun `空表与非数组都返回空`() {
        assertTrue(ScoreLogic.parseTable(null).isEmpty())
        assertTrue(ScoreLogic.parseTable(JsonParser.parseString("[]")).isEmpty())
        assertTrue(ScoreLogic.parseTable(JsonParser.parseString("\"text\"")).isEmpty())
    }

    @Test
    fun `课程名为空的行跳过`() {
        val json = """
            [["课程","成绩"],
             ["","92"],
             ["操作系统","88"]]
        """.trimIndent()

        val entries = ScoreLogic.parseTable(JsonParser.parseString(json))

        assertEquals(1, entries.size)
        assertEquals("操作系统", entries[0].course)
    }

    // ------------------------------------------------------------ 差分

    private fun entry(course: String, term: String = "2025-2026-1") =
        ScoreEntry(course = course, score = "88", term = term)

    /** 首次运行只建基线：否则第一次打开就把历史成绩全报一遍。 */
    @Test
    fun `首次运行建立基线不提醒`() {
        val list = ScoreLogic.diff(snapshot = null, entries = listOf(entry("高等数学")))

        assertTrue(list.isEmpty())
    }

    @Test
    fun `新课程报出来`() {
        val snapshot = mapOf("高等数学|2025-2026-1" to "92")

        val list = ScoreLogic.diff(snapshot, listOf(entry("高等数学"), entry("操作系统")))

        assertEquals(listOf("操作系统"), list.map { it.course })
    }

    /** 改分（键已存在、分数变了）不提醒 —— 那需要展示分数差才有意义。 */
    @Test
    fun `改分不重新提醒`() {
        val snapshot = mapOf("高等数学|2025-2026-1" to "85")

        val list = ScoreLogic.diff(snapshot, listOf(entry("高等数学")))

        assertTrue(list.isEmpty())
    }

    @Test
    fun `快照序列化回读一致`() {
        val entries = listOf(entry("高等数学"), entry("操作系统", term = "2025-2026-2"))
        val snapshot = ScoreLogic.snapshotOf(entries)

        assertEquals(setOf("高等数学|2025-2026-1", "操作系统|2025-2026-2"), snapshot.keys)
        assertEquals("88", snapshot["操作系统|2025-2026-2"])
    }

    // ------------------------------------------------------------ 文案（隐私边界）

    @Test
    fun `单门课文案`() {
        assertEquals(
            "「操作系统」成绩已发布",
            ScoreLogic.summaryText(listOf(entry("操作系统"))),
        )
    }

    @Test
    fun `多门课文案最多列三个`() {
        val names = listOf("操作系统", "数据结构", "大学物理", "概率论")
        val text = ScoreLogic.summaryText(names.map { entry(it) })

        assertEquals("操作系统、数据结构、大学物理 等 4 门课成绩已发布", text)
    }

    /** ⚠️ 隐私边界：文案里绝不出现分数原文。 */
    @Test
    fun `文案绝不包含分数`() {
        val entries = listOf(
            ScoreEntry(course = "操作系统", score = "88", term = null),
            ScoreEntry(course = "数据结构", score = "95", term = null),
        )

        val text = ScoreLogic.summaryText(entries)

        assertFalse(text.contains("88"))
        assertFalse(text.contains("95"))
    }

    @Test
    fun `空列表文案为空`() {
        assertEquals("", ScoreLogic.summaryText(emptyList()))
    }
}
