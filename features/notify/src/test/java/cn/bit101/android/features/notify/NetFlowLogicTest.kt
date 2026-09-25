package cn.bit101.android.features.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 校园网流量阈值判定的单测。
 *
 * 口径（用户 2026-09-26 确认）：接口 `[6]` 按计费周期累计 = 官方门户的「本月」用量；
 * 300 GB 后限速，270 GB 提前提醒，各只发一次，「之后就算了」。
 */
class NetFlowLogicTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun `未到 270 GB 不提醒`() {
        assertNull(NetFlowChecker.decide(0L, warnedNear = false, warnedLimit = false))
        assertNull(NetFlowChecker.decide(269 * gib, false, false))
    }

    @Test
    fun `到 270 GB 提醒临近`() {
        assertEquals(
            NetFlowChecker.Alert.Near,
            NetFlowChecker.decide(270 * gib, warnedNear = false, warnedLimit = false),
        )
        // 阈值本身就是边界（>= 才算到）
        assertEquals(
            NetFlowChecker.Alert.Near,
            NetFlowChecker.decide(NetFlowChecker.WARN_BYTES, false, false),
        )
    }

    @Test
    fun `越过 300 GB 直接报超限，不补发临近那条`() {
        assertEquals(
            NetFlowChecker.Alert.Exceeded,
            NetFlowChecker.decide(366 * gib, warnedNear = false, warnedLimit = false),
        )
        // 已提醒过临近、随后越线 → 该发超限
        assertEquals(
            NetFlowChecker.Alert.Exceeded,
            NetFlowChecker.decide(301 * gib, warnedNear = true, warnedLimit = false),
        )
    }

    @Test
    fun `同一周期内各只发一次，之后不再打扰`() {
        // 270 已发、还没到 300 → 静默
        assertNull(NetFlowChecker.decide(280 * gib, warnedNear = true, warnedLimit = false))
        // 两条都发过 → 再也不发（用户要求「之后就算了」）
        assertNull(NetFlowChecker.decide(500 * gib, warnedNear = true, warnedLimit = true))
    }
}
