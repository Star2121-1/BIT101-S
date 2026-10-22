package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「我的预约」解析与签到时限计算。
 *
 * 输入取自 2026-09-18 真实 `/api/index/subscribe` 响应；签到规则来自
 * `/api/index/booking_rules` 原文：当日预约需在开始后 60 分钟内刷卡，次日预约需在次日 9:00 前刷卡。
 */
class ReservationTest {

    private val realRecord = """
        [{"signIn":"0","id":"3427299","enname":"","area_id":"4","space":"659","signOut":"0",
          "isSingle":"1","status":"2","spaceCategory":"1",
          "beginTime":"2026-09-18 11:03:27","endTime":"2026-09-18 22:29:59","type":"1",
          "areaName":"徐特立馆-三层-自然科学图书第一阅览室","no":"004","statusname":"已预约"}]
    """.trimIndent()

    @Test
    fun `parses real subscribe payload`() {
        val record = parseReservations(JSONArray(realRecord)).single()

        assertEquals("3427299", record.id)
        assertEquals("659", record.seatId)
        assertEquals("004", record.seatNo)
        assertEquals("4", record.areaId)
        assertTrue(record.areaName.contains("自然科学图书第一阅览室"))
        assertTrue("status=2 表示未签到的有效预约", record.isActive)
    }

    @Test
    fun `entries without id are dropped`() {
        val payload = """[{"space":"1","no":"001","status":"2"}]"""
        assertTrue(parseReservations(JSONArray(payload)).isEmpty())
    }

    @Test
    fun `same day reservation must be signed in within 60 minutes`() {
        val record = parseReservations(JSONArray(realRecord)).single()
        val now = LocalDateTime.of(2026, 9, 18, 11, 30)

        assertEquals(LocalDateTime.of(2026, 9, 18, 12, 3, 27), record.signInDeadline(now))
        val hint = record.signInHint(now)!!
        assertTrue("提示里应给出截止时间，实际: $hint", hint.contains("12:03"))
    }

    @Test
    fun `same day reservation overdue is called out`() {
        val record = parseReservations(JSONArray(realRecord)).single()
        val now = LocalDateTime.of(2026, 9, 18, 15, 0)

        assertTrue(record.signInHint(now)!!.contains("超时"))
    }

    @Test
    fun `next day reservation must be signed in before 9 am`() {
        val payload = """
            [{"id":"1","space":"1","no":"001","status":"2",
              "beginTime":"2026-09-19 08:00:00","endTime":"2026-09-19 22:30:00"}]
        """.trimIndent()
        val record = parseReservations(JSONArray(payload)).single()
        val now = LocalDateTime.of(2026, 9, 18, 23, 0)

        assertEquals(LocalDateTime.of(2026, 9, 19, 9, 0), record.signInDeadline(now))
        val hint = record.signInHint(now)!!
        assertTrue("提示里应给出次日 9:00，实际: $hint", hint.contains("09:00"))
    }

    @Test
    fun `unparseable begin time yields no hint instead of crashing`() {
        val payload = """[{"id":"1","space":"1","no":"001","status":"2","beginTime":""}]"""
        val record = parseReservations(JSONArray(payload)).single()

        assertNull(record.reserveDate)
        assertNull(record.signInHint())
    }

    @Test
    fun `non active status is not treated as valid`() {
        val payload = """[{"id":"1","space":"1","no":"001","status":"3",
                            "beginTime":"2026-09-18 11:00:00"}]"""
        assertFalse(parseReservations(JSONArray(payload)).single().isActive)
    }

    // ------------------------------------------------------------ 状态语义（2/3/4/6）

    private fun recordOf(status: String) = parseReservations(
        JSONArray("""[{"id":"1","space":"1","no":"001","status":"$status",
                        "beginTime":"2026-09-18 11:00:00"}]""")
    ).single()

    @Test
    fun `isCurrent 认全部仍有效的状态`() {
        // 2 未签到 / 3 使用中 / 4 已开始提醒 / 6 暂离 —— 都还是「我的预约」
        listOf("2", "3", "4", "6").forEach { assertTrue("status=$it 应算仍有效", recordOf(it).isCurrent) }
        // 8 结束或取消、9 未知 —— 不算
        listOf("8", "9").forEach { assertFalse("status=$it 不应算仍有效", recordOf(it).isCurrent) }
    }

    @Test
    fun `needsSignIn 认未刷卡的 2 与 4`() {
        // 4 = 预约开始提醒：已经开始但还没刷，签到时限就在开始后 60 分钟 ⇒ 仍要提醒
        assertTrue("status=2 未签到", recordOf("2").needsSignIn)
        assertTrue("status=4 已开始但未刷卡，仍要提醒刷卡", recordOf("4").needsSignIn)
        // 3 使用中 / 6 暂离 = 已刷过；8 结束 = 不用提醒
        assertFalse("status=3 已刷卡", recordOf("3").needsSignIn)
        assertFalse("status=6 已刷卡", recordOf("6").needsSignIn)
        assertFalse("status=8 已结束", recordOf("8").needsSignIn)
    }
}
