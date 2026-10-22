package cn.bit101.android.features.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WidgetLogic.countdownText] 的单测。
 *
 * ## 为什么这条逻辑值得单测
 *
 * 组件的座位快照**每 10 分钟才写一次** ⇒ 倒计时如果写死在快照里就会**卡住 10 分钟**。
 * 而暂离保留期到点会释放座位并记违约、签到窗口只有 60 分钟 ⇒ 这 10 分钟有实际后果。
 * 所以快照存**截止时刻**，组件渲染时现算。
 *
 * RemoteViews 的渲染跑不了 JVM 单测 ⇒ 这段逻辑必须下沉到纯逻辑层（它就在 [WidgetLogic] 里）。
 */
class WidgetCountdownTest {

    private val now = 1_760_000_000_000L // 固定时刻

    private fun line(
        trail: String = "剩余 {m} 分钟",
        deadline: Long? = now + 48 * 60_000L,
        expired: String? = "保留时间已到",
    ) = WidgetLine(
        lead = "暂离",
        main = "座位 018",
        trail = trail,
        countdownDeadlineAt = deadline,
        countdownExpiredText = expired,
    )

    @Test
    fun `按截止时刻现算剩余分钟`() {
        assertEquals("剩余 48 分钟", WidgetLogic.countdownText(line(), now))
    }

    @Test
    fun `过了十分钟就少十分钟`() {
        // 快照是 10 分钟前写的，但组件每次渲染都现算 ⇒ 数字必须跟着走
        assertEquals("剩余 38 分钟", WidgetLogic.countdownText(line(), now + 10 * 60_000L))
    }

    @Test
    fun `已经过了截止时刻显示过期文案而不是负数`() {
        // ⚠️ 关键：快照陈旧 ⇒ 渲染时可能已过期 ⇒ 不能显示「剩余 -3 分钟」
        assertEquals("保留时间已到", WidgetLogic.countdownText(line(), now + 51 * 60_000L))
    }

    @Test
    fun `刚到截止时刻也算过期`() {
        assertEquals("保留时间已到", WidgetLogic.countdownText(line(), now + 48 * 60_000L))
    }

    @Test
    fun `没有截止时刻就原样返回`() {
        val plain = WidgetLine(lead = "监控中", main = "座位 108", trail = "尝试 3 次")
        assertEquals("尝试 3 次", WidgetLogic.countdownText(plain, now))
    }

    @Test
    fun `没有过期文案时过期就退回原文本`() {
        val noExpired = line(expired = null)
        assertEquals("剩余 {m} 分钟", WidgetLogic.countdownText(noExpired, now + 60 * 60_000L))
    }

    @Test
    fun `不足一分钟算零分钟`() {
        assertEquals("剩余 0 分钟", WidgetLogic.countdownText(line(), now + 48 * 60_000L - 30_000L))
    }

    @Test
    fun `时长占位符按小时分说`() {
        // 次日预约的签到：距今 12 小时 ⇒ 「剩 12 小时刷卡」而不是「剩 720 分钟刷卡」
        val signIn = line(trail = "剩 {d}刷卡", deadline = now + 12 * 60 * 60_000L)
        assertEquals("剩 12 小时刷卡", WidgetLogic.countdownText(signIn, now))
    }

    @Test
    fun `时长占位符不到一小时就是分钟`() {
        val signIn = line(trail = "剩 {d}刷卡", deadline = now + 42 * 60_000L)
        assertEquals("剩 42 分钟刷卡", WidgetLogic.countdownText(signIn, now))
    }

    @Test
    fun `时长占位符带余数`() {
        val signIn = line(trail = "剩 {d}刷卡", deadline = now + 65 * 60_000L)
        assertEquals("剩 1 小时 5 分刷卡", WidgetLogic.countdownText(signIn, now))
    }
}
