package cn.bit101.android.features.seat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务列表的 JSON 序列化。
 *
 * 这层直接决定「杀进程后任务还能不能恢复」：反序列化一旦抛异常，
 * 整个任务列表就会丢失，所以容错行为是重点测试对象。
 */
class TaskSerializationTest {

    private fun sample(id: String = "t1", mode: TaskMode = TaskMode.MONITOR, status: TaskStatus = TaskStatus.RUNNING) =
        ReservationTask(
            id = id,
            mode = mode,
            status = status,
            areaId = "area-1",
            segment = "2",
            seatNo = "A12",
            reserveDate = "2026-09-18",
            startTime = "08:00",
            endTime = "12:00",
            campusName = "中关村校区",
            floorName = "2层",
            areaName = "中文图书区",
            message = "监控中",
            createdAt = 1_756_000_000_000L,
            attempts = 7,
            lastAttemptAt = 1_756_000_120_000L
        )

    @Test
    fun `round trip preserves every field`() {
        val original = sample()

        val restored = deserializeTasks(serializeTasks(listOf(original)))

        assertEquals(listOf(original), restored)
    }

    @Test
    fun `liveness fields round trip`() {
        val original = sample()

        val restored = deserializeTasks(serializeTasks(listOf(original))).single()

        assertEquals(1_756_000_000_000L, restored.createdAt)
        assertEquals(7, restored.attempts)
        assertEquals(1_756_000_120_000L, restored.lastAttemptAt)
    }

    @Test
    fun `legacy json without liveness fields falls back to zero`() {
        // 升级前存下的任务没有这三个字段。必须回落 0 而不是抛异常，也不能变成当前时刻 ——
        // 否则卡片上会显示「已等待 56 年」（由 1970 纪元算出）或凭空多出的等待时间
        val restored = deserializeTasks("""[{"id":"legacy","mode":"MONITOR","status":"RUNNING"}]""").single()

        assertEquals(0L, restored.createdAt)
        assertEquals(0, restored.attempts)
        assertEquals(0L, restored.lastAttemptAt)
    }

    @Test
    fun `order is preserved`() {
        val tasks = listOf(sample("a"), sample("b"), sample("c"))

        val restored = deserializeTasks(serializeTasks(tasks))

        assertEquals(listOf("a", "b", "c"), restored.map { it.id })
    }

    @Test
    fun `unknown enum values fall back instead of throwing`() {
        val json = """[{"id":"x","mode":"TELEPORT","status":"MELTING","areaId":"a"}]"""

        val restored = deserializeTasks(json)

        assertEquals(1, restored.size)
        // 未知模式回落到 MONITOR（保守：不会误当成会真实下单的模式）
        assertEquals(TaskMode.MONITOR, restored.single().mode)
        assertEquals(TaskStatus.IDLE, restored.single().status)
    }

    @Test
    fun `missing id is regenerated`() {
        val restored = deserializeTasks("""[{"mode":"PREFER","status":"SUCCESS"}]""")

        assertTrue(restored.single().id.isNotBlank())
    }

    @Test
    fun `missing optional fields use defaults`() {
        val restored = deserializeTasks("""[{"mode":"MONITOR","status":"RUNNING"}]""")

        val task = restored.single()
        assertEquals("08:00", task.startTime)
        assertEquals("22:30", task.endTime)
        assertEquals("", task.seatNo)
    }

    @Test
    fun `corrupt json yields empty list rather than crashing`() {
        assertTrue(deserializeTasks("这不是 JSON").isEmpty())
        assertTrue(deserializeTasks("""{"not":"an array"}""").isEmpty())
        assertTrue(deserializeTasks("[1,2,3]").isEmpty())
    }

    @Test
    fun `blank input yields empty list`() {
        assertTrue(deserializeTasks("").isEmpty())
        assertTrue(deserializeTasks("   ").isEmpty())
    }

    @Test
    fun `empty list round trips to empty list`() {
        assertTrue(deserializeTasks(serializeTasks(emptyList())).isEmpty())
    }
}
