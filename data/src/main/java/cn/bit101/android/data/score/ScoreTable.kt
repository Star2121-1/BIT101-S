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
 * ## GPA 公式：按 教〔2022〕16号 走，**不跟网页版走**
 *
 * 学校教务处的红头文件原文（`jwb.bit.edu.cn/tzgg/2fc6522535bd4adfa4ba9fdc992af3b6.htm`，
 * 网页版自己引用的那个 `0fd24ee9…` 链接已经 404）：
 *
 * - 百分制：`X < 60 ⇒ 课程绩点记为 0`；否则 `绩点 = 4 - 3 × (100 - X)² / 1600`；
 * - **课程绩点四舍五入精确到小数点后一位**，再参与加权；
 * - 等级制：优秀 4.0、良好 3.6、中等 2.8、及格 1.7、不及格 0
 *   （这四个值正好是 95/85/75/65 代入公式后舍入到 0.1 的结果，自洽）；
 * - `平均学分绩点 = Σ(学分 × 课程绩点) / Σ学分`，**所有修读课程都算**；
 * - **校公选课的不及格成绩不计入绩点**（唯一一种可以整门剔掉的情况）；
 * - 同一门课重修/补考多条记录：**只取最高成绩**。
 *
 * ⚠️ **网页版在这两处与红头文件不符**（我们刻意不跟）：
 * ① 它把 `成绩==0` 的课程**整门剔除**（`N()` 里 `if(B(成绩)==0) continue`），
 * 导致不及格不进分母、绩点虚高；② 它没有逐课舍入。
 * 实测差异：85分/3学分 + 45分/4学分 → 按文件 **1.5429**，网页版 **3.5781**，差 2.04。
 * 这两条直接影响保研/评奖，所以以文件为准。
 *
 * 数值边界：90 → 3.8、85 → 3.6、80 → **3.3**（3.25 进位，注意不能用银行家舍入）、
 * 60 → 1.0、59 → 0、100 → 4.0。
 */
object ScoreTable {

    /** 没有它就不认这张表：课程名（没名字的行没法展示）。 */
    private val COURSE_KEYS = listOf("课程名称", "课程")
    private val COURSE_EXCLUDE = listOf("编号", "代码", "号")

    /** 成绩列关键词（按优先级）。 */
    private val SCORE_KEYS = listOf("成绩", "分数", "总评", "得分")

    /**
     * 成绩列的排除词。
     *
     * ⚠️ 教务真实表头是 **16 列**，里面「成绩」和「成绩标识」并列（`ScoreLogicTest`
     * 里 2026-09-26 抓的真实表头可查）。没有排除词的时候，只要教务把两列的顺序
     * 调换一下，`contains("成绩")` 就会命中「成绩标识」—— 那一列通常是
     * 「正常考试 / 补考 / 重修」，`toDoubleOrNull` 拿不到数，整张表会变成 0 门。
     * 现在只是靠「成绩」排在前面侥幸没出事。
     */
    private val SCORE_EXCLUDE = listOf("标识", "性质", "方式", "类型", "备注", "说明", "绩点")

    /** 学分列关键词。 */
    private val CREDIT_KEYS = listOf("学分")

    /** 学分列的排除词（防命中「总学分 / 学分绩点 / 平均学分」这类汇总列）。 */
    private val CREDIT_EXCLUDE = listOf("绩点", "平均", "总", "已修", "未修")

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
        val scoreText: String get() = valueOf(SCORE_KEYS, SCORE_EXCLUDE)
        val creditText: String get() = valueOf(CREDIT_KEYS, CREDIT_EXCLUDE)
        val term: String get() = valueOf(TERM_KEYS)
        val courseType: String get() = valueOf(TYPE_KEYS)

        /**
         * 课程**类别**（成绩表里叫「课程种类」，课表接口里是 `KCLBDM_DISPLAY`）。
         *
         * ⚠️ 与「课程性质」（`KCXZDM`，值是必修/选修）和「课程归属」（院系/模块）
         * 是三个不同维度，别混。「校公选课」这个值实际出现在**这一列**。
         */
        val courseCategory: String get() = valueOf(CATEGORY_KEYS)

        /**
         * 是不是**校公选课**。
         *
         * 只用来实现 教〔2022〕16号 那条「校公选课的不及格成绩不计入绩点」。
         *
         * ⚠️ **三个坑，都别踩**：
         * 1. 必须**精确相等**，不能 `contains("公选")`。「专业选修课」之类的值也带「选」字，
         *    模糊匹配会把专选/必修的不及格一起剔掉 —— 那是**虚高绩点**，比少剔一门
         *    严重得多（错误方向不对称：多算一门只是数字保守，少算一门会让保研/评奖虚高）。
         * 2. 查「课程性质」+「课程种类/类别」，**不查「课程归属」** —— 后者是院系/模块，
         *    与「这课算不算校公选」无关。
         * 3. 「校公选课」的真实归属是**课程类别**（`KCLBDM`）而不是课程性质（`KCXZDM`）；
         *    成绩表里对应「课程种类」列。2026-10-31 用 iOS 课表 fixture 复核过
         *    （14 门课里 `KCLBDM_DISPLAY` 出现「校公选课」×4，`KCXZDM_DISPLAY` 只有必修/选修）。
         *
         * ## 成绩表可能压根没有这一列
         *
         * 真机 16 列表头是 `序号/开课学期/课程编号/课程名称/成绩/成绩标识/学分/总学时/
         * 考试性质/考核方式/课程属性/课程性质/课程归属/课程种类/是否第一次考试/操作栏` ——
         * 有「课程种类」，但**未验证**它是否填了「校公选课」。
         * 匹配不上时**没有任何副作用**（按「所有课程都算」的官方默认口径处理）。
         * 等拿到真实取值再补，**不要**为了覆盖面去加模糊匹配，也不要碰「课程归属」。
         */
        val isPublicElective: Boolean
            get() = listOf(courseType, courseCategory).any { it.trim() in PUBLIC_ELECTIVE_NAMES }

        val averageScoreText: String get() = valueOf(AVG_KEYS)
        val classRank: String get() = valueOf(CLASS_RANK_KEYS)
        val majorRank: String get() = valueOf(MAJOR_RANK_KEYS)

        /**
         * 学分；解析不出来给 null（**不要**当 0 —— 0 学分不参与加权，语义不同）。
         *
         * ⚠️ `isFinite()` 必不可少：`"NaN".toDoubleOrNull()` 会老老实实返回
         * `Double.NaN`，而 `NaN <= 0` 是 **false** —— 下游那道 `credit <= 0` 拦不住它，
         * 于是 `creditSum` 变 NaN、`creditSum <= 0` 还是 false，最后界面直接印
         * 「GPA: NaN / 总学分: NaN」。这是唯一一条「数学上已经坏了却当成正常结果返回」的路。
         */
        val credit: Double?
            get() = creditText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                ?.takeIf { it.isFinite() }

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

        /**
         * **全量行**上按课程去重后，每门课成绩最高的那条。
         *
         * ## 为什么必须在全量上做（顺序很关键）
         *
         * 官方口径的绩点是「**在读期间**修读的所有课程」，所以去重是**跨学期**的：
         * 一门课 2024-2025-1 挂了、2025-2026-1 重修及格，全局只算及格那条。
         *
         * 如果反过来「先按学期筛、再在筛出来的子集里去重」，那么用户切到挂科那个学期时，
         * 那门课只看得见挂科记录 ⇒ 挂科被当成最终成绩又算一次，跟全学期视图的数字对不上。
         * 官方网页版和 iOS 都是先筛后去重，**这里刻意不跟**（`official-formula` 调查确认的）。
         *
         * ⚠️ 副作用（要认）：某门课只出现在它**最高分记录所在的那个学期**。用户切到
         * 「没考好的那个学期」时，列表里还看得见该行，但顶部统计里不含它 —— 这是
         * 「跨学期去重」的必然结果，不是 bug。
         */
        val bestRows: List<Row>
            get() {
                val best = LinkedHashMap<String, Row>()
                rows.forEach { row ->
                    val key = keyOf(row)
                    val existing = best[key]
                    if (existing == null || (scoreOf(existing.scoreText) ?: -1.0) < (scoreOf(row.scoreText) ?: -1.0)) {
                        best[key] = row
                    }
                }
                return best.values.toList()
            }
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
            // ⚠️ 跳过「重复表头行」：`detail=false` 与 `detail=true` 两种响应被上游拼接过，
            // 中间可能夹第二个表头。早先没挡，那一行会变成列表里一门叫「课程名称」的假课，
            // 同时因为 `scoreText` 是「成绩」这两个字而算不出分 ⇒ 还会多报「另有 1 门没计入」。
            // 判定用「与表头逐字相等的单元格 ≥ 2 个」——数据行很难同时有两格正好等于列名，
            // 而用「含关键词」判定会误伤「程序设计课程设计 / 学科基础课程」这种真实课名。
            val headerLike = cells.count { c ->
                val t = c.trim()
                t.isNotBlank() && headers.any { it.isNotBlank() && it == t }
            } >= 2
            if (headerLike) return@forEachIndexed

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

    /**
     * 等级制 → (百分制, 绩点)；不是等级描述时返回 null。
     *
     * ## 这对数字是**反推**出来的，不是文件直接给的（要说清楚）
     *
     * 教〔2022〕16号 只规定了「等级 → 课程绩点」，没规定「等级 → 百分制分数」。
     * 但把 95/85/75/65 代进那个公式再按 0.1 四舍五入，正好得到 4.0/3.6/2.8/1.7：
     * `4-3(5)²/1600=3.9531→4.0`、`4-3(15)²/1600=3.5781→3.6`、
     * `4-3(25)²/1600=2.8281→2.8`、`4-3(35)²/1600=1.7031→1.7`。
     * ⇒ 三端用的 95/85/75/65 与红头文件是**自洽**的，可以放心用。
     *
     * ⚠️ 唯一带推断成分的是**「加权均分」**里五级制课程按 95/85/75/65 参与平均 ——
     * 红头文件定义的是「平均学分绩 = Σ(学分×成绩)/Σ学分」，五级制换算成分数的
     * 具体值学校没公开。这个数只用于展示，不影响学分绩点。
     */
    private fun gradeOf(raw: String): Pair<Double, Double>? = when (raw.trim()) {
        "优秀" -> 95.0 to 4.0
        "良好" -> 85.0 to 3.6
        "中等" -> 75.0 to 2.8
        "及格" -> 65.0 to 1.7
        "不及格" -> 0.0 to 0.0
        else -> null
    }

    /**
     * 教务把「没有分数、但按 0 分记」的几种写法。
     *
     * 依据 **办发〔2019〕75号《学籍管理细则》第二十三条**（不是 16号文 —— 16号文只有
     * 「计算范围 / 计算规则 / 出具形式 / 附则」四节，**没有第二十三条**）：
     * 「凡擅自缺考（即旷考）的，该课程成绩按『0』分记」「凡严重违反考核纪律或者作弊的，
     * 该课程考核成绩应记为『无效』，在成绩测算时按『0』分对待」。
     * 所以这些**不是「跳过」而是「0 分」** —— 计入均分与绩点分母。
     *
     * ⚠️ `缓考` **不在此列**。理由要说准：**官方文件里根本没有「缓考怎么记分」这一条**
     * （75号全文无「缓考」二字，《本科课程考核工作规定》里 4 处「缓考」都只讲申请流程，
     * 只说「不得参加当次考试」）。所以「缓考跳过」是**我们的实现约定**，不是官方规定 ——
     * 依据是「成绩还没出，没有事实可记」。把缓考当 0 分会平白拉垮绩点。
     */
    private val ZERO_MARKERS = listOf("缺考", "旷考", "作弊", "无效")

    /**
     * 逐课绩点保留 1 位小数（**四舍五入**，half-up）。
     *
     * ⚠️ 这里绝对不能用 `kotlin.math.round` —— 它是 **ties-to-even（银行家舍入）**：
     * `round(32.5) == 32.0`。而 60~100 分（0.1 步长）里**唯一**精确落在 `.x5`
     * 半值上的分数就是 **80 分 → 3.25**，于是 80 分会被舍成 3.2 而不是 3.3。
     * 只错这一门、只差 0.1，几乎没人会对得出来。
     *
     * ⇒ 必须显式做 half-up。`floor(x + 0.5)` 是其中一种等价实现（GPA 恒非负，
     * 不用管负数方向）。
     *
     * ⚠️ 坑**只在 Kotlin**：`kotlin.math.round` 走 `Math.rint`。`Double.roundToInt()`、
     * Swift 的 `.rounded()`、JS 的 `Math.round` 默认都是 half-up，不需要额外写法。
     *
     * ⚠️ 另：预舍入只保证**统计**正确，不保证**显示**一致 —— 聚合值是分母由 Σ学分
     * 决定的有理数，任意显示精度都可能精确落在 `.x5` 上
     * （1 位：`(3.2+3.3)/2 = 3.25`；2 位：`(3.1×6+3.2×2)/8 = 3.125`）。
     * 所以显示层不能把未按显示位数舍入的 Double 直接交给 `%.2f`。
     */
    private fun round1(x: Double): Double = kotlin.math.floor(x * 10 + 0.5) / 10

    /**
     * 成绩原文 → 统计用百分制数值；认不出来返回 null。
     *
     * 认不出的典型：`缓考`、`免修`、`—`、空串 —— 这些**必须跳过**，
     * 当成 0 分会把平均分拉垮（这是最容易被忽略的错）。
     * 而 `缺考` / `旷考` / `作弊` 是**真的 0 分**（见 [ZERO_MARKERS]），不是跳过。
     */
    fun scoreOf(raw: String): Double? {
        gradeOf(raw)?.let { return it.first }
        val t = raw.trim().removeSuffix("分").trim()
        if (ZERO_MARKERS.any { t == it || t.contains(it) }) return 0.0
        val x = t.toDoubleOrNull() ?: return null
        return x.takeIf { it in 0.0..100.0 }
    }

    /**
     * 成绩原文 → **课程绩点**；认不出来返回 null。
     *
     * ## 两条口径都来自 教〔2022〕16号（三端原先都没做对）
     *
     * 1. **不及格也必须算**：「若 X 小于 60 则课程绩点记为 0」—— 是记 0 后
     *    **照常参与加权**，不是把整门课踢出去。只有**校公选课**不及格才不计入
     *    （由 [summarize] 判断，不在这个函数里）。
     * 2. **逐课绩点四舍五入到 1 位小数**：公式原值必须先 round 再加权。
     *    ⇒ 90 分是 **3.8** 而不是 3.8125；85 分是 3.6；80 分是 3.3（3.25 进位）。
     *
     * 这两点直接影响保研/评奖时的「平均学分绩点」，跟网页版算出来的数会不一样 ——
     * 我们按**红头文件**走，不按网页版的 bug 走。
     */
    fun gpaOf(raw: String): Double? {
        gradeOf(raw)?.let { return it.second }
        val t = raw.trim().removeSuffix("分").trim()
        if (ZERO_MARKERS.any { t == it || t.contains(it) }) return 0.0
        val x = t.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return null
        if (x < 60) return 0.0
        return round1(4 - 3 * (100 - x) * (100 - x) / 1600)
    }

    // ------------------------------------------------------------ 统计

    /**
     * 加权统计结果。
     *
     * ⚠️ **三个「学分/门数」类字段语义不同，界面上必须分开显示**（早先把它们
     * 混成一个「总学分」，用户以为一学期只修了 4 学分）：
     *
     * | 字段 | 含义 |
     * |---|---|
     * | [totalCredit] | **计入绩点**的学分合计（及格 + 不及格都算；校公选不及格、缓考不算） |
     * | [earnedCredit] | **已获得**学分（只有及格才拿到，依据《学籍管理细则》第十五条） |
     * | [courseCount] | 计入绩点的门数 |
     *
     * @param weightedScore 加权平均分（百分制）；没有可统计的课时为 null
     * @param weightedGpa 加权平均**学分绩点**（4 分制）；同上
     * @param skipped 没有任何可用成绩、因而**整门没计入**的门数（缓考 / 免修 / 学分不明）
     * @param electiveFailed 因「校公选课不及格」被排除的门数（规定如此，见下）
     */
    data class Summary(
        val courseCount: Int,
        val totalCredit: Double,
        val weightedScore: Double?,
        val weightedGpa: Double?,
        val skipped: Int,
        /**
         * 校公选课不及格、按 教〔2022〕16号「校公选课的不及格成绩不计入绩点」排除的门数。
         *
         * ⚠️ **只有校公选课**。必修/专业选修的不及格要计入分母（绩点 0），
         * 那才是官方口径 —— 网页版把所有不及格都剔除了，是它的 bug。
         */
        val electiveFailed: Int = 0,
        /** 已获得学分：只有及格（≥60 或五级制「及格」及以上）才拿到。 */
        val earnedCredit: Double = 0.0,    ) {
        companion object {
            val EMPTY = Summary(0, 0.0, null, null, 0, 0, 0.0)
        }
    }

    /**
     * 校公选课的**精确**取值集合（`课程性质` 或 `课程种类/类别` 命中其一才算）。
     *
     * ## 为什么只有这 5 个值
     *
     * - 「文化素质通识课 / 实践训练通识课 / 拓展英语」：官方文件里「校公选课」的展开 —— **明文**；
     * - 「校公选课」：课表 `KCLBDM_DISPLAY` 实际观测到的值 —— **观测**（课表，非成绩表）；
     * - 「校公选」：成绩表会去掉末尾的「课程 / 课」（课表「公共基础课程」→ 成绩表
     *   「公共基础」，课表「专业课」→ 成绩表「专业」），同规则推出 —— **推断**。
     *
     * ⚠️ **刻意不收「公共选修课」「公选课」**：官方从没这么叫，而「公共选修」在实际
     * 教务里常是院级/校级选修的统称，可能包含非校公选课程。一旦误命中就是
     * **整门剔除、学分不进分母 ⇒ 绩点虚高** —— 这是不可接受的方向。
     * 宁可漏判（后果只是绩点偏低、且只影响个别校公选挂科）。
     */
    private val PUBLIC_ELECTIVE_NAMES = setOf(
        "校公选课", "校公选",
        "文化素质通识课", "实践训练通识课", "拓展英语",
    )
    /** 课程归属 / 种类列（判定校公选课用）。 */
    /**
     * 课程**类别**列（成绩表里叫「课程种类」）。
     *
     * ⚠️ 刻意**不含「课程归属」** —— 那是院系/模块维度，跟「校公选」无关。
     * 见 [Row.isPublicElective]。
     */
    private val CATEGORY_KEYS = listOf("课程种类", "课程类别")

    /**
     * 加权统计。
     *
     * ## 口径来源：教〔2022〕16号（学校教务处红头文件）
     *
     * 这一节**刻意不跟网页版走** —— 网页版在这两处与红头文件不一致，
     * 而且它自己引用的「官方公式」链接已经 404。逐条：
     *
     * 1. **在读期间修读的所有课程都计入**（百分制与五级制都算）；
     * 2. **不及格按「课程绩点记 0」照常参与加权**，学分进分母。
     *    ⇒ 网页版把所有 `成绩==0` 的课程整门剔除，会让绩点虚高
     *    （85分/3学分 + 45分/4学分：按文件 1.5429，网页版 3.5781，差 2.04）；
     * 3. **只有校公选课的不及格不计入绩点**（记入 [Summary.electiveFailed]）；
     * 4. **逐课绩点先四舍五入到 1 位小数再加权**（见 [gpaOf]）；
     * 5. 同一门课因重修/补考有多条成绩时，**只取最高成绩**。
     *
     * ## 我们自己的两条（红头文件没规定，选保守做法）
     *
     * - `缓考` / `免修` / 认不出的成绩 / 0 学分：整门不计入，记入 [Summary.skipped]；
     *   ⚠️ 但 `缺考`/`旷考`/`作弊` 是**真的 0 分**，照常计入（见 [ZERO_MARKERS]）。
     * - 没有课程编号时退回用**课程名**做去重键 —— 否则一次重修就被算成两门。
     */
    fun summarize(rows: List<Row>): Summary {
        if (rows.isEmpty()) return Summary.EMPTY

        val best = LinkedHashMap<String, Row>()
        val noScoreKeys = LinkedHashSet<String>()
        val electiveFailedKeys = LinkedHashSet<String>()

        rows.forEach { row ->
            val key = keyOf(row)
            val score = scoreOf(row.scoreText)
            val gpa = gpaOf(row.scoreText)
            val credit = row.credit
            if (score == null || gpa == null || credit == null || credit <= 0) {
                noScoreKeys += key
                return@forEach
            }
            // 校公选课不及格：不计入绩点（唯一一种「不及格可以剔掉」的情况）
            if (gpa <= 0.0 && row.isPublicElective) {
                electiveFailedKeys += key
                return@forEach
            }
            val existing = best[key]
            if (existing == null || (scoreOf(existing.scoreText) ?: 0.0) < score) {
                best[key] = row
            }
        }

        // 按**门**而不是按行报数：同一门课「缓考一条 + 及格一条」时这门课是算进去的，
        // 不该同时出现在「另有 N 门没计入」里（那会让用户觉得自相矛盾）。
        // ⚠️ 两遍都要排除 `best`，`skipped` 还要再排除 `electiveFailed` ——
        // 同一门课可能同时有「缓考一条」和「校公选不及格一条」，那是**一门课**，
        // 不能两边各报一次（早先就是这么重复计数的）。
        val electiveFailed = electiveFailedKeys.count { it !in best }
        val skipped = noScoreKeys.count { it !in best && it !in electiveFailedKeys }

        var creditSum = 0.0
        var scoreSum = 0.0
        var gpaSum = 0.0
        var earnedSum = 0.0
        var count = 0
        best.values.forEach { row ->
            val credit = row.credit ?: return@forEach
            val score = scoreOf(row.scoreText) ?: return@forEach
            val gpa = gpaOf(row.scoreText) ?: return@forEach
            creditSum += credit
            scoreSum += score * credit
            gpaSum += gpa * credit
            // 「考核成绩及格的，取得规定的学分」——《学籍管理细则》第十五条。
            // ⚠️ 当前实现（`score >= 60`）是「已获学分 = 数学及格且确实计入绩点的
            // 已交费课程」，与建议口径的差异在**未缴费课程**与**课程归属不在培养方案的
            // 非校公选挂科**两处。现有实现在这两处**多算**（把没拿到的学分算成已获）。
            // 要精确需要接入缴费状态/培养方案数据，而成绩表里没有这些列 ⇒ 暂不做。
            if (score >= 60.0) earnedSum += credit
            count++
        }
        if (creditSum <= 0) return Summary(0, 0.0, null, null, skipped, electiveFailed, 0.0)
        return Summary(
            courseCount = count,
            totalCredit = creditSum,
            weightedScore = scoreSum / creditSum,
            weightedGpa = gpaSum / creditSum,
            skipped = skipped,
            electiveFailed = electiveFailed,
            earnedCredit = earnedSum,
        )
    }

    /**
     * 去重键：优先课程编号，没有编号就退回课程名。
     *
     * ⚠️ 退回课程名是关键 —— `parse` 只强制要求「课程名称」列存在，**不要求编号列**。
     * 一旦教务不给编号列，早先的实现会把所有行放进「无编号」桶各自独立计入，
     * 一次重修就多算一门课、多算一遍学分。
     */
    private fun keyOf(row: Row): String {
        val number = row.courseNumber.filterNot { it.isWhitespace() }.lowercase()
        if (number.isNotEmpty()) return "n:$number"
        val name = row.courseName.filterNot { it.isWhitespace() }.lowercase()
        if (name.isNotEmpty()) return "c:$name"
        return "r:${row.index}"
    }
}
