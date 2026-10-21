package cn.bit101.android.features.widget

import cn.bit101.android.data.database.entity.ExamScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WidgetExamPageTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 21, 10, 0)

    private fun exam(
        name: String,
        date: LocalDate,
        begin: LocalTime,
        end: LocalTime,
        classroom: String = "一教101",
    ) = ExamScheduleEntity(
        id = 0,
        term = "2026-2027-1",
        name = name,
        courseId = "",
        teacher = "",
        classroom = classroom,
        date = date,
        beginTime = begin,
        endTime = end,
        examMode = "",
        seatId = "",
    )

    private fun pageExams(vararg exams: ExamScheduleEntity): List<String> =
        WidgetLogic.examPage(exams.toList(), now).items.map { it.main }

    @Test
    fun `只保留未结束的考试且进行中排在最前`() {
        val ongoing = exam("操作系统", now.toLocalDate(), LocalTime.of(9, 0), LocalTime.of(11, 0))
        val todayLater = exam("高等数学", now.toLocalDate(), LocalTime.of(14, 0), LocalTime.of(16, 0))
        val tomorrow = exam(
            "大学物理",
            now.toLocalDate().plusDays(1),
            LocalTime.of(9, 0),
            LocalTime.of(11, 0),
        )
        val finished = exam(
            "英语",
            now.toLocalDate().minusDays(1),
            LocalTime.of(9, 0),
            LocalTime.of(11, 0),
        )

        val page = WidgetLogic.examPage(listOf(finished, tomorrow, todayLater, ongoing), now)

        assertEquals(listOf("操作系统", "高等数学", "大学物理"), page.items.map { it.main })
        assertTrue("进行中的考试要高亮", page.items.first().urgent)
    }

    @Test
    fun `日期标记用今天明天与月日`() {
        val page = WidgetLogic.examPage(
            listOf(
                exam("今天", now.toLocalDate(), LocalTime.of(14, 0), LocalTime.of(16, 0)),
                exam("明天", now.toLocalDate().plusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0)),
                exam("月末", LocalDate.of(2026, 11, 30), LocalTime.of(9, 0), LocalTime.of(11, 0)),
                exam("明年", LocalDate.of(2027, 1, 5), LocalTime.of(9, 0), LocalTime.of(11, 0)),
            ),
            now,
        )

        assertEquals(
            listOf("今天", "明天", "11/30", "2027/1/5"),
            page.items.map { it.lead },
        )
    }

    @Test
    fun `行内显示时间段与地点`() {
        val page = WidgetLogic.examPage(
            listOf(
                exam(
                    "操作系统",
                    now.toLocalDate().plusDays(2),
                    LocalTime.of(9, 0),
                    LocalTime.of(11, 30),
                    classroom = "文萃楼F302",
                )
            ),
            now,
        )

        val line = page.items.single()
        assertEquals("09:00-11:30", line.time)
        assertEquals("文萃楼F302", line.trail)
        assertEquals(PageKind.EXAM, page.kind)
    }

    @Test
    fun `未登录时整页换成登录引导`() {
        val page = WidgetLogic.examPage(
            listOf(exam("操作系统", now.toLocalDate(), LocalTime.of(14, 0), LocalTime.of(16, 0))),
            now,
            loggedIn = false,
        )

        assertTrue(page.items.isEmpty())
        assertEquals("未登录 BIT101", page.loginPrompt)
    }

    @Test
    fun `没有未结束的考试时显示空态`() {
        val page = WidgetLogic.examPage(
            listOf(
                exam(
                    "英语",
                    now.toLocalDate().minusDays(1),
                    LocalTime.of(9, 0),
                    LocalTime.of(11, 0),
                )
            ),
            now,
        )

        assertTrue(page.items.isEmpty())
        assertEquals("暂无考试安排", page.emptyText)
        assertNull(page.loginPrompt)
    }

    @Test
    fun `build 把考试页追加在最后一页`() {
        val data = WidgetLogic.build(
            courses = emptyList(),
            ddls = emptyList(),
            exams = emptyList(),
            today = now.toLocalDate(),
            now = now,
            firstDay = null,
        )

        assertEquals(PageKind.EXAM, data.pages.last().kind)
        assertEquals(PageKind.entries.size, data.pages.size)
        assertEquals(PageKind.entries.toList(), data.pages.map { it.kind })
    }
}
