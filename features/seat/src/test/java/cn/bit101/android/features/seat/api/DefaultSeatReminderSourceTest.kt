package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.model.ReservationRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * [DefaultSeatReminderSource] 的单测。
 *
 * 钉住最关键的一条：**状态 4（预约开始提醒）也要发签到提醒**。
 *
 * 背景：签到时限是「预约开始后 60 分钟」。预约一开始，服务端就把状态从 `2` 改成 `4`
 * （状态名「预约开始提醒」）。若只认 `2`，提醒会在**用户最该被提醒的那一刻**消失 ⇒
 * 到点记违约（累计 5 次停用 7 天）。
 */
class DefaultSeatReminderSourceTest {

    private val seatApi = mockk<SeatApi>(relaxed = true)
    private val repository = mockk<SeatReservationRepository>(relaxed = true)

    private fun source() = DefaultSeatReminderSource(seatApi, repository)

    private fun record(status: String, begin: String = "2026-10-21 09:30:00") = ReservationRecord(
        id = "1",
        seatId = "s1",
        seatNo = "A12",
        areaName = "图书馆 3F",
        beginTime = begin,
        endTime = "2026-10-21 22:30:00",
        status = status,
    )

    private fun givenRecords(records: List<ReservationRecord>) {
        coEvery { seatApi.ensureTokenLoaded() } returns "tk"
        coEvery { seatApi.token } returns "tk"
        coEvery { repository.records } returns kotlinx.coroutines.flow.MutableStateFlow(records)
    }

    @Test
    fun `状态 4 也发签到提醒`() = runBlocking {
        // 09:30 开始 → 截止 10:30；now = 10:00，还在窗口内
        givenRecords(listOf(record(status = "4")))
        val now = LocalDateTime.of(2026, 10, 21, 10, 0)

        val pending = source().pendingSignIns(now)

        assertEquals(1, pending.size)
        assertEquals("A12", pending.first().seatNo)
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 30), pending.first().signInDeadline)
    }

    @Test
    fun `状态 2 照常发签到提醒`() = runBlocking {
        givenRecords(listOf(record(status = "2")))
        val now = LocalDateTime.of(2026, 10, 21, 10, 0)

        assertEquals(1, source().pendingSignIns(now).size)
    }

    @Test
    fun `已刷卡的状态 3 与 6 不再提醒`() = runBlocking {
        givenRecords(listOf(record(status = "3"), record(status = "6")))
        val now = LocalDateTime.of(2026, 10, 21, 10, 0)

        assertTrue(source().pendingSignIns(now).isEmpty())
    }

    @Test
    fun `没有会话就不提醒`() = runBlocking {
        givenRecords(listOf(record(status = "2")))
        // ⚠️ 覆盖要在 givenRecords 之后：mockk 的 coEvery 是「后设覆盖前设」
        coEvery { seatApi.ensureTokenLoaded() } returns ""
        coEvery { seatApi.token } returns ""

        assertTrue(source().pendingSignIns(LocalDateTime.of(2026, 10, 21, 10, 0)).isEmpty())
    }
}
