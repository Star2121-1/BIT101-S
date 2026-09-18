package cn.bit101.android.features.seat.api

import cn.bit101.android.features.seat.model.SeatStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应解析，**用 2026-09-18 从 seatlib.bit.edu.cn 真实抓取的响应体**做输入。
 *
 * 为什么不用手写样例：解析层最容易「看起来对」。字段类型与预期不符时它不会报错，
 * 只会静默地给出空数据或错误数据 —— 手写样例恰恰会迎合自己的假设，
 * 掩盖真实响应里的类型不一致。
 */
class SeatResponseParserTest {

    /** 真实响应（截取 1 个校区 / 1 个楼层 / 2 个区域，字段与类型保持原样）。 */
    private val realTreePayload = """
    [{"id":"1","name":"徐特立馆","enname":"","parentId":"0","levels":"1","isValid":"1",
      "comment":"","sort":"0","type":"0","color":null,"TotalCount":0,"UnavailableSpace":"0",
      "image_url":"https://seatlib.bit.edu.cn/home/images/web/area/1/floor.jpg","tag":0,
      "children":[
        {"id":"2","name":"三层","enname":"","parentId":"1","levels":"3","isValid":1,
         "comment":"","sort":"1","type":"0","color":null,"TotalCount":462,"UnavailableSpace":"0",
         "image_url":"https://seatlib.bit.edu.cn/home/images/web/area/2/floor.jpg","tag":0,
         "children":[
           {"id":"3","name":"视听学习空间","enname":"","parentId":"2","levels":"1","isValid":1,
            "comment":"","sort":"2","type":"1","color":null,"TotalCount":99,"UnavailableSpace":"4",
            "image_url":"https://seatlib.bit.edu.cn/home/images/web/area/3/floor.jpg","tag":0},
           {"id":"4","name":"自然科学图书第一阅览室","enname":"","parentId":"2","levels":"1","isValid":1,
            "comment":"","sort":"2","type":"1","color":null,"TotalCount":54,"UnavailableSpace":"1",
            "image_url":"https://seatlib.bit.edu.cn/home/images/web/area/4/floor.jpg","tag":0}
         ]}
      ]}]
    """.trimIndent()

    /** 真实响应：`times` 里的 id/start/end 恒为 null。 */
    private val realDatesPayload = """
    [{"day":"2026-09-18","times":[{"id":null,"status":1,"start":null,"end":null}]},
     {"day":"2026-09-19","times":[{"id":null,"status":1,"start":null,"end":null}]}]
    """.trimIndent()

    /** 真实响应：status 是字符串，"2" 表示已被本人预约。 */
    private val realSeatsPayload = """
    [{"id":"1","no":"001","name":"001","area":"3","category":"1",
      "point_x":"10.72917","point_x2":null,"point_x3":null,"point_x4":null,
      "point_y":"28.88889","point_y2":null,"point_y3":null,"point_y4":null,
      "width":"2.2916669999999999","height":"4.444445","status":"2","status_name":"已预约",
      "area_name":"视听学习空间","area_levels":"1","area_type":"1","area_color":null},
     {"id":"2","no":"002","name":"002","area":"3","category":"1","status":"1","status_name":"可预约"},
     {"id":"3","no":"003","name":"003","area":"3","category":"1","status":"3","status_name":"维修"}]
    """.trimIndent()

    @Test
    fun `tree fields are typed consistently despite mixed server types`() {
        // 这条是重点：实测 type 是字符串 "1"、isValid 在同一个响应里既可能是字符串
        // 也可能是数字。若解析依赖隐式强转，区域会被当成非区域，导致区域永远选不中。
        val nodes = parseSeatTree(JSONArray(realTreePayload))

        assertEquals(4, nodes.size)
        val area = nodes.first { it.id == "3" }
        assertEquals("视听学习空间", area.name)
        assertEquals(1, area.type)
        assertEquals("2", area.parentId)
    }

    @Test
    fun `tree is flattened depth first with correct parents`() {
        val nodes = parseSeatTree(JSONArray(realTreePayload))

        assertEquals(listOf("1", "2", "3", "4"), nodes.map { it.id })
        assertNull(nodes.first { it.id == "1" }.parentId)
        assertEquals("1", nodes.first { it.id == "2" }.parentId)
    }

    @Test
    fun `only type 1 nodes are areas as the dropdown expects`() {
        val nodes = parseSeatTree(JSONArray(realTreePayload))

        assertEquals(listOf("3", "4"), nodes.filter { it.type == 1 }.map { it.id })
    }

    @Test
    fun `dates keep the day and fall back to empty times when server sends null`() {
        val dates = parseSeatDates(JSONArray(realDatesPayload))

        assertEquals(listOf("2026-09-18", "2026-09-19"), dates.map { it.day })
        // 实测时段字段全为 null —— 解析后是空串，调用方按「不可用」回落到默认时段
        dates.forEach {
            assertEquals("", it.segmentId)
            assertEquals("", it.start)
            assertEquals("", it.end)
        }
    }

    @Test
    fun `seats map string status to enum`() {
        val seats = parseSeats(JSONArray(realSeatsPayload), areaId = "3")

        assertEquals(listOf("001", "002", "003"), seats.map { it.no })
        assertEquals(SeatStatus.RESERVED, seats[0].status)
        assertEquals(SeatStatus.AVAILABLE, seats[1].status)
        // 未知状态码按「不可用」处理，而不是当成可约 —— 宁可漏约也不能误约
        assertEquals(SeatStatus.UNAVAILABLE, seats[2].status)
        assertTrue(seats.all { it.areaId == "3" })
    }

    @Test
    fun `server status codes map to the five documented groups`() {
        // 分组取自官方前端 seat-map.js 挑底图的分支
        assertEquals(SeatStatus.AVAILABLE, seatStatusOf("1"))
        listOf("2", "10", "11").forEach { assertEquals("status=$it", SeatStatus.RESERVED, seatStatusOf(it)) }
        listOf("6", "8", "9").forEach { assertEquals("status=$it", SeatStatus.IN_USE, seatStatusOf(it)) }
        assertEquals(SeatStatus.LEAVE, seatStatusOf("7"))
        listOf("3", "4", "5").forEach { assertEquals("status=$it", SeatStatus.UNAVAILABLE, seatStatusOf(it)) }
        // 空值与未知值都不能被误判成可约
        assertEquals(SeatStatus.UNAVAILABLE, seatStatusOf(null))
        assertEquals(SeatStatus.UNAVAILABLE, seatStatusOf(""))
        assertEquals(SeatStatus.UNAVAILABLE, seatStatusOf("999"))
    }

    @Test
    fun `seat map position and status name are parsed from real payload`() {
        val payload = """
            [{"id":"656","no":"001","status":"1","status_name":"空闲",
              "point_x":"69.106880000000004","point_y":"90.193370000000002",
              "width":"2.4890189999999999","height":"4.4198890000000004"}]
        """.trimIndent()
        val seat = parseSeats(JSONArray(payload), areaId = "4").single()

        assertEquals("空闲", seat.statusName)
        assertTrue("坐标必须能定位", seat.hasMapPosition)
        assertEquals(69.10688f, seat.pointX!!, 0.001f)
        assertEquals(4.419889f, seat.height!!, 0.001f)
    }

    @Test
    fun `coordinates may be missing and must not break map positioning`() {
        val payload = """[{"id":"1","no":"001","status":"1"}]"""
        val seat = parseSeats(JSONArray(payload), areaId = "4").single()

        assertFalse("缺坐标时不应声称可定位", seat.hasMapPosition)
        assertNull(seat.pointX)
    }

    @Test
    fun `seat map images pick the right background per status`() {
        val images = cn.bit101.android.features.seat.model.SeatMapImages(
            free = "free.jpg", book = "book.jpg", close = "close.jpg",
            leave = "leave.jpg", use = "use.jpg"
        )
        assertEquals("free.jpg", images.forStatus(SeatStatus.AVAILABLE))
        assertEquals("book.jpg", images.forStatus(SeatStatus.RESERVED))
        assertEquals("book.jpg", images.forStatus(SeatStatus.MINE))
        assertEquals("use.jpg", images.forStatus(SeatStatus.IN_USE))
        assertEquals("leave.jpg", images.forStatus(SeatStatus.LEAVE))
        assertEquals("close.jpg", images.forStatus(SeatStatus.UNAVAILABLE))
        assertTrue(cn.bit101.android.features.seat.model.SeatMapImages().isEmpty)
    }

    @Test
    fun `intOrZero handles every type the server actually mixes`() {
        assertEquals(1, JSONObject("""{"type":"1"}""").intOrZero("type"))
        assertEquals(1, JSONObject("""{"type":1}""").intOrZero("type"))
        assertEquals(0, JSONObject("""{"type":null}""").intOrZero("type"))
        assertEquals(0, JSONObject("""{}""").intOrZero("type"))
        assertEquals(0, JSONObject("""{"type":"abc"}""").intOrZero("type"))
    }

    @Test
    fun `errorText prefers message and falls back to msg`() {
        // 成功响应用 msg，错误响应用 message —— 实测两者都会出现
        assertEquals("您尚未登录", JSONObject("""{"code":10001,"message":"您尚未登录"}""").errorText())
        assertEquals("操作成功", JSONObject("""{"code":1,"msg":"操作成功"}""").errorText())
        assertEquals("未知错误", JSONObject("""{"code":0}""").errorText())
    }
}
