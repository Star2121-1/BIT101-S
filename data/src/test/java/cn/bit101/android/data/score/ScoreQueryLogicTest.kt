package cn.bit101.android.data.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩查询异步流程的纯逻辑单测（终止条件与限频）。
 *
 * 这套流程的「对敲」验证做不到（要真实账密 + 学校风控），所以把判定逻辑抽出来锁死。
 */
class ScoreQueryLogicTest {

    @Test
    fun `ready_services 含 jwb 才算就绪`() {
        assertTrue(ScoreQueryLogic.isReady(listOf("jwb")))
        assertTrue(ScoreQueryLogic.isReady(listOf("jwb", "jwc")))
        assertFalse(ScoreQueryLogic.isReady(listOf("jwc")))
        assertFalse(ScoreQueryLogic.isReady(emptyList()))
        assertFalse(ScoreQueryLogic.isReady(null))
    }

    @Test
    fun `终结态与短信态要认出来`() {
        assertTrue(ScoreQueryLogic.isTerminal("failed"))
        assertTrue(ScoreQueryLogic.isTerminal("expired"))
        // running / waiting_sms / authenticated 都不是终结态
        assertFalse(ScoreQueryLogic.isTerminal("running"))
        assertFalse(ScoreQueryLogic.isTerminal("waiting_sms"))
        assertFalse(ScoreQueryLogic.isTerminal(null))

        assertTrue(ScoreQueryLogic.needsSms("waiting_sms"))
        assertFalse(ScoreQueryLogic.needsSms("running"))
        assertFalse(ScoreQueryLogic.needsSms(null))
    }

    /**
     * ⚠️ 限频是**防封号**的：新流程每次检查都要用账密走一遍学校统一身份认证，
     * 而检查触发点是「App 启动 + 每日任务」——不限频等于每次开 App 登录一次。
     */
    @Test
    fun `检查限频 12 小时`() {
        val now = 1_800_000_000_000L
        // 从没查过 → 该查
        assertTrue(ScoreQueryLogic.shouldCheck(-1L, now))
        // 刚查过 → 不查
        assertFalse(ScoreQueryLogic.shouldCheck(now - 60_000, now))
        // 差一分钟不到 12h → 不查；刚到 12h → 查
        assertFalse(ScoreQueryLogic.shouldCheck(now - ScoreQueryLogic.CHECK_INTERVAL_MS + 60_000, now))
        assertTrue(ScoreQueryLogic.shouldCheck(now - ScoreQueryLogic.CHECK_INTERVAL_MS, now))
    }
}
