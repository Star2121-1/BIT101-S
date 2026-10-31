package cn.bit101.android.data.score

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScoreTable] 的契约测试。
 *
 * 表头形状按官方 Web / iOS 两端的键名造（2026-10-31 对照 `BIT101-dev/BIT101`
 * 的 `Score.vue` 与 `BIT101-dev/BIT101-iOS` 的 `ScoreModels.swift`），
 * GPA 公式按学校公布口径固化 —— 这三处任何一处改动都会让测试变红。
 */
class ScoreTableTest {

    /** 按真实表头造一张表；列序刻意做成**非直觉顺序**，逼代码按键名取值。 */
    private fun table(vararg rows: List<String>): String {
        val header = listOf(
            "序号", "开课学期", "课程编号", "课程名称", "成绩", "平均分",
            "学分", "课程性质", "本人成绩在班级中占", "本人成绩在专业中占",
        )
        val all = listOf(header) + rows
        return all.joinToString(",", "[", "]") { row ->
            row.joinToString(",", "[", "]") { "\"$it\"" }
        }
    }

    @Test
    fun 解析_按列名取值_与列序无关() {
        val json = table(
            listOf("1", "2025-2026-2", "MA10001", "高等数学A", "90", "82", "4", "必修", "10%", "25%"),
            listOf("2", "2025-2026-2", "CS20002", "数据结构", "优秀", "85", "3", "必修", "5%", "12%"),
        )
        val t = ScoreTable.parse(JsonParser.parseString(json))!!

        assertEquals(2, t.rows.size)
        val first = t.rows[0]
        assertEquals("高等数学A", first.courseName)
        assertEquals("MA10001", first.courseNumber)
        assertEquals("90", first.scoreText)
        assertEquals(4.0, first.credit!!, 1e-9)
        assertEquals("2025-2026-2", first.term)
        assertEquals("必修", first.courseType)
        assertEquals("10%", first.classRank)
        assertEquals("25%", first.majorRank)
        assertEquals("82", first.averageScoreText)
        assertEquals("数据结构", t.rows[1].courseName)
    }

    @Test
    fun 解析_课程名列不会被课程编号抢走() {
        // 表头里 "课程编号" 排在 "课程名称" 前面 —— 早期实现就是在这里拿到编号当课名的
        val json = table(listOf("1", "2025-2026-2", "09000410", "操作系统", "88", "80", "4", "必修", "", ""))
        val t = ScoreTable.parse(JsonParser.parseString(json))!!
        assertEquals("操作系统", t.rows[0].courseName)
    }

    @Test
    fun gpa_按学校公式换算() {
        // 公式：x<60 ⇒ 0；否则 4 - 3(100-x)²/1600
        assertEquals(4.0, ScoreTable.gpaOf("100")!!, 1e-9)
        assertEquals(3.8125, ScoreTable.gpaOf("90")!!, 1e-9)
        assertEquals(3.25, ScoreTable.gpaOf("80")!!, 1e-9)
        assertEquals(1.0, ScoreTable.gpaOf("60")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("59")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("0")!!, 1e-9)
    }

    @Test
    fun gpa_等级制按对照表() {
        assertEquals(4.0, ScoreTable.gpaOf("优秀")!!, 1e-9)
        assertEquals(3.6, ScoreTable.gpaOf("良好")!!, 1e-9)
        assertEquals(2.8, ScoreTable.gpaOf("中等")!!, 1e-9)
        assertEquals(1.7, ScoreTable.gpaOf("及格")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("不及格")!!, 1e-9)
        // 等级同时给百分制口径（统计均分时用）
        assertEquals(95.0, ScoreTable.scoreOf("优秀")!!, 1e-9)
        assertEquals(65.0, ScoreTable.scoreOf("及格")!!, 1e-9)
    }

    @Test
    fun 认不出的成绩_必须返回null而不是0() {
        // 这些都不该当成 0 分拉低平均 —— 是最容易被忽略的错
        listOf("缓考", "缺考", "—", "", "-", "免修").forEach {
            assertNull("「$it」不该被当成分数", ScoreTable.scoreOf(it))
            assertNull("「$it」不该被当成绩点", ScoreTable.gpaOf(it))
        }
    }

    @Test
    fun 统计_加权平均分与加权绩点() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "课程B", "80", "75", "3", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(2, s.courseCount)
        assertEquals(7.0, s.totalCredit, 1e-9)
        // (90×4 + 80×3) / 7 = 600/7
        assertEquals(600.0 / 7.0, s.weightedScore!!, 1e-9)
        // (3.8125×4 + 3.25×3) / 7 = 25/7
        assertEquals(25.0 / 7.0, s.weightedGpa!!, 1e-9)
        assertEquals(0, s.skipped)
    }

    @Test
    fun 统计_同课程编号只取最高分() {
        // 重修：同编号两条，低的 70 不该参与统计
        val json = table(
            listOf("1", "2024-2025-1", "C3", "大学物理", "70", "70", "3", "必修", "", ""),
            listOf("2", "2025-2026-1", "C3", "大学物理", "85", "75", "3", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals("重修只算一门", 1, s.courseCount)
        assertEquals(3.0, s.totalCredit, 1e-9)
        assertEquals(85.0, s.weightedScore!!, 1e-9)
    }

    @Test
    fun 统计_跳过缓考与零学分() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "课程B", "缓考", "75", "2", "必修", "", ""),
            listOf("3", "2025-2026-2", "C3", "形势与政策", "95", "90", "0", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(1, s.courseCount)
        assertEquals(4.0, s.totalCredit, 1e-9)
        assertEquals(90.0, s.weightedScore!!, 1e-9)
        assertEquals("缓考 + 0 学分共两条被跳过", 2, s.skipped)
    }

    /**
     * 0 分 / 不及格按官方口径**不进入**加权绩点。
     *
     * 口径来源：线上 bundle `Score-92da5cfc.js` 的 `N()` 里 `if(B(成绩)==0) continue`，
     * 而 `B()` 对「不及格」返回 0。若我们照着「数学上更正确」的算进去，
     * 用户拿 App 的绩点跟网页版一对就对不上。
     */
    @Test
    fun 统计_不及格不计入绩点但要说出来() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "课程B", "不及格", "70", "3", "必修", "", ""),
            listOf("3", "2025-2026-2", "C3", "课程C", "0", "60", "2", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals("只有课程A进绩点", 1, s.courseCount)
        assertEquals(4.0, s.totalCredit, 1e-9)
        assertEquals(90.0, s.weightedScore!!, 1e-9)
        assertEquals("不及格 + 0 分两门被排除", 2, s.zeroSkipped)
        assertEquals("排除不是「算不出来」，不该混进 skipped", 0, s.skipped)
    }

    /** 原考不及格、补考及格 ⇒ 取最高分那条，算作有效课程。 */
    @Test
    fun 统计_补考及格按最高分计入且不算排除() {
        val json = table(
            listOf("1", "2025-2026-1", "D4", "线性代数", "40", "70", "3", "必修", "", ""),
            listOf("2", "2025-2026-2", "D4", "线性代数", "60", "70", "3", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(1, s.courseCount)
        assertEquals(60.0, s.weightedScore!!, 1e-9)
        assertEquals("最高分那条有效 ⇒ 0 分排除数为 0", 0, s.zeroSkipped)
    }

    @Test
    fun 解析_脏数据一律返回null() {
        assertNull("null", ScoreTable.parse(null))
        assertNull("不是数组", ScoreTable.parse(JsonParser.parseString("{\"a\":1}")))
        assertNull("没有表头", ScoreTable.parse(JsonParser.parseString("[[\"1\",\"2\"],[\"3\",\"4\"]]")))
    }

    /**
     * 空数组是**正常状态**，不是格式错。
     *
     * 2026-10-31 真机实测：本学期还没出分时认证主机就回 `[]`（连表头都不给）。
     * 早先把 `[]` 当解析失败，页面会显示「成绩表格式变了」—— 一个正常状态被说成
     * 「坏了」，用户当然以为是我们崩了。
     */
    @Test
    fun 解析_空数组是空表而不是失败() {
        val empty = ScoreTable.parse(JsonParser.parseString("[]"))
        assertTrue("空数组应解析成空表", empty != null)
        assertTrue("空数组不该有行", empty!!.rows.isEmpty())
        assertEquals("空表也不该有绩点", null, ScoreTable.summarize(empty.rows).weightedGpa)

        // 有表头但一行数据都没有，同样算「还没成绩」
        val headerOnly = ScoreTable.parse(
            JsonParser.parseString("[[\"课程名称\",\"成绩\",\"学分\"]]")
        )
        assertTrue("只有表头应解析成空表", headerOnly != null && headerOnly.rows.isEmpty())
    }

    @Test
    fun 学期列表_新的在前() {
        val json = table(
            listOf("1", "2024-2025-1", "A1", "课A", "90", "", "4", "必修", "", ""),
            listOf("2", "2026-2027-1", "B2", "课B", "80", "", "3", "选修", "", ""),
            listOf("3", "2025-2026-2", "C3", "课C", "70", "", "2", "必修", "", ""),
        )
        val t = ScoreTable.parse(JsonParser.parseString(json))!!
        assertEquals(listOf("2026-2027-1", "2025-2026-2", "2024-2025-1"), t.terms)
        assertEquals(listOf("必修", "选修"), t.courseTypes)
        assertTrue(t.headers.contains("课程名称"))
    }
}
