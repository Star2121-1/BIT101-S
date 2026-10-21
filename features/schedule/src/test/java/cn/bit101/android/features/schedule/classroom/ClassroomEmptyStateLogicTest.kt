package cn.bit101.android.features.schedule.classroom

import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.features.schedule.classroom.FreeClassroomSearchViewModel.ClassroomBusyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class ClassroomEmptyStateLogicTest {

    private fun room(
        name: String,
        nextBusyTime: LocalTime,
        nextFreeTime: LocalTime? = null,
    ) = ClassroomBusyData(
        classroom = ClassroomInfo(name, null),
        prettyFreeTimes = "",
        nextBusyTime = nextBusyTime,
        nextFreeTime = nextFreeTime,
    )

    /** 测试里只把「今天一直空闲」当作满足条件，其余都算被过滤。 */
    private val freeOnlyAllDay: (ClassroomBusyData) -> Boolean = {
        it.nextBusyTime == LocalTime.MAX
    }

    @Test
    fun `整栋在课时给出在课数与最近空闲时间`() {
        val rooms = listOf(
            room("F201", LocalTime.MIN, LocalTime.of(13, 30)),
            room("F202", LocalTime.MIN, LocalTime.of(11, 30)),
        )

        val text = ClassroomEmptyStateLogic.text(
            ClassroomEmptyStateLogic.summarize(rooms, freeOnlyAllDay)
        )

        assertEquals(
            "共 2 间教室，当前都不满足空闲条件\n2 间正在上课 · 最近 11:30 起空闲",
            text,
        )
    }

    @Test
    fun `在课且今天不再空闲时不编造时间`() {
        val rooms = listOf(room("F201", LocalTime.MIN, null))

        val text = ClassroomEmptyStateLogic.text(
            ClassroomEmptyStateLogic.summarize(rooms, freeOnlyAllDay)
        )

        assertEquals(
            "共 1 间教室，当前都不满足空闲条件\n1 间正在上课 · 今天没有更长的空闲时段",
            text,
        )
    }

    @Test
    fun `在课与即将上课混在一起时分别计数`() {
        val rooms = listOf(
            room("F201", LocalTime.MIN, LocalTime.of(11, 30)),
            room("F202", LocalTime.of(10, 32)),
        )

        val summary = ClassroomEmptyStateLogic.summarize(rooms, freeOnlyAllDay)

        assertEquals(2, summary.total)
        assertEquals(1, summary.busy)
        assertEquals(1, summary.aboutToStart)
        assertEquals(LocalTime.of(11, 30), summary.earliestFree)
        assertEquals(
            "共 2 间教室，当前都不满足空闲条件\n1 间正在上课 · 1 间即将上课 · 最近 11:30 起空闲",
            ClassroomEmptyStateLogic.text(summary),
        )
    }

    @Test
    fun `只有快上课的教室时不提在课与空闲时间`() {
        val rooms = listOf(room("F201", LocalTime.of(10, 32)))

        val text = ClassroomEmptyStateLogic.text(
            ClassroomEmptyStateLogic.summarize(rooms, freeOnlyAllDay)
        )

        assertEquals(
            "共 1 间教室，当前都不满足空闲条件\n1 间即将上课",
            text,
        )
    }

    @Test
    fun `一直空闲的教室不会被算成即将上课`() {
        val rooms = listOf(room("F201", LocalTime.MAX))

        val summary = ClassroomEmptyStateLogic.summarize(rooms, { false })

        assertEquals(0, summary.busy)
        assertEquals(0, summary.aboutToStart)
        assertEquals(1, summary.total)
    }

    @Test
    fun `原本就没有教室时返回 null 保留旧文案`() {
        val summary = ClassroomEmptyStateLogic.summarize(emptyList(), freeOnlyAllDay)

        assertNull(ClassroomEmptyStateLogic.text(summary))
    }
}
