package cn.bit101.android.data.school

import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.TimeTableItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 一间教室的原始事实（教室级候选的输入）。 */
    private fun room(
        id: String,
        building: String,
        name: String,
        floor: Int? = null,
        ahead: Int = 0,
        inBuilding: Int = 1,
    ) = StudySpotLogic.RoomFree(
        buildingId = id,
        buildingName = building,
        classroomName = name,
        floor = floor,
        freeSectionsAhead = ahead,
        buildingFreeCount = inBuilding,
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
     * ⚠️ 「能待多久」只返回**钟点**，整句话由调用方按「它当时在说哪一级」拼：
     * 教室级是「文萃楼C 501a 空到 20:55」，楼级是「有教室空到 20:55」。
     * 把措辞塞进这个函数里，迟早有一个调用方把主语说错 ——
     * 而同一份数据换个主语，正确性就变了。
     */
    @Test
    fun `空闲时长只给钟点`() {
        // 8:20 在第 1 节（8:00-8:50），连空 2 节 ⇒ 到第 2 节结束 9:40
        assertEquals("09:40", StudySpotLogic.freeUntilTime(table, LocalTime.of(8, 20), 2))
        // 连空节数超出作息表 ⇒ 截到最后一节，不能因为「后面没定义」就不给结论
        assertEquals("10:50", StudySpotLogic.freeUntilTime(table, LocalTime.of(8, 20), 99))
        // 算不出来（0）⇒ 不做断言
        assertNull(StudySpotLogic.freeUntilTime(table, LocalTime.of(8, 20), 0))
        // 当天课都上完了 ⇒ 没有「到几点」这回事
        assertNull(StudySpotLogic.freeUntilTime(table, LocalTime.of(22, 0), 3))
        // 连时间表都没有
        assertNull(StudySpotLogic.freeUntilTime(emptyList(), LocalTime.of(8, 20), 3))
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
        assertEquals("10:50", StudySpotLogic.freeUntilTime(t, LocalTime.of(10, 20), 1))
        // 10:50 下课后到达：那已经是第 4 节，能到 14:10 —— 这才是有用的那个数
        assertEquals("14:10", StudySpotLogic.freeUntilTime(t, LocalTime.of(10, 50), 1))

        // [planOf] 的 `at` 必须是同一个时刻，否则标题说「下课后」、时长却按现在算
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(10, 50),
            table = t,
            course = null,
            booking = null,
            rooms = listOf(room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 2, inBuilding = 3)),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("文萃楼I 302，空到 14:10", card.title)
        assertEquals("3 层 · 这栋楼还有 3 间", card.detail)
    }

    /**
     * ⚠️⚠️ **当天的课都上完时**，不能再说含糊的「现在空着」。
     *
     * 真机（2026-10-12 22:29）实测：卡片是 `文萃楼B 129`、副文案 `1 层 · 这栋楼还有 11 间`，
     * 详情页每行却写「现在空着」，而**空教室页**对同一间教室写的是「空闲到明天」
     * —— 同一个事实两种说法；更要命的是「现在空着」会让人以为马上就得走，
     * 其实当天已经没有课了，可以一直待下去。
     */
    @Test
    fun `当天的课上完了就说空闲到明天`() {
        assertTrue(StudySpotLogic.isDayOver(table, LocalTime.of(12, 0)))

        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(room("b1", "文萃楼B", "文萃楼B129", floor = 1, ahead = 0, inBuilding = 11)),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("文萃楼B 129，空闲到明天", card.title)
        assertEquals("1 层 · 这栋楼还有 11 间", card.detail)

        // 详情页必须与卡片口径一致（铁律 0.1：两边该一致处必补「两边一致」断言）
        assertEquals(StudySpotLogic.DAY_OVER_TEXT, StudySpotLogic.detailOf(plan).rows.first().untilText)
    }

    /**
     * 反向钉住：还在作息表里时**不许**说「空闲到明天」（那会让人以为今天没课了）。
     *
     * ⚠️ 「没发生 X」的断言必须先把 X 的前提桩上，否则会假通过 —— 这条就是上一条的守卫。
     * ⚠️ 边界：恰好等于最后一节**结束**时刻（10:50）就已经算「上完了」。
     */
    @Test
    fun `还在作息表内时不说空闲到明天`() {
        assertFalse(StudySpotLogic.isDayOver(table, LocalTime.of(8, 20)))
        assertFalse(StudySpotLogic.isDayOver(table, LocalTime.of(10, 49)))
        assertTrue(StudySpotLogic.isDayOver(table, LocalTime.of(10, 50)))

        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(room("b1", "文萃楼B", "文萃楼B129", floor = 1, ahead = 3, inBuilding = 11)),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("文萃楼B 129，空到 10:50", card.title)
        assertEquals("空到 10:50", StudySpotLogic.detailOf(plan).rows.first().untilText)
    }

    /** 空闲态卡片：标题只谈**一间教室**，副文案给距离 / 楼层 / 这栋楼还有几间。 */
    @Test
    fun `空闲态卡片的主语是一间教室`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(
                room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 2, inBuilding = 5),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals(StudySpotLogic.Kind.FREE_ROOM, card.kind)
        // ⚠️ 「空到 09:40」说的是**这一间**（时间的主语与地点同范围）
        assertEquals("文萃楼I 302，空到 09:40", card.title)
        // ⚠️ 距离那句话**必须带主语**（你上一节课），否则用户不知道跟哪儿比
        assertEquals("就在你上一节课那栋楼 · 3 层 · 这栋楼还有 5 间", card.detail)
    }

    /** 教室名 → 房间号 + 房间名后缀（真机样例）。 */
    @Test
    fun `教室名取房间号与房间名后缀`() {
        // 房间号里的字母是它的一部分（同一间大房分成 a/b/c/d，是真实存在的教室）
        assertEquals("501a", StudySpotLogic.roomLabelOf("文萃楼C501a", "文萃楼C"))
        assertEquals("805", StudySpotLogic.roomLabelOf("文萃楼C805", "文萃楼C"))
        // 带房间名的：房间号是楼名之后**第一段英文/数字**，后缀原样留着
        assertEquals("125", StudySpotLogic.roomLabelOf("文萃楼H125良乡二号机房", "文萃楼H"))
        assertEquals("良乡二号机房", StudySpotLogic.roomKindOf("文萃楼H125良乡二号机房", "文萃楼H"))
        // 没有后缀时不给编一个
        assertNull(StudySpotLogic.roomKindOf("文萃楼C501a", "文萃楼C"))
        // ⚠️ 楼名给的是**简写**（少一个区号字母）时，区号会留在房间号里 ——
        //    那是**对的**：宁可显示「文萃楼 C501a」，也不能把 C 丢了
        assertEquals("C501a", StudySpotLogic.roomLabelOf("文萃楼C501a", "文萃楼"))
        // ⚠️⚠️ 接口**教室名用简称、楼名用全称**（真机实测）：`理教楼203` 配 `理科教学大楼`。
        //    开头那三个字（简称）必须丢掉，否则界面会变成「理科教学大楼 理教楼203」——楼名说两遍
        assertEquals("203", StudySpotLogic.roomLabelOf("理教楼203", "理科教学大楼"))
        assertNull(StudySpotLogic.roomKindOf("理教楼203", "理科教学大楼"))
        assertEquals("B301", StudySpotLogic.roomLabelOf("综教B301", "综合教学大楼"))
        assertNull(StudySpotLogic.roomKindOf("综教B301", "综合教学大楼"))
        // 解析不出房间号时**原样返回**，不硬编一个号
        assertEquals("文萃楼C", StudySpotLogic.roomLabelOf("文萃楼C", "文萃楼C"))
    }

    /** 「文萃楼C 501a」这种展示名。 */
    @Test
    fun `教室展示名是楼名加房间号`() {
        assertEquals(
            "文萃楼C 501a",
            StudySpotLogic.roomPlace(room("b1", "文萃楼C", "文萃楼C501a")),
        )
        // 教室名用简称时，只显示「楼名 + 房间号」（不再重复一遍简称）
        assertEquals(
            "理科教学大楼 203",
            StudySpotLogic.roomPlace(room("b1", "理科教学大楼", "理教楼203")),
        )
        // 房间号取不出来时只说楼名（**不硬编一个号**）
        assertEquals("文萃楼C", StudySpotLogic.roomPlace(room("b1", "文萃楼C", "文萃楼C")))
    }

    /**
     * ⚠️ 同一栋楼的两种叫法必须认成**同一处**。
     *
     * 真机证据（2026-10-12）：课表里写的是「理教楼406」，而空教室接口给的楼名是
     * 「理科教学大楼」—— 用字符串前缀比对**一对都认不出来**，
     * 于是「用户正在这栋楼上自习却推荐不到它」。别名表是唯一认得全两边的地方。
     */
    @Test
    fun `理科教学楼的几种写法认成同一处`() {
        assertEquals("理科教学楼", CampusLocations.match("理教楼406")?.name)
        assertEquals("理科教学楼", CampusLocations.match("理科教学大楼")?.name)
        assertEquals("理科教学楼", CampusLocations.match("理科教学楼")?.name)
        assertEquals("理科教学楼", CampusLocations.match("理科楼")?.name)
        // ⚠️ 别把隔壁的「综合教学大楼」吞进来（两边都带「教学大楼」四个字）
        assertEquals("综合教学大楼", CampusLocations.match("综教B301")?.name)
        // ⚠️⚠️ 这里**必须写接口的真实楼名**：「综合教学楼大楼A / B」
        //    （2026-10-13 真机 dump 核实：空教室页楼列表第 13/14 项就是它们）。
        //    旧断言写的是「综合教学大楼A」—— 那个写法**在真实数据里根本不存在**
        //    （比真名少一个「楼」字）⇒ 测试全绿，而这栋楼在真机上**永远算不出距离**、
        //    也永远不被认成「用户自己的楼」。这正是铁律 0.7：期望值按想当然写死的假通过。
        assertEquals("综合教学大楼", CampusLocations.match("综合教学楼大楼A")?.name)
        assertEquals("综合教学大楼", CampusLocations.match("综合教学楼大楼B")?.name)
        // 理学楼也不该被吞（那是南邻的另一组三栋楼）
        assertEquals("理学楼A", CampusLocations.match("理学楼A305")?.name)
    }

    /**
     * ⚠️ 候选**总数**要封顶。
     *
     * 同楼限流只管住「一栋楼刷屏」，管不住「楼多」—— 拉取上限放宽到 16 栋之后，
     * 真机上立刻出现 `1/27`：「换一个」要按 27 次，没人会按。
     */
    @Test
    fun `候选总数封顶`() {
        // 10 栋楼各 1 间（互不同楼 ⇒ 同楼限流不生效），只靠总数封顶
        val rooms = (1..10).map { i -> room("b$i", "楼$i", "楼${i}01", ahead = 3, inBuilding = 1) }
        assertEquals(StudySpotLogic.MAX_ROOM_CANDIDATES, StudySpotLogic.rankRooms(rooms).size)
    }

    /**
     * ⚠️⚠️ 硬门槛：**知道**一间教室只空 1 节就必须挡掉 —— 坐下就该走了。
     *
     * ⚠️ 但 `0`（**算不出来**）不挡：把「不知道」当成「短」是一种假结论，
     *    它分数本来就低、会排到最后。
     */
    @Test
    fun `空一节的教室被门槛挡掉但算不出的不挡`() {
        val rooms = listOf(
            room("b1", "甲楼", "甲楼101", ahead = 1, inBuilding = 1),
            room("b2", "乙楼", "乙楼201", ahead = 2, inBuilding = 1),
            room("b3", "丙楼", "丙楼301", ahead = 0, inBuilding = 1),
        )
        val ranked = StudySpotLogic.rankRooms(rooms)

        assertEquals(listOf("乙楼", "丙楼"), ranked.map { it.room.buildingName })
        assertTrue("空 1 节的必须被挡掉", ranked.none { it.room.buildingName == "甲楼" })
    }

    /** ⚠️ 同一栋楼最多出 2 间 —— 不限的话一栋楼会刷满整个列表，用户没法比较楼之间。 */
    @Test
    fun `同一栋楼最多出两间`() {
        val rooms = listOf(
            room("b1", "文萃楼C", "文萃楼C501a", ahead = 5, inBuilding = 4),
            room("b1", "文萃楼C", "文萃楼C501b", ahead = 5, inBuilding = 4),
            room("b1", "文萃楼C", "文萃楼C501c", ahead = 5, inBuilding = 4),
            room("b1", "文萃楼C", "文萃楼C501d", ahead = 5, inBuilding = 4),
            room("b2", "文萃楼H", "文萃楼H102", ahead = 3, inBuilding = 1),
        )
        val ranked = StudySpotLogic.rankRooms(rooms)

        assertEquals(2, ranked.count { it.room.buildingName == "文萃楼C" })
        assertEquals(1, ranked.count { it.room.buildingName == "文萃楼H" })
        // 同楼里留的是**能待最久 / 名字靠前**的那两间（确定性）
        assertEquals(
            listOf("文萃楼C501a", "文萃楼C501b"),
            ranked.filter { it.room.buildingName == "文萃楼C" }.map { it.room.classroomName },
        )
    }

    /**
     * ⚠️ 排序里「能待多久」有**封顶**：空 8 节和空 6 节对自习的人差不多，
     *    不封顶的话时长会压死距离，而「走过去要多久」同样是决定性的。
     */
    @Test
    fun `能待得久与离得近都会加分`() {
        // 远的能待久 vs 近的能待短：封顶后差距只有 1 节，同楼加成 2 节 ⇒ 近的胜出
        val ranked = StudySpotLogic.rankRooms(
            rooms = listOf(
                room("b1", "甲楼", "甲楼101", ahead = 9, inBuilding = 1),
                room("b2", "文萃楼I", "文萃楼I302", ahead = 5, inBuilding = 1),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        assertEquals("文萃楼I302", ranked.first().room.classroomName)
        assertEquals(CampusLocations.Distance.SAME, ranked.first().distance)
        // 差得多的时候仍然是「能待久」的赢
        val ranked2 = StudySpotLogic.rankRooms(
            rooms = listOf(
                room("b1", "甲楼", "甲楼101", ahead = 9, inBuilding = 1),
                room("b2", "文萃楼I", "文萃楼I302", ahead = 2, inBuilding = 1),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        assertEquals("甲楼101", ranked2.first().room.classroomName)
    }

    /**
     * ⚠️⚠️ **打分全部并列时**，不能再落到楼名字典序。
     *
     * 真机（2026-10-12 22:29）实测：晚上没有参考点、又过了最后一节 ⇒ 打分**全是 0** ⇒
     * 排序退化成按楼名排，候选成了「文萃楼B/C/E/F」这种**按拼音排出来的结果**，
     * 用户课表里的文萃楼I 反而进不了前 8。
     * ⇒ 并列时优先「这栋楼空着几间」—— 那是**真信息**：空得多的楼，走过去更可能有位子。
     */
    @Test
    fun `打分全部并列时优先这栋楼空得多的`() {
        // ⚠️ 刻意选「楼名 E < F」的一对：字典序会让 E 在前，
        //    所以这一条能证明起作用的是**新规则**，不是碰巧和字典序一致
        val ranked = StudySpotLogic.rankRooms(
            rooms = listOf(
                room("bE", "文萃楼E", "文萃楼E201", ahead = 0, inBuilding = 2),
                room("bF", "文萃楼F", "文萃楼F101", ahead = 0, inBuilding = 33),
            ),
        )
        assertEquals("文萃楼F", ranked.first().room.buildingName)

        // 换过来也一样：与楼名字典序无关，只看谁空得多
        val ranked2 = StudySpotLogic.rankRooms(
            rooms = listOf(
                room("bF", "文萃楼F", "文萃楼F101", ahead = 0, inBuilding = 1),
                room("bE", "文萃楼E", "文萃楼E201", ahead = 0, inBuilding = 40),
            ),
        )
        assertEquals("文萃楼E", ranked2.first().room.buildingName)
    }

    /** 有参考点时打分本身分得开 —— 「空几间」只在**并列**时兜底，不得喧宾夺主。 */
    @Test
    fun `有参考点时仍然是打分高的优先`() {
        val ranked = StudySpotLogic.rankRooms(
            rooms = listOf(
                // 这栋楼空 33 间，但在别处
                room("bF", "文萃楼F", "文萃楼F101", ahead = 0, inBuilding = 33),
                // 只空 2 间，但就在用户上一节课那栋楼 ⇒ 有「同一栋楼」加成
                room("bI", "文萃楼I", "文萃楼I302", ahead = 0, inBuilding = 2),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        assertEquals("文萃楼I302", ranked.first().room.classroomName)
    }

    /** 距离档位与文案（坐标表是真的，但精度只到 ~31 米 ⇒ **只给档位**）。 */
    @Test
    fun `距离分档与文案`() {
        // 同一栋楼
        assertEquals(
            CampusLocations.Distance.SAME,
            CampusLocations.distanceOf(CampusLocations.distanceMeters(
                CampusLocations.find("文萃楼I"), CampusLocations.find("文萃楼I"),
            )),
        )
        // 文萃楼I → 文萃楼H 算出约 31 米：那是**同一栋楼的不同翼**，必须算「同一栋楼」
        assertEquals(
            CampusLocations.Distance.SAME,
            CampusLocations.distanceOf(CampusLocations.distanceMeters(
                CampusLocations.find("文萃楼I"), CampusLocations.find("文萃楼H"),
            )),
        )
        // 文萃楼I → 综合教学大楼 约 216 米
        assertEquals(
            CampusLocations.Distance.NEAR,
            CampusLocations.distanceOf(CampusLocations.distanceMeters(
                CampusLocations.find("文萃楼I"), CampusLocations.find("综合教学大楼"),
            )),
        )
        // 文萃楼I → 工业生态楼 约 833 米
        assertEquals(
            CampusLocations.Distance.FAR,
            CampusLocations.distanceOf(CampusLocations.distanceMeters(
                CampusLocations.find("文萃楼I"), CampusLocations.find("工业生态楼"),
            )),
        )
        // 算不出来就是 null，**不猜**
        assertNull(CampusLocations.distanceOf(null))
        assertNull(CampusLocations.distanceMeters(null, CampusLocations.find("文萃楼I")))
        // 跨校区不给距离
        assertNull(
            CampusLocations.distanceMeters(
                CampusLocations.find("文萃楼I"), CampusLocations.find("主楼"),
            ),
        )

        // 文案必须带**主语**（跟哪儿比）
        assertEquals("就在你上一节课那栋楼", StudySpotLogic.distanceText(CampusLocations.Distance.SAME, "你上一节课"))
        assertEquals("紧挨着你下节课那栋楼", StudySpotLogic.distanceText(CampusLocations.Distance.NEXT_DOOR, "你下节课"))
        assertEquals("距你下节课那栋楼挺远", StudySpotLogic.distanceText(CampusLocations.Distance.FAR, "你下节课"))
        assertNull(StudySpotLogic.distanceText(null, "你下节课"))
    }

    /** ⚠️ 参考点或楼坐标缺一，就**不说距离** —— 宁可不说，也不给假距离。 */
    @Test
    fun `算不出距离时不说距离`() {
        // 前沿楼不在坐标表里（真机课表里出现过「前沿楼 238」）
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(room("b1", "前沿楼", "前沿楼238", floor = 2, ahead = 3, inBuilding = 1)),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals("前沿楼 238，空到 10:50", card.title)
        assertEquals("2 层 · 这栋楼还有 1 间", card.detail)
        assertTrue("不认识楼就不该出现距离", card.detail?.contains("那栋楼") != true)
    }

    /** 上课中的卡片：说清「几点下课后去**哪一间**」，距离要带主语。 */
    @Test
    fun `上课中卡片说下课后去哪一间`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = StudySpotLogic.NextCourse(
                name = "操作系统",
                classroom = "文萃楼I404",
                endsAt = LocalTime.of(8, 50),
            ),
            booking = null,
            rooms = listOf(room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 2, inBuilding = 12)),
            reference = StudySpotLogic.Reference("文萃楼I404", "你现在上课"),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!

        assertEquals("正在上操作系统（文萃楼I404）", card.title)
        // ⚠️ 距离那句话的主语是**你现在上课**（正在上的那节就在这栋楼），不是「你上一节课」
        assertEquals(
            "08:50 下课后 · 去文萃楼I 302（空到 09:40） · 就在你现在上课那栋楼 · 3 层 · 这栋楼还有 12 间",
            card.detail,
        )
        assertTrue("正在上那节就在这栋楼，不该说成「你上一节课」", card.detail?.contains("你上一节课") != true)
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
            rooms = emptyList(),
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
            rooms = listOf(
                room("b1", "甲楼", "甲楼101", ahead = 4, inBuilding = 1),
                room("b2", "乙楼", "乙楼201", ahead = 3, inBuilding = 1),
                room("b3", "丙楼", "丙楼301", ahead = 2, inBuilding = 1),
            ),
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
            rooms = listOf(room("b1", "甲楼", "甲楼101", ahead = 4, inBuilding = 1)),
        )

        assertEquals(StudySpotLogic.Kind.HAS_BOOKING, plan.kind)
        assertTrue(plan.options.isEmpty())
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        assertEquals("你 13:20 在 文萃楼I404 有座位预约", card.title)
        assertEquals("已约好，直接去就行", card.detail)
    }

    /**
     * ⚠️⚠️ 空闲态**一间候选都没查到，也必须出卡**。
     *
     * 这条测试以前断言的是相反的行为（`hasContent == false` + 卡片为 `null`）——
     * 而那正是 2026-10-12 真机抓到的缺陷：取数失败 ⇒ **整块区域静默消失**，
     * 界面看起来完全正常，用户分不清「没查到数据」和「功能坏了」；
     * 详情页那句兜底文案也因此**永远够不着**（详情页唯一的入口就是这张卡）。
     * ⇒ 「能不能显示」与「有没有候选」**是两件事**，后者靠文案表达。
     */
    @Test
    fun `空闲态一间候选都没有也要出卡并说清没查到`() {
        val empty = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            rooms = emptyList(),
        )
        assertTrue(empty.options.isEmpty())
        // 没有候选 ≠ 不许显示
        assertTrue(empty.hasContent)

        val card = StudySpotLogic.studySpotCardOf(empty, 0)!!
        assertEquals(StudySpotLogic.Kind.FREE_ROOM, card.kind)
        assertEquals("现在没课", card.title)
        // ⚠️ 只声明「没查到」，**不声明「没有空教室」**（我们只看了前 MAX_BUILDINGS 栋）
        assertEquals("暂未查到空教室", card.detail)
    }

    /** 无候选时详情页也够得着 —— 以前这句兜底永远进不去（入口就是那张卡）。 */
    @Test
    fun `空闲态无候选时详情页给出没查到的说明`() {
        val empty = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            rooms = emptyList(),
        )

        val detail = StudySpotLogic.detailOf(empty)
        assertTrue(detail.rows.isEmpty())
        assertEquals("暂未查到空教室", detail.emptyHint)
    }

    /**
     * 同状态在卡片与详情页上**必须说同一句话**。
     *
     * ⚠️ 铁律 0.1：两边各写一个字面量迟早漂移，用户会以为出了两个错。这里钉住「两边一致」。
     */
    @Test
    fun `空闲态的标题在卡片与详情页上一致`() {
        val empty = StudySpotLogic.planOf(
            at = LocalTime.of(12, 0),
            table = table,
            course = null,
            booking = null,
            rooms = emptyList(),
        )

        val card = StudySpotLogic.studySpotCardOf(empty, 0)!!
        assertEquals(StudySpotLogic.FREE_HEADLINE, card.title)
        assertEquals(StudySpotLogic.FREE_HEADLINE, StudySpotLogic.detailOf(empty).headline)
    }

    // ===================================================== 详情页（点卡片进去看）

    /** 详情页把**整排教室**摊开，并且只有一个带「推荐」。 */
    @Test
    fun `详情页摊开教室并标出推荐`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(
                room("b2", "综合教学大楼", "综合教学大楼B301", floor = 3, ahead = 3, inBuilding = 9),
                room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 2, inBuilding = 5),
                room("b1", "文萃楼I", "文萃楼I404", floor = 4, ahead = 2, inBuilding = 5),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        val detail = StudySpotLogic.detailOf(plan)

        assertEquals(StudySpotLogic.Kind.FREE_ROOM, detail.kind)
        assertEquals("现在没课", detail.headline)
        assertEquals("按能待多久、离你多近排的", detail.subline)
        assertNull(detail.emptyHint)
        assertEquals(3, detail.rows.size)

        // 打分：文萃楼I302 = 2 节 + 同楼 2 = 4 > 综教B301 = 3 节 ⇒ 同楼的那间胜出
        val first = detail.rows[0]
        assertEquals("文萃楼I 302", first.place)
        assertEquals("空到 09:40", first.untilText)
        assertEquals("就在你上一节课那栋楼 · 3 层 · 这栋楼还有 5 间", first.detail)
        assertEquals(true, first.top)

        // 同分（文萃楼I404 与 302 完全同分）时按教室名确定性排序
        assertEquals("文萃楼I 404", detail.rows[1].place)
        assertEquals(false, detail.rows[1].top)

        // 另一栋楼：能待更久（3 节 ⇒ 10:50）但离得远
        assertEquals("综合教学大楼 B301", detail.rows[2].place)
        assertEquals("空到 10:50", detail.rows[2].untilText)
        assertEquals("距你上一节课那栋楼一段路 · 3 层 · 这栋楼还有 9 间", detail.rows[2].detail)

        // 「推荐」只标一个 —— 标一排等于没标
        assertEquals(1, detail.rows.count { it.top })
    }

    /**
     * ⚠️ 详情页与卡片必须是**同一份**数据。
     *
     * 两边各算一遍迟早对不上（点进去看到的 ≠ 卡片上的），那是最难解释的一类 bug。
     * 这里直接断言「详情页行的地点 + 钟点」拼起来等于卡片标题、副文案也一致。
     */
    @Test
    fun `详情页第一行与卡片说的是同一处`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = null,
            booking = null,
            rooms = listOf(
                room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 3, inBuilding = 2),
                room("b2", "文萃楼H", "文萃楼H102", floor = 1, ahead = 2, inBuilding = 1),
            ),
            reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        val detail = StudySpotLogic.detailOf(plan)
        val first = detail.rows.first()

        assertEquals("文萃楼I 302，空到 10:50", card.title)
        assertEquals(card.title, "${first.place}，${first.untilText}")
        assertEquals(card.detail, first.detail)
        assertEquals(plan.options.size, detail.rows.size)
    }

    /** 有课时头部要说清「几点下课后」；一行候选都没有时给「没查到」而不是留白。 */
    @Test
    fun `详情页有课时说清几点下课后`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(8, 20),
            table = table,
            course = StudySpotLogic.NextCourse("操作系统", "文萃楼I404", endsAt = LocalTime.of(8, 50)),
            booking = null,
            rooms = emptyList(),
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
            rooms = emptyList(),
        )
        val detail = StudySpotLogic.detailOf(plan)

        assertEquals("现在没课", detail.headline)
        assertEquals(StudySpotLogic.NO_CANDIDATE_HINT_NOW, detail.emptyHint)
        assertTrue("不许断言「没有空教室」", detail.emptyHint?.contains("没有") != true)
    }

    // ============================================ 2026-10-13 自检修掉的三处

    /**
     * ⚠️⚠️ **认楼不能只比前缀** —— 「理教楼406」与接口楼名「理科教学大楼」前缀
     * **一对都认不出来**，只有坐标表的别名能把两边映射到同一个地点。
     *
     * `DefaultStudySpotRepository.pickBuildings` 的注释里已经因为同一个原因栽过一次
     * （用户正在理科教学楼上课，推荐里一间理科教学楼的教室都没有），
     * 可 [StudySpotLogic.buildingOf] 当时**仍然只比前缀** ⇒
     * 楼级那条路（[StudySpotLogic.rank] 的「同楼加成」）对这些楼**从来没生效过**。
     */
    @Test
    fun `认楼要认得两种叫法而不只是前缀`() {
        val cs = StudySpotLogic.rank(
            listOf(free("b1", "理科教学大楼", 9), free("b2", "综合教学楼大楼B", 7)),
        )

        // 前缀比对一对都认不出来，别名表认得出来
        assertEquals("理科教学大楼", StudySpotLogic.buildingOf("理教楼406", cs))
        assertEquals("综合教学楼大楼B", StudySpotLogic.buildingOf("综教B301", cs))
        // 认不出来的楼仍然返回 null（**不猜**）—— 「前沿楼」不在坐标表里
        assertNull(StudySpotLogic.buildingOf("前沿楼101", cs))
        // 取最长前缀那条老规矩不能被别名路径弄丢
        val cs2 = StudySpotLogic.rank(listOf(free("b1", "文萃楼", 3), free("b2", "文萃楼I", 3)))
        assertEquals("文萃楼I", StudySpotLogic.buildingOf("文萃楼I404", cs2))
    }

    /**
     * ⚠️ **没有参考点时不许说「离你多近排的」**。
     *
     * 那时打分全靠时长、排序退化成「这栋楼空几间」，距离**根本没参与**。
     * 副标题是一句对用户的**承诺**，承诺一个不在场的依据正是铁律 0.4 要防的事
     * （真机实测：夜里 00:2x 打开，候选按「这栋楼还有 N 间」降序，而副标题还写着「离你多近」）。
     */
    @Test
    fun `没有参考点时副标题不提距离`() {
        val rooms = listOf(
            room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 3, inBuilding = 2),
            room("b2", "文萃楼H", "文萃楼H102", floor = 1, ahead = 2, inBuilding = 1),
        )

        val noRef = StudySpotLogic.detailOf(
            StudySpotLogic.planOf(
                at = LocalTime.of(8, 20), table = table,
                course = null, booking = null, rooms = rooms,
            ),
        )
        assertEquals("按能待多久、这栋楼空几间排的", noRef.subline)
        // 反向守卫：一行都不许出现距离文案（否则「没有参考点」这个前提就被破坏了）
        assertTrue(noRef.rows.none { it.detail?.contains("那栋楼") == true })

        // 有参考点时距离**真的**参与了打分（同楼加成）⇒ 那句话才成立
        val withRef = StudySpotLogic.detailOf(
            StudySpotLogic.planOf(
                at = LocalTime.of(8, 20), table = table,
                course = null, booking = null, rooms = rooms,
                reference = StudySpotLogic.Reference("文萃楼I404", "你上一节课"),
            ),
        )
        assertEquals("按能待多久、离你多近排的", withRef.subline)
    }

    /**
     * ⚠️ **「能待到什么时候」的三态在卡片与详情页必须同一优先级**。
     *
     * 曾经：详情页那行说「空闲到明天」，而卡片对**同一间**教室要么说「（空到 20:55）」、
     * 要么干脆不提 —— 同一份数据两种说法，用户会以为出了两个错（铁律 0.1）。
     * 这里钉住「正在上今天最后一节课」这个场景。
     */
    @Test
    fun `上课中且今天没课了时卡片也说空闲到明天`() {
        val plan = StudySpotLogic.planOf(
            at = LocalTime.of(10, 50),   // 最后一节 10:00-10:50 刚好下课
            table = table,
            course = StudySpotLogic.NextCourse(
                "操作系统", "文萃楼I404", endsAt = LocalTime.of(10, 50),
            ),
            booking = null,
            rooms = listOf(room("b1", "文萃楼I", "文萃楼I302", floor = 3, ahead = 3, inBuilding = 2)),
        )
        val card = StudySpotLogic.studySpotCardOf(plan, 0)!!
        val row = StudySpotLogic.detailOf(plan).rows.first()

        assertEquals(StudySpotLogic.DAY_OVER_TEXT, row.untilText)
        assertTrue(
            "卡片也要说「空闲到明天」，实际是：${card.detail}",
            card.detail?.contains(StudySpotLogic.DAY_OVER_TEXT) == true,
        )
    }

    /**
     * ⚠️⚠️ **回归守卫：接口的真实楼名必须全都能认出坐标**。
     *
     * 清单是**真机抓的**（2026-10-13，良乡空教室页楼列表的前 15 项；其余在折叠之下）。
     * 加这条是因为刚栽过一次：别名表里没有接口真名「综合教学楼大楼」，
     * 而单测断言的却是「综合教学大楼A」这个**真实数据里不存在的写法** ——
     * 测试全绿，那栋楼在真机上却永远算不出距离（铁律 0.7：期望值按想当然写死）。
     *
     * ⇒ 以后**新增楼名前先跑这条**；认不出就说明 `CampusLocations` 要补别名，
     *   **别改测试的期望值**。
     */
    @Test
    fun `接口返回的楼名都能认出坐标`() {
        val realBuildingNames = listOf(
            "文萃楼", "文萃楼B", "文萃楼C", "文萃楼E", "文萃楼F", "文萃楼G",
            "文萃楼H", "文萃楼I", "文萃楼J", "文萃楼L", "文萃楼M",
            "文萃楼圆形报告厅", "综合教学楼大楼A", "综合教学楼大楼B", "理科教学大楼",
        )
        val unknown = realBuildingNames.filter { CampusLocations.match(it) == null }
        assertTrue("这些接口楼名认不出坐标（要给 CampusLocations 补别名）：$unknown", unknown.isEmpty())
    }
}
