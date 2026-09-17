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
            message = "监控中"
        )

    @Test
    fun `round trip preserves every field`() {
        val original = sample()

        val restored = deserializeTasks(serializeTasks(listOf(original)))

        assertEquals(listOf(original), restored)
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
