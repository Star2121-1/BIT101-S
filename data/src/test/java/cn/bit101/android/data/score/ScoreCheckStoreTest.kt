package cn.bit101.android.data.score

import cn.bit101.android.data.score.ScoreCheckStore.Code
import cn.bit101.android.data.score.ScoreCheckStore.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成绩检查状态存储的单测（编解码 + 设置页文案）。 */
class ScoreCheckStoreTest {

    @Test
    fun `编解码往返`() {
        val status = Status(1_800_000_000_000L, Code.OK, 12)
        assertEquals(status, ScoreCheckStore.parse(ScoreCheckStore.encode(status)))
    }

    @Test
    fun `形状不对返回 null（不猜）`() {
        assertNull(ScoreCheckStore.parse(null))
        assertNull(ScoreCheckStore.parse(""))
        assertNull(ScoreCheckStore.parse("1800000000000"))
        assertNull(ScoreCheckStore.parse("abc|OK|1"))
        assertNull(ScoreCheckStore.parse("1800000000000|NOT_A_CODE|1"))
    }

    @Test
    fun `条数可以缺省`() {
        val status = ScoreCheckStore.parse("1800000000000|OK")
        assertEquals(0, status?.count)
        assertEquals(Code.OK, status?.code)
    }

    @Test
    fun `设置页文案覆盖各种结果`() {
        val at = 1_800_000_000_000L
        assertTrue(ScoreCheckStore.statusText(null).contains("还没检查过"))
        assertTrue(ScoreCheckStore.statusText(Status(at, Code.OK, 12)).contains("已同步 12 门课"))
        // ⚠️ 学校要短信时必须说清「已暂停」——否则用户以为功能坏了
        assertTrue(ScoreCheckStore.statusText(Status(at, Code.NEED_SMS)).contains("短信验证"))
        assertTrue(ScoreCheckStore.statusText(Status(at, Code.NOT_LOGGED_IN)).contains("未登录"))
    }

    /** 服务端给的失败原因要能存下来、读回来（设置页展示它，比任何转述都准）。 */
    @Test
    fun `失败原因能来回带`() {
        val raw = "用户名或密码错误 [status=401, risk=ustc-token]"
        val status = Status(1_800_000_000_000L, Code.AUTH_FAILED, 0, raw)

        val parsed = ScoreCheckStore.parse(ScoreCheckStore.encode(status))

        assertEquals(Code.AUTH_FAILED, parsed?.code)
        assertEquals(raw, parsed?.detail)
    }

    /** 老格式（三段、没有原因）仍要能读 —— 用户手机上就是这种旧记录。 */
    @Test
    fun `老格式没有原因段也能读`() {
        val parsed = ScoreCheckStore.parse("1800000000000|FAILED|0")

        assertEquals(Code.FAILED, parsed?.code)
        assertNull(parsed?.detail)
    }

    /** 原因里的 `|` 会破坏分隔格式 → 落盘前必须净化。 */
    @Test
    fun `原因里的分隔符会被净化`() {
        val status = Status(1_800_000_000_000L, Code.AUTH_FAILED, 0, "a|b\nc")

        val parsed = ScoreCheckStore.parse(ScoreCheckStore.encode(status))

        assertEquals("a/b c", parsed?.detail)
    }

    /**
     * ⚠️ 这条是本次修复的核心：学校拒绝登录（典型是密码不对）**重试永远不会成功**，
     * 文案必须给出正确动作，而不能再写「稍后重试」。
     */
    @Test
    fun `认证被拒的文案要说清重试无效`() {
        val text = ScoreCheckStore.statusText(
            Status(1_800_000_000_000L, Code.AUTH_FAILED, 0, "用户名或密码错误 [status=401]"),
        )

        assertTrue(text.contains("用户名或密码错误"))
        assertTrue(text.contains("重试无效"))
        // 技术细节（方括号里的）不该出现在给用户看的文案里
        assertTrue(!text.contains("status=401"))
    }

    /** 只取方括号前的人话；没有方括号就原样返回（不猜格式）。 */
    @Test
    fun `humanReason 取方括号前的人话`() {
        assertEquals("用户名或密码错误", ScoreCheckStore.humanReason("用户名或密码错误 [status=401]"))
        assertEquals("网络异常", ScoreCheckStore.humanReason("网络异常"))
        assertNull(ScoreCheckStore.humanReason(null))
        assertNull(ScoreCheckStore.humanReason("   "))
    }
}
