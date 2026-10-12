package cn.bit101.android.features.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照的**新鲜度**判定。
 *
 * ## 为什么要有它
 *
 * 「XX**现在**有空教室」是**有时效的断言**，而组件真刷数据靠 30 分钟的周期任务
 * （Doze 下会被拖到几小时）。若无限期采信快照，用户晚上点开组件会看到上午的结论 ——
 * 而组件重绘不打网络，自己**验不了**，唯一能做的就是**不采信过期的**。
 *
 * ⇒ 过期返回 null（这一页空着），宁可没有也不说可能是假的「现在」。
 */
class StudySpotSnapshotTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `窗口内的快照采信`() {
        assertTrue(StudySpotSnapshot.isFresh(now, now))
        assertTrue(StudySpotSnapshot.isFresh(now - 30 * 60_000L, now))
        // 正好卡在上界也算新鲜
        assertTrue(StudySpotSnapshot.isFresh(now - StudySpotSnapshot.FRESH_MS, now))
    }

    @Test
    fun `过期的快照不采信`() {
        assertFalse(StudySpotSnapshot.isFresh(now - StudySpotSnapshot.FRESH_MS - 1, now))
        // Doze 把周期任务拖到几小时 —— 正是这条要挡的情况
        assertFalse(StudySpotSnapshot.isFresh(now - 6 * 60 * 60_000L, now))
    }

    /** 没写过（0）不能算新鲜，否则「没有数据」会被当成「刚拿到的数据」。 */
    @Test
    fun `没有写入记录时不采信`() {
        assertFalse(StudySpotSnapshot.isFresh(0L, now))
        assertFalse(StudySpotSnapshot.isFresh(-1L, now))
    }

    /** 系统时间往回跳过（对时 / 用户改时间）⇒ 这份快照的年龄不可信，一律不采信。 */
    @Test
    fun `时钟回跳时不采信`() {
        assertFalse(StudySpotSnapshot.isFresh(now + 60_000L, now))
    }

    /**
     * ⚠️ **格式对不上就不采信** —— 真机栽过：v1.10.0 把快照里存的**语义**从
     * 「正在上 X」改成「哪栋楼有空教室」，而快照没换版本 ⇒ 新版把旧内容当空教室那句话显示，
     * **自习页同一句话出现两遍**。
     */
    @Test
    fun `格式对不上时不采信`() {
        val fresh = now - 60_000L

        assertTrue(StudySpotSnapshot.isUsable(StudySpotSnapshot.SCHEMA, fresh, now))
        // 老版本写下的（没有版本号 ⇒ 读到 0）
        assertFalse(StudySpotSnapshot.isUsable(0, fresh, now))
        assertFalse(StudySpotSnapshot.isUsable(StudySpotSnapshot.SCHEMA - 1, fresh, now))
        assertFalse(StudySpotSnapshot.isUsable(StudySpotSnapshot.SCHEMA + 1, fresh, now))
        // 格式对但过期，同样不采信
        assertFalse(
            StudySpotSnapshot.isUsable(
                StudySpotSnapshot.SCHEMA,
                now - StudySpotSnapshot.FRESH_MS - 1,
                now,
            ),
        )
    }
}
