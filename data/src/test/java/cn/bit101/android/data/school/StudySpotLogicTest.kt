package cn.bit101.android.data.school

import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.TimeTableItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class StudySpotLogicTest {

    private val table = listOf(
        TimeTableItem(LocalTime.of(8, 0), LocalTime.of(8, 50)),
        TimeTableItem(LocalTime.of(8, 55), LocalTime.of(9, 40)),
        TimeTableItem(LocalTime.of(10, 0), LocalTime.of(10, 50)),
    )

    private fun classroom(name: String, busy: String?) = ClassroomInfo(classroomName = name, busyTimeStr = busy)

    /** 节次判定：上课中取当前节，课间取**下一节**（课间十分钟不该推荐人去自习）。 */
    @Test
    fun `节次判定`() {
        assertEquals(1, StudySpotLogic.sectionFor(table, LocalTime.of(8, 20)))
        assertEquals(2, StudySpotLogic.sectionFor(table, LocalTime.of(8, 52)))
        assertEquals(1, StudySpotLogic.sectionFor(table, LocalTime.of(7, 0)))
        assertNull(StudySpotLogic.sectionFor(table, LocalTime.of(22, 0)))
    }

    /** ⚠️ null / 空串 = 全天空闲，不是「数据缺失」。单条非法只跳过它自己。 */
    @Test
    fun `占用节次解析`() {
        assertEquals(setOf(1, 2, 5), StudySpotLogic.parseBusySections("1,2,5"))
        assertEquals(emptySet<Int>(), StudySpotLogic.parseBusySections(null))
        assertEquals(emptySet<Int>(), StudySpotLogic.parseBusySections(""))
        assertEquals(setOf(3, 7), StudySpotLogic.parseBusySections("3,x,7"))
    }

    @Test
    fun `教室在当前时段是否空闲`() {
        assertEquals(false, StudySpotLogic.isFreeAt("1,2", 1))
        assertEquals(true, StudySpotLogic.isFreeAt("1,2", 3))
        assertEquals(true, StudySpotLogic.isFreeAt(null, 1))
        assertEquals(true, StudySpotLogic.isFreeAt("1,2", null))
    }

    @Test
    fun `统计某栋楼的空闲教室数`() {
        val rooms = listOf(
            classroom("I301", "1,2"),
            classroom("I302", null),
            classroom("I303", "3,4"),
        )
        // 第 1 节：I301 占用，另两间空
        assertEquals(2, StudySpotLogic.freeCountOf(rooms, table, LocalTime.of(8, 20)))
        // 第 3 节：I303 占用
        assertEquals(2, StudySpotLogic.freeCountOf(rooms, table, LocalTime.of(10, 20)))
    }

    /** ⚠️ 优先级：有课的时候推荐空教室是蠢话。 */
    @Test
    fun `正在上课时不推荐空教室`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(8, 20),
            course = StudySpotLogic.NextCourse(name = "高等数学", classroom = "综教B301", endsAt = LocalTime.of(8, 50)),
            booking = StudySpotLogic.Booking("文萃楼I404", "13:20"),
            buildings = listOf(StudySpotLogic.BuildingFree("b1", "文萃楼", 12)),
        )
        assertEquals(StudySpotLogic.Kind.IN_CLASS, s?.kind)
        assertTrue(s!!.title.contains("高等数学"))
    }

    @Test
    fun `有预约时说预约位置`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(12, 0),
            course = null,
            booking = StudySpotLogic.Booking("文萃楼I404", "13:20"),
            buildings = listOf(StudySpotLogic.BuildingFree("b1", "文萃楼", 12)),
        )
        assertEquals(StudySpotLogic.Kind.HAS_BOOKING, s?.kind)
        assertTrue(s!!.title.contains("文萃楼I404"))
    }

    @Test
    fun `没课没预约时推荐空闲最多的楼`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(12, 0),
            course = null,
            booking = null,
            buildings = listOf(
                StudySpotLogic.BuildingFree("b1", "文萃楼", 3),
                StudySpotLogic.BuildingFree("b2", "综教", 12),
            ),
        )
        assertEquals(StudySpotLogic.Kind.FREE_ROOM, s?.kind)
        assertEquals("综教", s?.buildingName)
        assertEquals(12, s?.freeCount)
    }

    /** ⚠️ 取不到数据就**不给建议** —— 一条瞎猜的建议比没有更糟。 */
    @Test
    fun `取不到空教室数据时不给建议`() {
        assertNull(
            StudySpotLogic.suggest(
                now = LocalTime.of(12, 0),
                course = null,
                booking = null,
                buildings = emptyList(),
            ),
        )
    }

    /** 全都满了一样不该说「有空教室」。 */
    @Test
    fun `所有楼都满了不给建议`() {
        assertNull(
            StudySpotLogic.suggest(
                now = LocalTime.of(12, 0),
                course = null,
                booking = null,
                buildings = listOf(
                    StudySpotLogic.BuildingFree("b1", "文萃楼", 0),
                    StudySpotLogic.BuildingFree("b2", "综教", 0),
                ),
            ),
        )
    }

    /**
     * 楼层解析 —— 用**真机空教室页实测的教室名**当样例（2026-10-12）：
     * `文萃楼H125良乡二号机房` / `文萃楼H221良乡四号机房`（带房间名后缀）
     * 与 `文萃楼I101` / `文萃楼I102` / `文萃楼I202`（不带后缀）。
     *
     * ⚠️ 解析不出来要返回 null，**绝不能硬猜一个楼层出来**。
     */
    @Test
    fun `楼层解析用真机教室名样例`() {
        assertEquals(1, StudySpotLogic.floorOf("文萃楼I101", "文萃楼I"))
        assertEquals(1, StudySpotLogic.floorOf("文萃楼I102", "文萃楼I"))
        assertEquals(2, StudySpotLogic.floorOf("文萃楼I202", "文萃楼I"))
        assertEquals(10, StudySpotLogic.floorOf("文萃楼I1001", "文萃楼I"))
        // 教室名不带楼名前缀时，直接取第一段数字
        assertEquals(3, StudySpotLogic.floorOf("I301", "文萃楼I"))
        // 解析不出 → 不给楼层
        assertNull(StudySpotLogic.floorOf("文萃楼I", "文萃楼I"))
        assertNull(StudySpotLogic.floorOf("文萃楼IAB", "文萃楼I"))
    }

    /**
     * ⚠️ 房间号后面还挂着房间名（真机实况）—— 必须取**第一段**数字。
     *
     * 曾经写成「取末尾那串数字」，而末尾是「良乡二号机房」这种汉字 ⇒ 一律解析成 null，
     * 界面上就永远没有楼层（承诺的「精确到楼层」等于没实现）。
     */
    @Test
    fun `房间号带房间名后缀时仍能解析楼层`() {
        assertEquals(1, StudySpotLogic.floorOf("文萃楼H125良乡二号机房", "文萃楼H"))
        assertEquals(1, StudySpotLogic.floorOf("文萃楼H126良乡三号机房", "文萃楼H"))
        assertEquals(2, StudySpotLogic.floorOf("文萃楼H221良乡四号机房", "文萃楼H"))
        assertEquals(2, StudySpotLogic.floorOf("文萃楼H229良乡七号机房", "文萃楼H"))
        // 楼名给了简写（少一个区号字母）也要能解析
        assertEquals(1, StudySpotLogic.floorOf("文萃楼H125良乡二号机房", "文萃楼"))
        // 房间名里的数字**不能**被当成房间号：第一段才是房间号
        assertEquals(2, StudySpotLogic.floorOf("文萃楼H221三号机房2", "文萃楼H"))
        // 第一段数字位数不对 → 宁可没有楼层，也不猜
        assertNull(StudySpotLogic.floorOf("线上考试2", "综合教学楼大楼B"))
        assertNull(StudySpotLogic.floorOf("文萃楼H1-101", "文萃楼H"))
    }

    @Test
    fun `地点名带楼层`() {
        assertEquals("文萃楼I 3 楼", StudySpotLogic.placeName("文萃楼I", 3))
        assertEquals("文萃楼I", StudySpotLogic.placeName("文萃楼I", null))
    }

    /** 有楼层时，推荐文案要写到楼层这一级。 */
    @Test
    fun `推荐文案写到楼层`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(12, 0),
            course = null,
            booking = null,
            buildings = listOf(StudySpotLogic.BuildingFree("b1", "文萃楼I", floor = 3, freeCount = 12)),
        )
        assertEquals("文萃楼I 3 楼现在有 12 间空教室", s?.title)
    }

    /** ⚠️ 组件那份**刻意不含数字**（组件不打网络，数字可能是几小时前的）。 */
    @Test
    fun `组件用的粗粒度文案不含数量`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(12, 0),
            course = null,
            booking = null,
            buildings = listOf(StudySpotLogic.BuildingFree("b1", "文萃楼", 12)),
        )
        val coarse = StudySpotLogic.coarseTitle(s!!)
        assertEquals("文萃楼现在有空教室", coarse)
        assertTrue(coarse?.contains("12") != true)
    }

    /**
     * ⚠️ **上课中也不说「正在上 X」**，只说「哪栋楼有空教室」。
     *
     * 「正在上什么」是**有时效的断言**：组件真刷数据靠 30 分钟周期任务（Doze 下更久），
     * 存进快照就会出现「晚上点开显示上午在上什么」⇒ 组件改为每次重绘用本地课表现算
     * （见 `WidgetLogic.studyCourseLine`），快照只负责需要网络的那一半。
     */
    @Test
    fun `组件用的粗粒度文案在上课时也不说正在上什么`() {
        val s = StudySpotLogic.suggest(
            now = LocalTime.of(8, 20),
            course = StudySpotLogic.NextCourse(name = "操作系统", classroom = "文萃楼I404"),
            booking = null,
            buildings = listOf(StudySpotLogic.BuildingFree("b1", "文萃楼H", 7)),
        )
        assertEquals(StudySpotLogic.Kind.IN_CLASS, s?.kind)
        val coarse = StudySpotLogic.coarseTitle(s!!)
        assertEquals("文萃楼H现在有空教室", coarse)
        assertTrue("粗粒度文案里不该出现有时效的「正在上」", coarse?.contains("正在上") != true)
    }

    // ------------------------------------------------- 「现在算不算有课」的唯一实现

    private fun courseAt(name: String, room: String, start: Int, end: Int) =
        StudySpotLogic.CourseAt(name = name, classroom = room, startSection = start, endSection = end)

    /** 正在上 → 取正在上的那一节，并带上「下课时间」与 `ongoing = true`。 */
    @Test
    fun `正在上课时取当前这一节`() {
        val r = StudySpotLogic.currentCourseOf(
            courses = listOf(courseAt("操作系统", "文萃楼I404", 1, 1)),
            table = table,
            now = LocalTime.of(8, 20),
        )
        assertEquals("操作系统", r?.name)
        assertEquals("文萃楼I404", r?.classroom)
        assertEquals(LocalTime.of(8, 50), r?.endsAt)
        assertEquals(true, r?.ongoing)
    }

    /**
     * ⚠️ **课间十分钟算「马上要上」而不是「正在上」** —— 人还在路上。
     *
     * 这条断言的第一版**期望值写错了**（以为 `ongoing` 该是 true），跑红后才发现代码真错：
     * 当时用 [StudySpotLogic.sectionFor] 的节次号判「正在上」，而它在课间返回的是**下一节**
     * ⇒ 9:40-10:00 的课间会把 10:00 那节课报成「正在上」。
     */
    @Test
    fun `课间十分钟内要上的课算马上要上`() {
        val r = StudySpotLogic.currentCourseOf(
            courses = listOf(courseAt("操作系统", "文萃楼I404", 3, 3)),
            table = table,
            now = LocalTime.of(9, 55),      // 第 3 节 10:00 开始，还有 5 分钟
        )
        assertEquals("操作系统", r?.name)
        assertEquals(false, r?.ongoing)
        assertEquals(LocalTime.of(10, 0), r?.startsAt)
        // 措辞要说清「几点上」，不能说「正在上」
        assertEquals("10:00 上操作系统（文萃楼I404）", StudySpotLogic.courseLine(r!!))
    }

    /**
     * ⚠️ 措辞只有一份实现：正在上 / 马上要上**必须分开说**。
     * 组件与 App 内那张卡都调 [StudySpotLogic.courseLine]，不许各自写。
     */
    @Test
    fun `正在上与马上要上的措辞分开`() {
        assertEquals(
            "正在上操作系统（文萃楼I404）",
            StudySpotLogic.courseLine(
                StudySpotLogic.NextCourse("操作系统", "文萃楼I404", ongoing = true),
            ),
        )
        assertEquals(
            "10:00 上操作系统（文萃楼I404）",
            StudySpotLogic.courseLine(
                StudySpotLogic.NextCourse(
                    "操作系统",
                    "文萃楼I404",
                    ongoing = false,
                    startsAt = LocalTime.of(10, 0),
                ),
            ),
        )
        // 拿不到开课钟点也不许说「正在上」
        assertEquals(
            "马上要上操作系统（文萃楼I404）",
            StudySpotLogic.courseLine(
                StudySpotLogic.NextCourse("操作系统", "文萃楼I404", ongoing = false),
            ),
        )
    }

    /** 离上课还早 / 今天的课都上完了 → 不给「有课」的结论。 */
    @Test
    fun `离上课还早或课都上完时不算有课`() {
        // 9:00 时，10:00 那节课还有一个小时
        assertNull(
            StudySpotLogic.currentCourseOf(
                courses = listOf(courseAt("操作系统", "文萃楼I404", 3, 3)),
                table = table,
                now = LocalTime.of(9, 0),
            ),
        )
        // 全天课程结束（第三节 10:50 下课后）
        assertNull(
            StudySpotLogic.currentCourseOf(
                courses = listOf(courseAt("操作系统", "文萃楼I404", 3, 3)),
                table = table,
                now = LocalTime.of(11, 30),
            ),
        )
        // 那天没课（调用方按星期几/周次/放假过滤后传空）
        assertNull(
            StudySpotLogic.currentCourseOf(
                courses = emptyList(),
                table = table,
                now = LocalTime.of(8, 20),
            ),
        )
    }

    // ============================================ 候选池：聚合 / 排序 / 文案 / 时长

    private fun free(
        id: String,
        name: String,
        count: Int,
        floor: Int? = null,
        ahead: Int = 0,
    ) = StudySpotLogic.BuildingFree(
        buildingId = id,
        buildingName = name,
        freeCount = count,
        floor = floor,
        freeSectionsAhead = ahead,
    )

    /**
     * ⚠️⚠️ **标题里的名字与数字必须同范围**。
     *
     * 服务端给的是**逐层**条目，一栋楼会来好几条。要是直接拿「3 楼那一条」当最优，
     * 而数字写的是楼的总数，用户跑去 3 楼会发现根本没有那么多 —— 数据是对的，
     * 界面说的是**另一件事**（「命中对了不代表展示对了」）。
     */
    @Test
    fun `多层楼的标题只说楼名而数量是楼的总数`() {
        val multi = StudySpotLogic.groupByBuilding(
            listOf(
                free("b1", "文萃楼I", 5, floor = 3),
                free("b1", "文萃楼I", 15, floor = 5),
            ),
        ).single()

        assertEquals(20, multi.freeCount)
        assertEquals(2, multi.floors.size)
        // 多的那层排前面，用于「单层才点名」的判断
        assertEquals(5, multi.floors.first().floor)
        assertEquals("文萃楼I现在有 20 间空教室", StudySpotLogic.candidateTitle(multi))

        // 只有一层有空时**才**点名楼层 —— 此时楼层数 = 楼的总数，两者范围一致
        val single = StudySpotLogic.groupByBuilding(listOf(free("b1", "文萃楼I", 12, floor = 3))).single()
        assertEquals("文萃楼I 3 楼现在有 12 间空教室", StudySpotLogic.candidateTitle(single))
    }

    /** 全满的楼不进候选（没什么可去的）。 */
    @Test
    fun `全满的楼不进候选`() {
        assertTrue(StudySpotLogic.rank(listOf(free("b1", "甲楼", 0))).isEmpty())
        // 有空的楼照样在
        assertEquals(1, StudySpotLogic.rank(listOf(free("b1", "甲楼", 0), free("b2", "乙楼", 1))).size)
    }

    /**
     * ⚠️ 就近加成**必须温和**：太大就会出现「近但只剩 2 间」压过「远但有 12 间」——
     * 那是拿一个没根据的前提（人愿意为了近去挤一间几乎满的教室）替用户做决定。
     */
    @Test
    fun `就近加成不会压过空教室明显更多的楼`() {
        val farWins = StudySpotLogic.rank(
            buildings = listOf(free("b1", "文萃楼I", 2), free("b2", "综教", 12)),
            lastClassroom = "文萃楼I404",
        )
        assertEquals("综教", farWins.first().buildingName)

        // 差得不多（3 间以内）时，同楼的那个胜出
        val nearWins = StudySpotLogic.rank(
            buildings = listOf(free("b1", "文萃楼I", 5), free("b2", "综教", 7)),
            lastClassroom = "文萃楼I404",
        )
        assertEquals("文萃楼I", nearWins.first().buildingName)
        assertEquals(true, nearWins.first().matchLastClass)
        assertEquals(false, nearWins[1].matchLastClass)
    }

    /**
     * ⚠️ 排序**必须确定**：并列时不能靠输入顺序（输入顺序来自并发请求，每次都可能不同），
     * 否则同一份数据两次排序给出不同的第一条，卡片上的推荐会自己跳。
     *
     * ⚠️ 并列时按楼名排 —— 那只是「**确定**」，不是「更好听」：
     *    Kotlin 的字符串比较是 UTF-16 码点序，`乙`(U+4E59) < `甲`(U+7532)，
     *    所以并列时「乙楼」在前。**别把它当成有意义的排序**去改。
     */
    @Test
    fun `并列时候选顺序稳定`() {
        val a = StudySpotLogic.rank(listOf(free("b1", "乙楼", 5), free("b2", "甲楼", 5)))
        val b = StudySpotLogic.rank(listOf(free("b2", "甲楼", 5), free("b1", "乙楼", 5)))

        assertEquals(listOf("乙楼", "甲楼"), a.map { it.buildingName })
        assertEquals(a.map { it.buildingName }, b.map { it.buildingName })
    }

    /** 教室名认楼要取**最长**前缀，否则「文萃楼I」会被认成隔壁的「文萃楼」。 */
    @Test
    fun `教室名认楼取最长前缀`() {
        val cs = StudySpotLogic.rank(listOf(free("b1", "文萃楼", 3), free("b2", "文萃楼I", 3)))

        assertEquals("文萃楼I", StudySpotLogic.buildingOf("文萃楼I404", cs))
        assertEquals("文萃楼", StudySpotLogic.buildingOf("文萃楼H101", cs))
        assertNull(StudySpotLogic.buildingOf("综合教学楼B301", cs))
        assertNull(StudySpotLogic.buildingOf(null, cs))
    }

    /**
     * ⚠️ 「上一节课的教室」有**时限**：上午 10 点就上完的课，下午拿它说「你在那儿附近」
     * 就是瞎猜。超了窗口返回 null ⇒ 不做这次推断。
     */
    @Test
    fun `上一节课的教室只在时限内才算数`() {
        val courses = listOf(courseAt("操作系统", "文萃楼I404", 3, 3))  // 10:00-10:50

        // 刚下课 5 分钟：认
        assertEquals("文萃楼I404", StudySpotLogic.recentClassroomOf(courses, table, LocalTime.of(10, 55)))
        // 正好在窗口边缘：还认
        assertEquals("文萃楼I404", StudySpotLogic.recentClassroomOf(courses, table, LocalTime.of(12, 20)))
        // 超过 90 分钟：不认（宁可给普通的「空教室最多」，也不给基于错前提的「就近」）
        assertNull(StudySpotLogic.recentClassroomOf(courses, table, LocalTime.of(12, 30)))
        // 还在上课（没有「上完」的课）
        assertNull(StudySpotLogic.recentClassroomOf(courses, table, LocalTime.of(10, 20)))
        // 取最近上完的那节
        assertEquals(
            "文萃楼I404",
            StudySpotLogic.recentClassroomOf(
                courses = listOf(courseAt("高数", "综教B301", 1, 1), courseAt("操作系统", "文萃楼I404", 3, 3)),
                table = table,
                now = LocalTime.of(10, 55),
            ),
        )
    }

    @Test
    fun `连续空闲节数`() {
        // 第 3 节占用 ⇒ 从第 1 节起只能连空 2 节
        assertEquals(2, StudySpotLogic.freeSectionsAhead("3", 1, 3))
        // 从第 3 节起算：第 3 节就占用 ⇒ 0
        assertEquals(0, StudySpotLogic.freeSectionsAhead("1,2,3", 3, 3))
        // null / 空串 = 全天空闲，不是数据缺失
        assertEquals(3, StudySpotLogic.freeSectionsAhead(null, 1, 3))
        assertEquals(3, StudySpotLogic.freeSectionsAhead("", 1, 3))
    }

    /**
     * 「能待多久」的措辞是「**有教室**空到 …」—— 它的来源是**各间教室里连续空得最久的那个**，
     * 只保证「至少有 1 间能一路坐到底」，**不保证**整栋楼/整层都空着。
     * 同一份数据换个主语，正确性就变了（写成「（这栋楼）空到 20:55」就是假的）。
     */
    @Test
    fun `空闲时长文案`() {
        // 8:20 在第 1 节（8:00-8:50），连空 2 节 ⇒ 到第 2 节结束 9:40
        assertEquals("有教室空到 09:40", StudySpotLogic.freeUntilText(table, LocalTime.of(8, 20), 2))
        // 连空节数超出作息表 ⇒ 截到最后一节，不能因为「后面没定义」就不给结论
        assertEquals("有教室空到 10:50", StudySpotLogic.freeUntilText(table, LocalTime.of(8, 20), 99))
        // 算不出来（0）⇒ 不做断言
        assertNull(StudySpotLogic.freeUntilText(table, LocalTime.of(8, 20), 0))
        // 当天课都上完了 ⇒ 没有「到几点」这回事
        assertNull(StudySpotLogic.freeUntilText(table, LocalTime.of(22, 0), 3))
        // 连时间表都没有
        assertNull(StudySpotLogic.freeUntilText(emptyList(), LocalTime.of(8, 20), 3))
    }

    /**
     * ⚠️⚠️ 空闲时长要按**到达时刻**算，不是「现在」。
     *
     * 正在上课的人**到不了** —— 拿「现在这一节」算出来的那个数是他在教室里的最后几分钟，
     * 跟他下课之后去哪儿毫无关系。
     */
    @Test
    fun `空闲时长按到达时刻算`() {
        // 比上面那张表多一节 13:20-14:10，这样「下课之后」才落到一个真实节次上
        val t = table + TimeTableItem(LocalTime.of(13, 20), LocalTime.of(14, 10))

        // 10:20 还在第 3 节里：连空 1 节 = 到 10:50（下课那一刻本身）
        assertEquals("有教室空到 10:50", StudySpotLogic.freeUntilText(t, LocalTime.of(10, 20), 1))
        // 10:50 下课后到达：那已经是第 4 节，能到 14:10 —— 这才是有用的那个数
        assertEquals("有教室空到 14:10", StudySpotLogic.freeUntilText(t, LocalTime.of(10, 50), 1))

        // [planOf] 的 `at` 必须是同一个时刻，否则标题说「下课后」、时长却按现在算
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(10, 50),
            table = t,
            course = null,
            booking = null,
            buildings = listOf(free("b1", "文萃楼I", 3, floor = 3, ahead = 1)),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("文萃楼I 3 楼现在有 3 间空教室", card.title)
        assertEquals("有教室空到 14:10", card.detail)
    }

    /** 空闲态卡片：标题 + 「为什么推荐 / 各层分布 / 还能空多久」。 */
    @Test
    fun `空闲态卡片把就近理由与楼层分布说清楚`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(
                free("b1", "文萃楼I", 5, floor = 3, ahead = 2),
                free("b1", "文萃楼I", 7, floor = 5, ahead = 1),
            ),
            lastClassroom = "文萃楼I404",
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals(StudySpotLogic.Kind.FREE_ROOM, card.kind)
        assertEquals("文萃楼I现在有 12 间空教室", card.title)
        // ⚠️ 理由只陈述**事实**（课表上写着「上一节在文萃楼I」），不说「你就在附近」那种推论
        assertEquals(
            "你上一节在文萃楼I上课 · 5 层 7 间 · 3 层 5 间 · 有教室空到 09:40",
            card.detail,
        )
    }

    /** 上课中的卡片：说清「几点下课后去哪儿」，且**不重复**说上一节在哪上。 */
    @Test
    fun `上课中卡片说下课后去哪儿`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = StudySpotLogic.NextCourse(
                name = "操作系统",
                classroom = "文萃楼I404",
                endsAt = LocalTime.of(8, 50),
            ),
            booking = null,
            buildings = listOf(free("b1", "文萃楼I", 12, floor = 3)),
            lastClassroom = "文萃楼I404",
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals("正在上操作系统（文萃楼I404）", card.title)
        assertEquals("08:50 下课后 · 去文萃楼I 3 楼（12 间空）", card.detail)
        assertTrue("正在上那节就在这栋楼，不该再说「你上一节在…」", card.detail?.contains("你上一节") != true)
    }

    /**
     * ⚠️⚠️ 有课但**一个候选都没取到**时，副文案必须是「没查到」，不能是「地点：X」。
     *
     * 这是**真机上抓到的**：某次逐栋拉空教室**全部失败**，卡片只剩
     * `正在上计算机系统导论（综教B301）` + `地点：综教B301` —— 同一件事说两遍，
     * 而**那栋楼其实空着**（去空教室页一查，文萃楼C 有 9 间空着）。
     *
     * ⇒ 两件事：
     * ① 不许把标题里已有的教室再说一遍
     * ② 降级**不许无声无息** —— 明说「暂未查到」，但**不声明「没有空教室」**
     *    （我们并不知道：只看了前 8 栋楼）
     */
    @Test
    fun `上课中取不到空教室时明说没查到`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = StudySpotLogic.NextCourse("操作系统", "文萃楼I404", endsAt = LocalTime.of(8, 50)),
            booking = null,
            buildings = emptyList(),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals(StudySpotLogic.Kind.IN_CLASS, card.kind)
        assertEquals("正在上操作系统（文萃楼I404）", card.title)
        assertEquals(StudySpotLogic.NO_CANDIDATE_HINT, card.detail)
        assertTrue("不许把标题里的教室再说一遍", card.detail?.contains("文萃楼I404") != true)
        assertTrue("不许断言「没有空教室」（我们并不知道）", card.detail?.contains("没有") != true)
    }

    /** 「换一个」= 本地换下标，转一圈回到第一条；越界也不许崩（候选数量刷新时会变）。 */
    @Test
    fun `换一个按下标循环`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(free("b1", "甲楼", 5), free("b2", "乙楼", 4), free("b3", "丙楼", 3)),
        )

        val titles = (0..3).map { StudySpotLogic.studySpotCardOf(plan, it)?.title }
        assertEquals(3, titles.distinct().size)
        // 转一圈回到第一条
        assertEquals(titles[0], titles[3])
        // 越界的下标（旧下标 + 刷新后候选变短）靠取模兜住 —— 不许崩
        assertEquals(titles[1], StudySpotLogic.studySpotCardOf(plan, 7)?.title)   // 7 % 3 = 1
        assertEquals(titles[2], StudySpotLogic.studySpotCardOf(plan, -1)?.title)  // 负数也要绕回
    }

    /** 「有预约」压过「空教室」：已经安排好了，别再推荐别的地方。 */
    @Test
    fun `有预约时不给空教室候选`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = StudySpotLogic.Booking("文萃楼I404", "13:20"),
            buildings = listOf(free("b1", "甲楼", 5)),
        )

        assertEquals(StudySpotLogic.Kind.HAS_BOOKING, plan.kind)
        assertTrue(plan.options.isEmpty())
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("你 13:20 在 文萃楼I404 有座位预约", card.title)
        assertEquals("已约好，直接去就行", card.detail)
    }

    /** 什么都取不到（全满）时 `hasContent` 为 false ⇒ 整张卡不渲染，另一条路照常给。 */
    @Test
    fun `没有任何内容时不显示卡片`() {
        val empty = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(free("b1", "甲楼", 0)),
        )
        assertEquals(false, empty.hasContent)
        assertNull(StudySpotLogic.studySpotCardOf(empty, 0))
    }

    // ===================================================== 详情页（点卡片进去看）

    /** 详情页把**整排**候选摊开，并且只有一个带「推荐」。 */
    @Test
    fun `详情页摊开全部候选`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(
                free("b1", "文萃楼I", 7, floor = 5, ahead = 2),
                free("b1", "文萃楼I", 5, floor = 3, ahead = 2),
                free("b2", "综教", 9),
            ),
            lastClassroom = "文萃楼I404",
        )
        val detail = StudySpotLogic.detailOf(plan)

        assertEquals(StudySpotLogic.Kind.FREE_ROOM, detail.kind)
        assertEquals("现在没课", detail.headline)
        assertEquals("按空教室多少排序", detail.subline)
        assertNull(detail.emptyHint)
        assertEquals(2, detail.rows.size)

        // 排序：文萃楼I = 12 间 + 同楼加成 3 = 15 > 综教 9
        val first = detail.rows[0]
        assertEquals("文萃楼I", first.place)
        assertEquals(12, first.freeCount)
        assertEquals("12 间空教室", first.countText)
        assertEquals("你上一节在文萃楼I上课", first.reason)
        assertEquals("5 层 7 间 · 3 层 5 间 · 有教室空到 09:40", first.detail)
        assertEquals(true, first.top)

        assertEquals("综教", detail.rows[1].place)
        assertEquals(false, detail.rows[1].top)
        // 「推荐」只标一个 —— 标一排等于没标
        assertEquals(1, detail.rows.count { it.top })
    }

    /**
     * ⚠️ 详情页与卡片必须是**同一份**数据。
     *
     * 两边各算一遍迟早对不上（点进去看到的数 ≠ 卡片上的数），那是最难解释的一类 bug。
     * 这里直接断言「行里的地点 + 数量」拼起来等于卡片标题。
     */
    @Test
    fun `详情页第一行与卡片说的是同一处`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(free("b1", "甲楼", 5), free("b2", "乙楼", 4)),
            lastClassroom = "甲楼101",
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        val first = StudySpotLogic.detailOf(plan).rows.first()

        assertEquals("甲楼现在有 5 间空教室", card.title)
        assertEquals(card.title, "${first.place}现在有 ${first.countText}")
        assertEquals(plan.options.size, StudySpotLogic.detailOf(plan).rows.size)
    }

    /** 有课时头部要说清「几点下课后」；一行候选都没有时给「没查到」而不是留白。 */
    @Test
    fun `详情页有课时说清几点下课后`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = StudySpotLogic.NextCourse("操作系统", "文萃楼I404", endsAt = LocalTime.of(8, 50)),
            booking = null,
            buildings = emptyList(),
        )
        val detail = StudySpotLogic.detailOf(plan)

        assertEquals(StudySpotLogic.Kind.IN_CLASS, detail.kind)
        assertEquals("正在上操作系统（文萃楼I404）", detail.headline)
        assertEquals("08:50 下课后可以去这些地方", detail.subline)
        assertTrue(detail.rows.isEmpty())
        assertEquals(StudySpotLogic.NO_CANDIDATE_HINT, detail.emptyHint)
    }

    /** 空闲态一行都没有时，说明也**只声明「没查到」**（我们并不知道是不是真没有）。 */
    @Test
    fun `详情页空闲态没有候选时也说没查到`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            buildings = listOf(free("b1", "甲楼", 0)),
        )
        val detail = StudySpotLogic.detailOf(plan)

        assertEquals("现在没课", detail.headline)
        assertEquals(StudySpotLogic.NO_CANDIDATE_HINT_NOW, detail.emptyHint)
        assertTrue("不许断言「没有空教室」", detail.emptyHint?.contains("没有") != true)
    }
}
