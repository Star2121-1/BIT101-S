package cn.bit101.android.features.seat.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务状态的终态判定。
 *
 * 这个判定保护的是「已结束的任务被改回运行中」——
 * 认证失效时仓储会一次性把在跑任务置为 FAILED，而那个刚被取消的协程
 * 还可能再执行一次状态更新。判错就会让 UI 显示「运行中」但实际已经死掉。
 */
class TaskStatusTest {

    @Test
    fun `success failed and cancelled are terminal`() {
        assertTrue(TaskStatus.SUCCESS.isTerminal)
        assertTrue(TaskStatus.FAILED.isTerminal)
        assertTrue(TaskStatus.CANCELLED.isTerminal)
    }

    @Test
    fun `idle and running are not terminal`() {
        assertFalse(TaskStatus.IDLE.isTerminal)
        assertFalse(TaskStatus.RUNNING.isTerminal)
    }

    @Test
    fun `every status is classified explicitly`() {
        // 新增状态时若忘了归类，会落到「非终态」，这里会立刻失败提醒
        val nonTerminal = TaskStatus.entries.filterNot { it.isTerminal }
        assertTrue(nonTerminal.containsAll(listOf(TaskStatus.IDLE, TaskStatus.RUNNING)))
        assertTrue(nonTerminal.size == 2)
    }
}
