package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.SeatViolationLogic.Entry
import cn.bit101.android.features.seat.SeatViolationLogic.Watch
import cn.bit101.android.features.seat.model.ReservationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SeatViolationLogic] 的纯逻辑单测。
 *
 * 锁住的是这套**推算**里最容易出错、也最容易误伤用户的几条：
 * - 宽限期内**不判**（服务端状态更新延迟 / 卡点刷卡）
 * - 已签到、已取消的**绝不记**（对正确行为的误罚最伤）
 * - 同一条预约**只记一次**
 * - 违约时刻记「截止 + 宽限」而不是「打开 App 的时刻」
 * - 编解码往返一致（本地文件是唯一的持久化手段）
 */
class SeatViolationLogicTest {

    private val t0 = 1_760_000_000_000L // 任意固定时刻，只作基准
    private val grace = SeatViolationLogic.GRACE_MILLIS

    private fun watch(
        id: String = "r1",
        label: String = "座位 018",
        deadline: Long = t0,
        signedIn: Boolean = false,
        cancelled: Boolean = false,
    ) = Watch(id, label, deadline, signedIn, cancelled)

    // ---------------------------------------------------------------- 判定

    @Test
    fun `宽限期内不判违约`() {
        val watches = listOf(watch(deadline = t0))
        assertEquals(emptyList<Entry>(), SeatViolationLogic.detect(watches, emptySet(), t0 + grace - 1))
    }

    @Test
    fun `过了宽限且始终未签到才记一次`() {
        val watches = listOf(watch(deadline = t0))
        val out = SeatViolationLogic.detect(watches, emptySet(), t0 + grace)
        assertEquals(1, out.size)
        // ⚠️ 违约时刻 = 截止 + 宽限，不是「现在」—— App 隔三天才打开也不该写成那天
        assertEquals(t0 + grace, out[0].atMillis)
        assertEquals("座位 018", out[0].seatLabel)
        assertEquals(SeatViolationLogic.Reason.MISSED_SIGN_IN, out[0].reason)
    }

    @Test
    fun `已签到的不记`() {
        val watches = listOf(watch(signedIn = true))
        assertEquals(0, SeatViolationLogic.detect(watches, emptySet(), t0 + grace * 10).size)
    }

    @Test
    fun `已取消的不记 —— 不能惩罚正确的取消行为`() {
        val watches = listOf(watch(cancelled = true))
        assertEquals(0, SeatViolationLogic.detect(watches, emptySet(), t0 + grace * 10).size)
    }

    @Test
    fun `同一条预约只记一次`() {
        val watches = listOf(watch())
        val counted = setOf("r1")
        assertEquals(0, SeatViolationLogic.detect(watches, counted, t0 + grace * 10).size)
    }

    @Test
    fun `多条未签到一次全部落账`() {
        val watches = listOf(watch("r1"), watch("r2"), watch("r3", signedIn = true))
        val out = SeatViolationLogic.detect(watches, emptySet(), t0 + grace)
        assertEquals(listOf("r1", "r2"), out.map { it.reservationId })
    }

    // ---------------------------------------------------------------- 跟踪表合并

    @Test
    fun `签到标记只升不降`() {
        val merged = SeatViolationLogic.upsert(
            listOf(watch(signedIn = true)),
            listOf(watch(signedIn = false)),
        )
        assertEquals(1, merged.size)
        assertTrue("已签到不应被后续快照退回", merged[0].signedIn)
    }

    @Test
    fun `取消标记只升不降`() {
        val merged = SeatViolationLogic.upsert(
            listOf(watch(cancelled = true)),
            listOf(watch(cancelled = false)),
        )
        assertTrue(merged[0].cancelled)
    }

    @Test
    fun `新预约会登记进跟踪表`() {
        val merged = SeatViolationLogic.upsert(emptyList(), listOf(watch("r1"), watch("r2")))
        assertEquals(setOf("r1", "r2"), merged.map { it.reservationId }.toSet())
    }

    @Test
    fun `过期跟踪记录会被清掉`() {
        val old = watch("old", deadline = t0 - SeatViolationLogic.WATCH_TTL_MILLIS - 1)
        val keep = watch("keep", deadline = t0)
        assertEquals(
            listOf("keep"),
            SeatViolationLogic.prune(listOf(old, keep), t0).map { it.reservationId }
        )
    }

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
    fun `通知正文带累计与剩余`() {
        assertEquals(
            "座位 018 签到已超时 · 已记 1 次违约，累计 2 次，再 3 次将暂停预约 7 天",
            SeatViolationLogic.noticeText("座位 018", 2)
        )
    }

    @Test
    fun `通知正文在满 5 次时改口`() {
        assertEquals(
            "座位 018 签到已超时 · 已记 1 次违约，累计 5 次，按规则预约权暂停 7 天",
            SeatViolationLogic.noticeText("座位 018", 5)
        )
    }

    @Test
    fun `座位号缺失时正文不至于残缺`() {
        assertTrue(SeatViolationLogic.noticeText("", 1).startsWith("座位预约 签到已超时"))
    }

    @Test
    fun `最近一次为空时返回 null`() {
        assertNull(SeatViolationLogic.lastText(emptyList()))
    }

    // ---------------------------------------------------------------- 去重与编解码

    @Test
    fun `台账按预约 id 去重并按时间排序`() {
        val entries = listOf(
            Entry("b", t0 + 100, "座位 2"),
            Entry("a", t0, "座位 1"),
            Entry("b", t0 + 100, "座位 2"),
        )
        val out = SeatViolationLogic.normalize(entries)
        assertEquals(2, out.size)
        assertEquals(listOf("a", "b"), out.map { it.reservationId })
    }

    @Test
    fun `编解码往返一致`() {
        val entries = listOf(
            Entry("r1", t0, "座位 018"),
            Entry("manual:123", t0 + 5, "", SeatViolationLogic.Reason.MANUAL),
        )
        val watches = listOf(watch("w1", "座位 001", t0 + 7, signedIn = true), watch("w2", "座位 002", t0 + 8))
        val (e2, w2) = SeatViolationLogic.decode(SeatViolationLogic.encode(entries, watches))
        assertEquals(entries, e2)
        assertEquals(watches.sortedBy { it.deadlineMillis }, w2.sortedBy { it.deadlineMillis })
    }

    @Test
    fun `含分隔符的标签不会破坏文件结构`() {
        val entries = listOf(Entry("r|1", t0, "座位 |018"))
        val watches = listOf(watch("w|1", "座位 |002", t0))
        val (e2, w2) = SeatViolationLogic.decode(SeatViolationLogic.encode(entries, watches))
        assertEquals(entries, e2)
        assertEquals(watches, w2)
    }

    @Test
    fun `坏行跳过而不是整份作废`() {
        val raw = "E|r1|$t0|座位 018|missed\nE|bad\nW|w1\nE|r2|${t0 + 1}|座位 019|missed\n"
        val (e, w) = SeatViolationLogic.decode(raw)
        assertEquals(listOf("r1", "r2"), e.map { it.reservationId })
        assertEquals(0, w.size)
    }

    @Test
    fun `空文件解析出空台账`() {
        val (e, w) = SeatViolationLogic.decode("")
        assertEquals(0, e.size)
        assertEquals(0, w.size)
    }

    // ---------------------------------------------------------------- 标签

    @Test
    fun `座位标签优先用座位号`() {
        assertEquals(
            "座位 018",
            SeatViolationLogic.seatLabelOf(ReservationRecord(id = "1", seatId = "9", seatNo = "018", areaName = "徐特立馆-三层"))
        )
    }

    @Test
    fun `没有座位号时退回区域名`() {
        assertEquals(
            "徐特立馆-三层",
            SeatViolationLogic.seatLabelOf(ReservationRecord(id = "1", seatId = "9", seatNo = "", areaName = "徐特立馆-三层"))
        )
    }
}
