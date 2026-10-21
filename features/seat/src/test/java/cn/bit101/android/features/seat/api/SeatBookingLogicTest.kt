package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.model.ReservationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class SeatBookingLogicTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 21, 10, 0)

    private fun record(
        begin: String,
        end: String,
        status: String = "2",
        area: String = "图书馆 3F",
        seat: String = "A12",
    ) = ReservationRecord(
        id = "1",
        seatId = "s1",
        seatNo = seat,
        areaName = area,
        beginTime = begin,
        endTime = end,
        status = status,
    )

    @Test
    fun `进行中的预约优先且地点带座位号`() {
        val booking = SeatBookingLogic.pick(
            listOf(
                record("2026-10-21 09:30:00", "2026-10-21 11:30:00"),
                record("2026-10-21 10:30:00", "2026-10-21 12:00:00"),
            ),
            now,
        )

        assertEquals("图书馆 3F A12", booking?.place)
        assertEquals("09:30-11:30", booking?.timeText)
    }

    @Test
    fun `一小时以内开始的预约会被提示`() {
        val booking = SeatBookingLogic.pick(
            listOf(record("2026-10-21 10:59:00", "2026-10-21 12:00:00")),
            now,
        )

        assertEquals("10:59-12:00", booking?.timeText)
    }

    @Test
    fun `一小时后才开始的预约不提示`() {
        assertNull(
            SeatBookingLogic.pick(
                listOf(record("2026-10-21 11:01:00", "2026-10-21 12:00:00")),
                now,
            )
        )
    }

    @Test
    fun `已结束的预约不提示`() {
        assertNull(
            SeatBookingLogic.pick(
                listOf(record("2026-10-21 08:00:00", "2026-10-21 09:30:00")),
                now,
            )
        )
    }

    @Test
    fun `已结束或取消的预约不提示`() {
        assertNull(
            SeatBookingLogic.pick(
                listOf(record("2026-10-21 09:30:00", "2026-10-21 11:30:00", status = "8")),
                now,
            )
        )
    }

    @Test
    fun `使用中和暂离的预约都会提示`() {
        listOf("3", "6").forEach { status ->
            val booking = SeatBookingLogic.pick(
                listOf(record("2026-10-21 09:30:00", "2026-10-21 11:30:00", status = status)),
                now,
            )

            assertEquals("status=$status", "09:30-11:30", booking?.timeText)
        }
    }

    @Test
    fun `已开始但未签到的预约会提示`() {
        val booking = SeatBookingLogic.pick(
            listOf(record("2026-10-21 09:30:00", "2026-10-21 11:30:00", status = "4")),
            now,
        )

        assertEquals("09:30-11:30", booking?.timeText)
    }

    @Test
    fun `多条命中时取开始最早的一条`() {
        val booking = SeatBookingLogic.pick(
            listOf(
                record("2026-10-21 10:40:00", "2026-10-21 12:00:00", seat = "B01"),
                record("2026-10-21 09:00:00", "2026-10-21 11:00:00", seat = "C02"),
            ),
            now,
        )

        assertEquals("图书馆 3F C02", booking?.place)
    }

    @Test
    fun `跨天的预约写明天`() {
        val booking = SeatBookingLogic.pick(
            listOf(record("2026-10-22 00:20:00", "2026-10-22 02:00:00")),
            LocalDateTime.of(2026, 10, 21, 23, 40),
        )

        assertEquals("明天 00:20-02:00", booking?.timeText)
    }

    @Test
    fun `时间解析失败或没有地点时跳过`() {
        assertNull(
            SeatBookingLogic.pick(
                listOf(record("not-a-time", "2026-10-21 11:30:00")),
                now,
            )
        )
        assertNull(
            SeatBookingLogic.pick(
                listOf(record("2026-10-21 09:30:00", "2026-10-21 11:30:00", area = "", seat = "")),
                now,
            )
        )
    }
}
