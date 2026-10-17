package cn.bit101.android.features.schedule.classroom

import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.data.school.StudySpotLogic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/**
 * [ClassroomBusyTimeLogic] 的判定矩阵（对应 SCHED-001 的 6 用例 + 基准换算 / 空列表边界）。
 *
 * ⚠️ 忙节号是 **1-based**、课表是 **0-based 列表**——跨基准用例（课间找「下一节」、
 * 1-based 5 落回 0-based 下标 4）专门守着这个换算，防止未来被「简化」成 ≥ / ≤ 近似。
 */
class ClassroomBusyTimeLogicTest {

    private val table: TimeTable = listOf(
        TimeTableItem(LocalTime.of(8, 0), LocalTime.of(8, 45)),     // 第 1 节
        TimeTableItem(LocalTime.of(8, 55), LocalTime.of(9, 40)),    // 第 2 节
        TimeTableItem(LocalTime.of(10, 0), LocalTime.of(10, 45)),   // 第 3 节
        TimeTableItem(LocalTime.of(10, 55), LocalTime.of(11, 40)),  // 第 4 节
        TimeTableItem(LocalTime.of(13, 30), LocalTime.of(14, 15)),  // 第 5 节
    )

    private fun nextBusyOf(busyTimeStr: String?, now: String): LocalTime =
        ClassroomBusyTimeLogic.nextBusyTimeOf(
            StudySpotLogic.parseBusySections(busyTimeStr).sorted(),
            table,
            LocalTime.parse(now),
        )

    private fun nextFreeOf(busyTimeStr: String?, now: String, thresholdMinutes: Long): LocalTime =
        ClassroomBusyTimeLogic.nextFreeTimeOf(
            StudySpotLogic.parseBusySections(busyTimeStr).sorted(),
            table,
            LocalTime.parse(now),
            thresholdMinutes,
        )

    // ---------------------------------------------------------------- nextBusyTimeOf

    @Test
    fun `用例1 正在上当天最后忙节 - 占用到作业`() { // 原 bug 场景：不再「空闲到明天」
        assertEquals(LocalTime.MIN, nextBusyOf("2", "09:00"))
    }

    @Test
    fun `用例2 正在上课且之后还有忙节 - 占用到作业`() {
        assertEquals(LocalTime.MIN, nextBusyOf("2,3", "09:00"))
    }

    @Test
    fun `用例3 课间且下一节忙 - 空闲到下一节开始`() { // 跨基准：课间 09:50 应取第 3 节 10:00
        assertEquals(LocalTime.of(10, 0), nextBusyOf("3", "09:50"))
    }

    @Test
    fun `用例4 首节前 - 空闲到首个忙节开始`() {
        assertEquals(LocalTime.of(8, 55), nextBusyOf("2", "07:30"))
    }

    @Test
    fun `用例5 末节结束后 - 空闲到明天`() {
        assertEquals(LocalTime.MAX, nextBusyOf("2", "15:00"))
    }

    @Test
    fun `用例6a 正在上非忙节 - 空闲到之后第一个忙节`() {
        assertEquals(LocalTime.of(13, 30), nextBusyOf("5", "10:20"))
    }

    @Test
    fun `用例6b 课间且后续忙节在第5节 - 跨基准取到13-30`() { // 1-based 5 ⇔ 0-based 4
        assertEquals(LocalTime.of(13, 30), nextBusyOf("4,5", "12:00"))
    }

    @Test
    fun `用例6c 课间且忙节都在过去 - 空闲到明天`() {
        assertEquals(LocalTime.MAX, nextBusyOf("3", "12:00"))
    }

    @Test
    fun `全天空闲 空忙节列表 - 空闲到明天`() { // busyTimeStr = null / 空串（服务端语义）
        assertEquals(LocalTime.MAX, nextBusyOf(null, "10:20"))
        assertEquals(LocalTime.MAX, nextBusyOf("", "10:20"))
    }

    @Test
    fun `边界 恰好在上课开始时刻 - 按正在上课计`() {
        assertEquals(LocalTime.MIN, nextBusyOf("1", "08:00"))
    }

    @Test
    fun `边界 课表为空 - 不崩且空闲到明天`() {
        assertEquals(
            LocalTime.MAX,
            ClassroomBusyTimeLogic.nextBusyTimeOf(emptyList(), emptyList(), LocalTime.NOON),
        )
    }

    // ---------------------------------------------------------------- nextFreeTimeOf

    @Test
    fun `恢复空闲 课间不足阈值 - 等到最后一个忙节下课`() {
        // 第1节 08:45 下课、第2节 08:55 开始：间距 10 分钟不 > 阈值 10 ⇒ 跳到 09:40
        assertEquals(LocalTime.of(9, 40), nextFreeOf("1,2", "08:30", 10))
    }

    @Test
    fun `恢复空闲 课间超过阈值 - 当前忙节下课即恢复`() {
        // 第1节 08:45 下课、第3节 10:00 开始：间距 75 分钟 > 阈值 10 ⇒ 08:45
        assertEquals(LocalTime.of(8, 45), nextFreeOf("1,3", "08:30", 10))
    }

    @Test
    fun `恢复空闲 空列表守卫 - 不崩`() {
        assertEquals(LocalTime.MAX, nextFreeOf(null, "08:30", 10))
    }
}
