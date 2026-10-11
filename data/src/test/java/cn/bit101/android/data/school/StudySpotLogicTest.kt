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
}
