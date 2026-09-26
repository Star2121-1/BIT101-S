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
}
