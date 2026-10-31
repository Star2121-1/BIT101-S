package cn.bit101.android.data.score

import com.google.gson.JsonElement

/**
 * 成绩表的**完整**解析与统计（原生成绩页用）。
 *
 * ## 与 [ScoreLogic] 的分工
 * - [ScoreLogic]：只抽「课名 / 成绩 / 学期」三列，服务**出分提醒**的差分 ——
 *   它刻意不碰学分和绩点，因为通知里连分数都不显示（用户定的隐私边界）。
 * - [ScoreTable]（本文件）：**保留全部列**，服务成绩页的列表、详情、加权统计。
 *
 * 两者共用 [ScoreLogic.rowsOf] / [ScoreLogic.findColumn] 这一份表格读取实现，
 * 不各自复制一遍（同仓「一份实现」的既有规矩）。
 *
 * ## 表头是从哪来的（不是猜的）
 * 2026-10-31 对照官方三端源码核实：
 * - `BIT101-dev/BIT101`（Web，`src/views/Score.vue`）用 `课程名称/成绩/平均分/学分/
 *   开课学期/课程性质/课程编号/本人成绩在班级中占/本人成绩在专业中占`；
 * - `BIT101-dev/BIT101-iOS`（`ScoreDomain/ScoreModels.swift`）同一套键名，
 *   并声明 `requiredHeaderNames = {课程名称, 课程编号, 成绩, 学分}`；
 * - `BIT101-dev/BIT101-GO`（`pkg/webvpn/score.go`）证明表头是**从教务页面动态抓**的
 *   （`#dataList tr` 第一行），所以**列序会变**，只能按键名读。
 *
 * ⇒ 这里一律按**列名**取值，绝不按列下标。找不到关键列就不猜，返回 null。
 *
 * ## GPA 公式（学校公布口径，与 Web / iOS 完全一致）
 * - 等级制：优秀 95 / 4.0、良好 85 / 3.6、中等 75 / 2.8、及格 65 / 1.7、不及格 0 / 0
 * - 百分制：`x < 60 ⇒ 绩点 0`；否则 `绩点 = 4 - 3 × (100 - x)² / 1600`
 *   （100 分 → 4.0，90 分 → 3.8125，60 分 → 1.0）
 * - 加权：`Σ(值 × 学分) / Σ学分`
 * - **同一门课修读多次**（同课程编号多条记录）：只取**成绩最高**的一条参与统计，
 *   与 Web/iOS 一致 —— 这是学校重修覆盖的通行口径，不是我们的发挥
 */
object ScoreTable {

    /** 没有它就不认这张表：课程名（没名字的行没法展示）。 */
    private val COURSE_KEYS = listOf("课程名称", "课程")
    private val COURSE_EXCLUDE = listOf("编号", "代码", "号")

    /** 成绩列关键词（按优先级）。 */
    private val SCORE_KEYS = listOf("成绩", "分数", "总评", "得分")

    /** 学分列关键词。 */
    private val CREDIT_KEYS = listOf("学分")

    /** 学期列关键词 —— 教务真实表头是「开课学期」，先试具体的再退回「学期」。 */
    private val TERM_KEYS = listOf("开课学期", "学期", "学年")

    /** 课程性质列关键词。 */
    private val TYPE_KEYS = listOf("课程性质", "课程属性")

    /** 课程编号列关键词（统计去重用）。 */
    private val NUMBER_KEYS = listOf("课程编号", "课程号")

    /** 平均分列关键词（同课程同期的班平，用于详情展示）。 */
    private val AVG_KEYS = listOf("平均分")

    private val CLASS_RANK_KEYS = listOf("本人成绩在班级中占", "班级排名", "班级")
    private val MAJOR_RANK_KEYS = listOf("本人成绩在专业中占", "专业排名", "专业")

    /** 一行成绩。保留原始列序，详情页按原样展示。 */
    data class Row(
        /** 表内行序（1 起），只用于列表 key。 */
        val index: Int,
        /** 与 [cells] 一一对应的列名。 */
        val headers: List<String>,
        val cells: List<String>,
    ) {
        /** 按列名取值；列不存在时返回空串（页面不用到处判空）。 */
        operator fun get(key: String): String =
            headers.indexOfFirst { it == key }.let { if (it < 0) "" else cells.getOrElse(it) { "" } }

        /** 保序的「列名 → 值」，详情页直接用。 */
        val fields: List<Pair<String, String>>
            get() = headers.mapIndexed { i, h -> h to cells.getOrElse(i) { "" } }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }

        val courseName: String get() = valueOf(COURSE_KEYS, COURSE_EXCLUDE)
        val courseNumber: String get() = valueOf(NUMBER_KEYS)
        val scoreText: String get() = valueOf(SCORE_KEYS)
        val creditText: String get() = valueOf(CREDIT_KEYS)
        val term: String get() = valueOf(TERM_KEYS)
        val courseType: String get() = valueOf(TYPE_KEYS)
        val averageScoreText: String get() = valueOf(AVG_KEYS)
        val classRank: String get() = valueOf(CLASS_RANK_KEYS)
        val majorRank: String get() = valueOf(MAJOR_RANK_KEYS)

        /** 学分；解析不出来给 null（**不要**当 0 —— 0 学分不参与加权，语义不同）。 */
        val credit: Double?
            get() = creditText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        private fun valueOf(keys: List<String>, exclude: List<String> = emptyList()): String {
            for (k in keys) {
                val i = headers.indexOfFirst { h -> h.contains(k) && exclude.none { h.contains(it) } }
                if (i >= 0) return cells.getOrElse(i) { "" }.trim()
            }
            return ""
        }
    }

    /** 解析好的成绩表。 */
    data class Table(
        val headers: List<String>,
        val rows: List<Row>,
    ) {
        /** 出现过的学期，**新的在前**（`2026-2027-1` 这种格式按字符串倒序即正确）。 */
        val terms: List<String>
            get() = rows.map { it.term }.filter { it.isNotBlank() }.distinct().sortedDescending()

        /** 出现过的课程性质（保持表内顺序）。 */
        val courseTypes: List<String>
            get() = rows.map { it.courseType }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * 解析二维表。
     *
     * @return 找不到表头 / 没有课程名列时返回 **null**（调用方显示「没拿到成绩」，
     *   而不是把一堆乱码当成绩渲染）。行里课名为空的行会被跳过。
     *
     * ⚠️ **空数组 `[]` 返回「空表」而不是 null**：2026-10-31 真机实测，本学期还没出分时
     * 认证主机就是这么回的（连表头都不给）。把它当「格式错误」会让一个正常状态显示成
     * 「教务改版了」——用户会以为坏了。
     */
    fun parse(root: JsonElement?): Table? {
        // 连数组都不是 ⇒ 这不是成绩表（null / 对象 / 字符串）
        if (root == null || !root.isJsonArray) return null
        val rows = ScoreLogic.rowsOf(root)
        // 是数组但没内容 ≠ 格式错：接口给空数组时就是「一门成绩都还没有」
        if (rows.isEmpty() || rows.all { it.all { cell -> cell.isBlank() } }) {
            return Table(headers = emptyList(), rows = emptyList())
        }

        // 表头行 = 第一个「像表头」的行（含课程/成绩/学期任一关键词）。
        // ⚠️ 不能假定第 0 行：真实接口第 0 行就是表头，但 `detail=false` 与 `true`
        //   两种响应拼接过，上游不保证。
        val headerIndex = rows.indexOfFirst { row -> row.any { ScoreLogic.isHeaderCell(it) } }
        if (headerIndex < 0) return null

        val headers = rows[headerIndex].map { it.trim() }
        val courseCol = ScoreLogic.findColumn(headers, COURSE_KEYS, COURSE_EXCLUDE)
        if (courseCol < 0) return null

        val out = ArrayList<Row>(rows.size)
        rows.drop(headerIndex + 1).forEachIndexed { i, cells ->
            val name = cells.getOrNull(courseCol)?.trim().orEmpty()
            if (name.isBlank()) return@forEachIndexed
            // 补齐到表头长度：教务偶尔给出比表头短的行，按列名取值时才不会错位
            val padded = if (cells.size >= headers.size) cells else cells + List(headers.size - cells.size) { "" }
            out += Row(index = i + 1, headers = headers, cells = padded)
        }
        // 有表头但一行数据都没有 —— 也是「还没成绩」，不是格式错
        return Table(headers = headers, rows = out)
    }

    // ------------------------------------------------------------ 成绩 ↔ 数值

    /** 等级制 → (百分制, 绩点)；不是等级描述时返回 null。 */
    private fun gradeOf(raw: String): Pair<Double, Double>? = when (raw.trim()) {
        "优秀" -> 95.0 to 4.0
        "良好" -> 85.0 to 3.6
        "中等" -> 75.0 to 2.8
        "及格" -> 65.0 to 1.7
        "不及格" -> 0.0 to 0.0
        else -> null
    }

    /**
     * 成绩原文 → 统计用百分制数值；认不出来返回 null。
     *
     * 认不出的典型：`缓考`、`缺考`、`—`、空串 —— 这些**必须跳过**，
     * 当成 0 分会把平均分拉垮（这是最容易被忽略的错）。
     */
    fun scoreOf(raw: String): Double? {
        gradeOf(raw)?.let { return it.first }
        val x = raw.trim().removeSuffix("分").toDoubleOrNull() ?: return null
        return x.takeIf { it in 0.0..100.0 }
    }

    /** 成绩原文 → 绩点；认不出来返回 null。见类文档里的公式与实测值。 */
    fun gpaOf(raw: String): Double? {
        gradeOf(raw)?.let { return it.second }
        val x = raw.trim().removeSuffix("分").toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return null
        if (x < 60) return 0.0
        return 4 - 3 * (100 - x) * (100 - x) / 1600
    }

    // ------------------------------------------------------------ 统计

    /**
     * 加权统计结果。
     *
     * @param courseCount 参与统计的**门数**（已按课程编号去重）
     * @param totalCredit 参与统计的总学分
     * @param weightedScore 加权平均分；没有可统计的课时为 null
     * @param weightedGpa 加权平均绩点；同上
     * @param skipped 被跳过的行数（成绩认不出 / 学分缺失），页面可提示「N 门未计入」
     */
    data class Summary(
        val courseCount: Int,
        val totalCredit: Double,
        val weightedScore: Double?,
        val weightedGpa: Double?,
        val skipped: Int,
    ) {
        companion object {
            val EMPTY = Summary(0, 0.0, null, null, 0)
        }
    }

    /**
     * 加权统计。
     *
     * 去重口径：同一 `课程编号` 多条记录时**取成绩最高的那条**（重修覆盖），
     * 没有编号的行不参与去重、各自计入。
     */
    fun summarize(rows: List<Row>): Summary {
        if (rows.isEmpty()) return Summary.EMPTY

        var skipped = 0
        val bestByCourse = LinkedHashMap<String, Row>()
        val noNumber = ArrayList<Row>()

        rows.forEach { row ->
            val score = scoreOf(row.scoreText)
            val credit = row.credit
            if (score == null || credit == null || credit <= 0) {
                skipped++
                return@forEach
            }
            val key = row.courseNumber.filterNot { it.isWhitespace() }.lowercase()
            if (key.isEmpty()) {
                noNumber += row
                return@forEach
            }
            val existing = bestByCourse[key]
            if (existing == null || (scoreOf(existing.scoreText) ?: 0.0) < score) {
                bestByCourse[key] = row
            }
        }

        var creditSum = 0.0
        var scoreSum = 0.0
        var gpaSum = 0.0
        var count = 0
        (bestByCourse.values + noNumber).forEach { row ->
            val credit = row.credit ?: return@forEach
            val score = scoreOf(row.scoreText) ?: return@forEach
            val gpa = gpaOf(row.scoreText) ?: return@forEach
            creditSum += credit
            scoreSum += score * credit
            gpaSum += gpa * credit
            count++
        }
        if (creditSum <= 0) return Summary(0, 0.0, null, null, skipped)
        return Summary(
            courseCount = count,
            totalCredit = creditSum,
            weightedScore = scoreSum / creditSum,
            weightedGpa = gpaSum / creditSum,
            skipped = skipped,
        )
    }
}
