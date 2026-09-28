package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.model.RenegeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SeatViolationLogic] 的纯逻辑单测。
 *
 * ⚠️ 这里**故意不再有**「签到超时就算一次违约」那类用例 —— 那套本地推算已被删除
 * （2026-09-28 确认服务端有 `/api/Member/reneges`，见该文件顶部注释）。
 * 现在本地只剩两件事：文案、以及「哪几条是新出现的」。
 */
class SeatViolationLogicTest {

    private fun record(
        place: String = "徐特立馆-三层",
        seat: String = "018",
        time: String = "2026-09-27 11:33",
    ) = RenegeRecord(nameMerge = place, name = seat, time = time, status = "1", statusName = "违约")

    // ---------------------------------------------------------------- 计数与文案

    @Test
    fun `剩余次数不会变成负数`() {
        assertEquals(5, SeatViolationLogic.remaining(0))
        assertEquals(1, SeatViolationLogic.remaining(4))
        assertEquals(0, SeatViolationLogic.remaining(5))
        assertEquals(0, SeatViolationLogic.remaining(9))
    }

    @Test
    fun `零次也要给出完整说明`() {
        assertEquals("暂无违约记录 · 累计 5 次将暂停预约 7 天", SeatViolationLogic.summaryText(0))
    }

    @Test
    fun `未到上限时报还剩几次`() {
        assertEquals("已违约 2 次 · 再 3 次将暂停预约 7 天", SeatViolationLogic.summaryText(2))
    }

    @Test
    fun `到上限时改说已暂停`() {
        assertEquals("已违约 5 次 · 按规则预约权暂停 7 天", SeatViolationLogic.summaryText(5))
    }

    @Test
    fun `通知标题在满 5 次时改口`() {
        assertEquals("座位违约 2/5", SeatViolationLogic.noticeTitle(2))
        assertEquals("预约权可能已被暂停", SeatViolationLogic.noticeTitle(5))
    }

    /**
     * ⚠️ 类别词必须跟着 `type` 走：把研讨室违约写成「座位违约」，
     * 等于通知用户一件**没发生**的事（他会去查一个不存在的座位违约）。
     */
    @Test
    fun `通知标题按类别换词`() {
        assertEquals("座位", SeatViolationLogic.typeLabel(SeatViolationLogic.TYPE_SEAT))
        assertEquals("研讨室", SeatViolationLogic.typeLabel(SeatViolationLogic.TYPE_SEMINAR))
        assertEquals(
            "研讨室违约 1/5",
            SeatViolationLogic.noticeTitle(1, SeatViolationLogic.TYPE_SEMINAR),
        )
        // 到上限时两句都是「预约权可能已被暂停」，不分类别（规则本身是同一句）
        assertEquals(
            SeatViolationLogic.noticeTitle(5, SeatViolationLogic.TYPE_SEAT),
            SeatViolationLogic.noticeTitle(5, SeatViolationLogic.TYPE_SEMINAR),
        )
    }

    @Test
    fun `通知正文带累计与剩余`() {
        assertEquals(
            "徐特立馆-三层 018 · 被记了 1 次违约，累计 2 次，再 3 次将暂停预约 7 天",
            SeatViolationLogic.noticeText(record().label(), 2),
        )
    }

    @Test
    fun `通知正文在满 5 次时改口`() {
        assertTrue(
            SeatViolationLogic.noticeText("座位 018", 5)
                .endsWith("累计 5 次，按规则预约权暂停 7 天")
        )
    }

    @Test
    fun `条目描述缺失时正文不至于残缺`() {
        assertTrue(SeatViolationLogic.noticeText("", 1).startsWith("座位预约 · 被记了 1 次违约"))
    }

    // ---------------------------------------------------------------- 新增检测

    @Test
    fun `空集合时全部算新增`() {
        val list = listOf(record(seat = "018"), record(seat = "019"))
        assertEquals(list, SeatViolationLogic.newRecords(emptySet(), list))
    }

    @Test
    fun `见过的不会重复算新增`() {
        val a = record(seat = "018")
        val b = record(seat = "019")
        val fresh = SeatViolationLogic.newRecords(setOf(a.key), listOf(a, b))
        assertEquals(listOf(b), fresh)
    }

    @Test
    fun `身份键靠场馆加座位加时间合成`() {
        // 接口不给 id，同场馆同座位**不同时间**必须是两条记录
        val x = record(time = "2026-09-27 11:33")
        val y = record(time = "2026-09-28 09:10")
        assertTrue(x.key != y.key)
        // 只见过 x 时，新增的应当**只有** y
        assertEquals(listOf(y), SeatViolationLogic.newRecords(setOf(x.key), listOf(x, y)))
    }

    // ---------------------------------------------------------------- 缓存编解码

    @Test
    fun `编解码往返一致`() {
        val keys = setOf("a|b|c", "徐特立馆-三层|018|2026-09-27 11:33")
        assertEquals(keys, SeatViolationLogic.decode(SeatViolationLogic.encode(keys)))
    }

    @Test
    fun `编码去重且稳定排序`() {
        val raw = SeatViolationLogic.encode(listOf("b", "a", "b"))
        assertEquals("a\nb", raw)
    }

    @Test
    fun `空文件解析出空集合`() {
        assertEquals(emptySet<String>(), SeatViolationLogic.decode(""))
        assertEquals(emptySet<String>(), SeatViolationLogic.decode("\n \n"))
    }
}
