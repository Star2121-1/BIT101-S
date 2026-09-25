package cn.bit101.android.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CampusNetLogic] 解析测试 —— 样本取自 2026-09-24 真实响应。
 */
class CampusNetLogicTest {

    /** 真实样本（2026-09-24，余额 40.01、累计流量 389049551406 字节）。 */
    private val sample =
        "1120241355,1790237818,1790242108,373674937,147058240,0,389049551406,7156454," +
            "10.195.136.157,0,,40.01,0,0,0,0,0,0,0,0,0,1.01.20220802"

    @Test
    fun `解析真实样本`() {
        val info = CampusNetLogic.parse(sample)!!

        assertEquals("1120241355", info.userName)
        assertEquals(1790237818L, info.loginEpochSeconds)
        assertEquals(1790242108L, info.nowEpochSeconds)
        assertEquals(373674937L, info.bytesIn)
        assertEquals(147058240L, info.bytesOut)
        assertEquals(389049551406L, info.bytesTotal)
        assertEquals(7156454L, info.durationSeconds)
        assertEquals("10.195.136.157", info.ip)
        assertEquals(40.01, info.balanceYuan, 0.001)
    }

    /** 未在线 / 错误页 / 空串 → null。 */
    @Test
    fun `非法响应返回 null`() {
        assertNull(CampusNetLogic.parse("not_online_error"))
        assertNull(CampusNetLogic.parse(""))
        assertNull(CampusNetLogic.parse("<html>error</html>"))
    }

    /** 字段不足（截断响应）→ null，不能越界。 */
    @Test
    fun `字段不足返回 null`() {
        assertNull(CampusNetLogic.parse("1120241355,1790237818,1790242108,1,2"))
    }

    /** 数字字段出现非数字内容 → null 而不是抛异常。 */
    @Test
    fun `数字字段损坏返回 null`() {
        assertNull(
            CampusNetLogic.parse(
                "1120241355,NaN,1790242108,1,2,0,3,4,10.0.0.1,0,,x,0"
            )
        )
    }

    @Test
    fun `流量与时长格式化`() {
        // 389049551406 B = 362.3 GiB（统一保留一位小数）
        assertEquals("362.3 GB", CampusNetLogic.formatTraffic(389049551406L))
        assertEquals("1.5 GB", CampusNetLogic.formatTraffic(1610612736L))
        assertEquals("0.1 GB", CampusNetLogic.formatTraffic(107374182L))

        assertEquals("5 分钟", CampusNetLogic.formatDuration(300))
        assertEquals("2 小时 5 分", CampusNetLogic.formatDuration(7500))
        // 2 天 3 小时
        assertEquals("2 天 3 小时", CampusNetLogic.formatDuration(2L * 86400 + 3 * 3600))
    }

    /** 在线时长的时间差（now - login）应为正数（服务器时间在走）。 */
    @Test
    fun `服务器时间在登录时间之后`() {
        val info = CampusNetLogic.parse(sample)!!
        assertTrue(info.nowEpochSeconds >= info.loginEpochSeconds)
        assertFalse(info.durationSeconds < 0)
    }

    /** 三种结果要分开：有数据 / 未认证 / 连不上（UI 文案全靠这个区分）。 */
    @Test
    fun `parseResult 区分三类结果`() {
        val online = CampusNetLogic.parseResult(sample)
        assertTrue(online is CampusNetResult.Online)
        assertEquals(40.01, online.infoOrNull!!.balanceYuan, 0.001)

        // Srun 在请求方没有在线会话时返回 not_online（短文本，非 CSV）
        assertEquals(CampusNetResult.NotOnline, CampusNetLogic.parseResult("not_online"))
        assertEquals(CampusNetResult.NotOnline, CampusNetLogic.parseResult("Not_Online\n"))

        // 错误页 / 空串：不可识别（与「未认证」是两件事）
        assertTrue(CampusNetLogic.parseResult("<html>error</html>") is CampusNetResult.Failed)
        assertTrue(CampusNetLogic.parseResult("") is CampusNetResult.Failed)
    }

    /** 只有 Online 才算有数据 —— 其余两种都必须给出 null，避免 UI 误判成成功。 */
    @Test
    fun `infoOrNull 仅 Online 有值`() {
        assertNull(CampusNetResult.NotOnline.infoOrNull)
        assertNull(CampusNetResult.Failed("x").infoOrNull)
        assertNull(CampusNetLogic.parseResult("not_online").infoOrNull)
    }
}
