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
    fun gpa_按学校公式换算且逐课舍入到一位小数() {
        // 教〔2022〕16号：x<60 ⇒ 0；否则 4 - 3(100-x)²/1600，**结果四舍五入到 0.1**
        assertEquals(4.0, ScoreTable.gpaOf("100")!!, 1e-9)
        // 3.8125 → 3.8
        assertEquals(3.8, ScoreTable.gpaOf("90")!!, 1e-9)
        // 3.578125 → 3.6
        assertEquals(3.6, ScoreTable.gpaOf("85")!!, 1e-9)
        assertEquals(1.0, ScoreTable.gpaOf("60")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("59")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("0")!!, 1e-9)
    }

    /**
     * 80 分是 **唯一的舍入陷阱**：`3.25` 正好落在 `.x5` 半值上，
     * 而 `kotlin.math.round` 是银行家舍入（ties-to-even）会得到 3.2。
     * 学校要求四舍五入 ⇒ 必须是 **3.3**。这条测试就是钉这个的。
     */
    @Test
    fun gpa_八十分必须进位到三点三而不是三点二() {
        assertEquals(3.3, ScoreTable.gpaOf("80")!!, 1e-9)
    }

    @Test
    fun gpa_等级制按对照表() {
        assertEquals(4.0, ScoreTable.gpaOf("优秀")!!, 1e-9)
        assertEquals(3.6, ScoreTable.gpaOf("良好")!!, 1e-9)
        assertEquals(2.8, ScoreTable.gpaOf("中等")!!, 1e-9)
        assertEquals(1.7, ScoreTable.gpaOf("及格")!!, 1e-9)
        assertEquals(0.0, ScoreTable.gpaOf("不及格")!!, 1e-9)
        // 等级同时给百分制口径（统计均分时用；95/85/75/65 是 74号文第五条的换算表）
        assertEquals(95.0, ScoreTable.scoreOf("优秀")!!, 1e-9)
        assertEquals(65.0, ScoreTable.scoreOf("及格")!!, 1e-9)
    }

    @Test
    fun 认不出的成绩_必须返回null而不是0() {
        // 这些都不该当成 0 分拉低平均 —— 是最容易被忽略的错
        listOf("缓考", "—", "", "-", "免修").forEach {
            assertNull("「$it」不该被当成分数", ScoreTable.scoreOf(it))
            assertNull("「$it」不该被当成绩点", ScoreTable.gpaOf(it))
        }
    }

    /**
     * 缺考/旷考/作弊**不是「认不出」，而是真的 0 分**。
     *
     * 依据 教〔2022〕16号 第二十三条：「凡擅自缺考（即旷考）的，该课程成绩按『0』分记」。
     * 早先把它和「缓考」一起跳过 ⇒ 挂科的人绩点反而更高，方向完全反了。
     */
    @Test
    fun 缺考旷考作弊按零分计入而不是跳过() {
        listOf("缺考", "旷考", "作弊", "无效").forEach {
            assertEquals("「$it」应记 0 分", 0.0, ScoreTable.scoreOf(it)!!, 1e-9)
            assertEquals("「$it」应记 0 绩点", 0.0, ScoreTable.gpaOf(it)!!, 1e-9)
        }
        // 缓考必须仍然是 null（批准过延期，成绩还没出）
        assertNull("缓考不该被当 0 分", ScoreTable.scoreOf("缓考"))
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
        assertEquals("两门都及格 ⇒ 已获学分等于总学分", 7.0, s.earnedCredit, 1e-9)
        // (90×4 + 80×3) / 7 = 600/7
        assertEquals(600.0 / 7.0, s.weightedScore!!, 1e-9)
        // 逐课舍入后：(3.8×4 + 3.3×3) / 7 = 25.1/7
        assertEquals(25.1 / 7.0, s.weightedGpa!!, 1e-9)
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
        assertEquals("重修只算一遍学分", 3.0, s.earnedCredit, 1e-9)
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
        assertEquals("缓考 + 0 学分共两门被跳过", 2, s.skipped)
    }

    /**
     * **核心口径测试：不及格必须计入分母。**
     *
     * 教〔2022〕16号：「若 X 小于 60 则课程绩点记为 0」—— 记 0 后照常参与加权，
     * 不是把整门课踢出去。网页版在 `N()` 里 `if(B(成绩)==0) continue` 把所有
     * 不及格整门剔除，会让绩点虚高（85分/3学分 + 45分/4学分：按文件 1.5429，
     * 网页版 3.5781）。
     */
    @Test
    fun 统计_不及格计入分母而不是剔除() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "课程B", "不及格", "70", "3", "必修", "", ""),
            listOf("3", "2025-2026-2", "C3", "课程C", "0", "60", "2", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals("三门全进", 3, s.courseCount)
        assertEquals("不及格的学分也要进分母", 9.0, s.totalCredit, 1e-9)
        assertEquals("已获学分只有及格那门", 4.0, s.earnedCredit, 1e-9)
        // (90×4 + 0×3 + 0×2) / 9
        assertEquals(40.0, s.weightedScore!!, 1e-9)
        // (3.8×4 + 0 + 0) / 9
        assertEquals(15.2 / 9.0, s.weightedGpa!!, 1e-9)
        assertEquals("不及格不是「算不出来」，不该进 skipped", 0, s.skipped)
        assertEquals("必修不及格不在校公选例外里", 0, s.electiveFailed)
    }

    /**
     * 学校文件里唯一一条「不及格也可以不计入」的例外：**校公选课**。
     *
     * 这条走的是「课程性质」列 —— 属**防御性**路径：实测 `课程性质`（`KCXZDM`）只有
     * 必修/选修，正常情况下这个值不会出现在这一列。留着是因为成绩表的列含义
     * 会随教务改版漂移，两列都认比只认一列稳。真正该命中的是下面那条「课程种类」用例。
     */
    @Test
    fun 统计_校公选课不及格不计入() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "艺术鉴赏", "不及格", "70", "2", "校公选课", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(1, s.courseCount)
        assertEquals(4.0, s.totalCredit, 1e-9)
        assertEquals(90.0, s.weightedScore!!, 1e-9)
        assertEquals("校公选课不及格单独报数", 1, s.electiveFailed)
        assertEquals("它不算「没有可用成绩」", 0, s.skipped)
    }

    /**
     * 「校公选课」这个值真实落在**课程类别**（`KCLBDM`）上，成绩表里对应「课程种类」列。
     *
     * ⚠️ 这条用例只证明**分支能被命中**，不证明真机上会命中 ——
     * 成绩接口当前回空表，`课程种类` 列的实际取值**尚未实测**。
     * 若实测发现它不填「校公选课」，这个例外在 Android 侧就等于未生效
     * （不会错算，只是回到「所有课程都算」的默认口径）。别在报告里写成「已实现」。
     */
    @Test
    fun 统计_课程种类列写校公选课时也能识别() {
        val header = listOf("课程编号", "课程名称", "成绩", "学分", "课程性质", "课程种类")
        val json = listOf(
            header,
            listOf("A1", "课程A", "90", "4", "必修", "专业课"),
            listOf("B2", "艺术鉴赏", "不及格", "2", "选修", "校公选课"),
        ).joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") { "\"$it\"" } }

        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(1, s.electiveFailed)
        assertEquals(4.0, s.totalCredit, 1e-9)
    }

    /**
     * 「校公选」必须**精确匹配**。用 `contains("公选")` 会把「专业选修课」之类
     * 一起剔掉 —— 那是虚高绩点，错误方向不可接受。
     */
    @Test
    fun 统计_专业选修不及格照算不算校公选() {
        val json = table(
            listOf("1", "2025-2026-2", "A1", "课程A", "90", "80", "4", "必修", "", ""),
            listOf("2", "2025-2026-2", "B2", "数据结构", "不及格", "70", "3", "专业选修课", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals("专选不及格要计入", 2, s.courseCount)
        assertEquals(7.0, s.totalCredit, 1e-9)
        assertEquals(0, s.electiveFailed)
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
        assertEquals(0, s.electiveFailed)
    }

    /** 同一门课「缓考一条 + 及格一条」⇒ 这门课算进去了，就不该再报「没计入」。 */
    @Test
    fun 统计_同一门课有成绩就不再报没计入() {
        val json = table(
            listOf("1", "2025-2026-1", "E5", "概率论", "缓考", "70", "3", "必修", "", ""),
            listOf("2", "2025-2026-2", "E5", "概率论", "88", "70", "3", "必修", "", ""),
        )
        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals(1, s.courseCount)
        assertEquals("这门课有成绩 ⇒ 不该报「没计入」", 0, s.skipped)
    }

    /** 没有课程编号列时退回用**课程名**去重 —— 否则一次重修就被算成两门课。 */
    @Test
    fun 统计_没有课程编号时按课程名去重() {
        val header = listOf("开课学期", "课程名称", "成绩", "学分", "课程性质")
        val json = listOf(
            header,
            listOf("2024-2025-1", "大学物理", "70", "3", "必修"),
            listOf("2025-2026-1", "大学物理", "85", "3", "必修"),
        ).joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") { "\"$it\"" } }

        val s = ScoreTable.summarize(ScoreTable.parse(JsonParser.parseString(json))!!.rows)

        assertEquals("同一门课只算一门", 1, s.courseCount)
        assertEquals(3.0, s.totalCredit, 1e-9)
        assertEquals(85.0, s.weightedScore!!, 1e-9)
    }

    /**
     * 上游会把 `detail=false` 与 `detail=true` 两种响应拼接，中间可能夹第二个表头。
     * 那一行必须**跳掉** —— 否则会变成列表里一门叫「课程名称」的假课，
     * 还会因为成绩是「成绩」两个字而多报一条「没计入」。
     */
    @Test
    fun 解析_重复表头行被跳过() {
        val header = listOf("开课学期", "课程编号", "课程名称", "成绩", "学分", "课程性质")
        val json = listOf(
            header,
            listOf("2025-2026-2", "A1", "课程A", "90", "4", "必修"),
            header,
            listOf("2025-2026-2", "B2", "课程B", "80", "3", "必修"),
        ).joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") { "\"$it\"" } }

        val t = ScoreTable.parse(JsonParser.parseString(json))!!

        assertEquals("假课程不该出现", 2, t.rows.size)
        assertEquals(listOf("课程A", "课程B"), t.rows.map { it.courseName })
    }

    /**
     * **真实 16 列表头**（2026-09-26 16:31 真机响应原样，取自 `ScoreLogicTest`）。
     *
     * 线上 Web 只用 8 列，而真实表是 16 列，里面「成绩」和「成绩标识」并列、
     * 「课程属性」和「课程性质」并列。这条用例锁死列名映射不漂移 ——
     * 一旦哪天 `SCORE_KEYS` 或 `COURSE_KEYS` 改错，成绩会读成「正常考试」这种文本。
     *
     * 第 16 列「操作栏」这段 fixture 里不存在，正好也验证了「行比表头短要补空」。
     */
    @Test
    fun 解析_真实十六列表头映射不漂移() {
        val header = listOf(
            "序号", "开课学期", "课程编号", "课程名称", "成绩", "成绩标识", "学分", "总学时",
            "考试性质", "考核方式", "课程属性", "课程性质", "课程归属", "课程种类", "是否第一次考试",
        )
        val json = listOf(
            header,
            listOf(
                "1", "2026-2027-1", "1000123001", "操作系统", "87", "正常考试", "3.5", "56",
                "考试", "闭卷", "学科基础课", "必修", "计算机学院", "专业课", "是",
            ),
        ).joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") { "\"$it\"" } }

        val r = ScoreTable.parse(JsonParser.parseString(json))!!.rows.single()

        assertEquals("成绩必须取「成绩」列，不能取到「成绩标识」", "87", r.scoreText)
        assertEquals("操作系统", r.courseName)
        assertEquals("课程编号不能抢走课程名", "1000123001", r.courseNumber)
        assertEquals(3.5, r.credit!!, 1e-9)
        assertEquals("必修", r.courseType)
        assertEquals("专业课", r.courseCategory)
        assertEquals(87.0, ScoreTable.scoreOf(r.scoreText)!!, 1e-9)
        // 87 → 4-3(13)²/1600 = 3.6831 → 3.7
        assertEquals(3.7, ScoreTable.gpaOf(r.scoreText)!!, 1e-9)
    }

    /**
     * 去重是**跨学期**的（官方口径是「在读期间所有课程」）。
     *
     * 若先按学期筛再去重，用户切到挂科那个学期时挂科会被当成最终成绩再算一次。
     * 所以 `Table.bestRows` 必须在全量上算好。
     */
    @Test
    fun 全量去重_跨学期只留最高分那条() {
        val json = table(
            listOf("1", "2024-2025-1", "F6", "大学英语", "50", "70", "3", "必修", "", ""),
            listOf("2", "2025-2026-1", "F6", "大学英语", "85", "70", "3", "必修", "", ""),
            listOf("3", "2025-2026-1", "G7", "体育", "90", "80", "1", "必修", "", ""),
        )
        val t = ScoreTable.parse(JsonParser.parseString(json))!!

        assertEquals("列表仍然显示全部 3 行", 3, t.rows.size)
        val best = t.bestRows
        assertEquals("去重后 2 门", 2, best.size)
        assertEquals(85.0, best.first { it.courseNumber == "F6" }.scoreText.toDouble(), 1e-9)
        // 只筛挂科那个学期 ⇒ 该课在筛出来的子集里不是最高分那条，统计不含它
        val oldTerm = ScoreTable.summarize(best.filter { it.term == "2024-2025-1" })
        assertEquals("旧学期视图里这门课已被更高分取代", 0, oldTerm.courseCount)
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
