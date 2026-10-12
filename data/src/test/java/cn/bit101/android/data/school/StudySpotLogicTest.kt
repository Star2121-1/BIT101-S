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
}
