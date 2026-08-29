package cn.bit101.android.features.seat.api

import android.util.Log
import cn.bit101.android.config.user.base.LoginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.HttpCookie
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.SeatTreeNode

class SeatApi(private val loginStatus: LoginStatus) {

    companion object {
        private const val BASE = "https://seatlib.bit.edu.cn"
    }

    @Volatile
    var token: String = ""

    init {
        Log.d("SeatApi", "instance created, id=${System.identityHashCode(this)}, token='$token'")
    }

    private val cookieManager: CookieManager get() = loginStatus.cookieManager
    private val cookieStore get() = cookieManager.cookieStore

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val original = chain.request()
                val authHeader = if (token.isNotEmpty()) "bearer$token" else "NONE"
                Log.d("SeatApi", "interceptor: url=${original.url}, auth=$authHeader, tokenLen=${token.length}")
                val req = original.newBuilder()
                    .header("lang", "zh")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .apply {
                        if (token.isNotEmpty()) header("Authorization", "bearer$token")
                    }
                    .build()
                val response = chain.proceed(req)
                Log.d("SeatApi", "interceptor response: code=${response.code}, url=${response.request.url}")
                if (response.code == 401 && token.isNotEmpty()) {
                    response.close()
                    throw java.io.IOException("TOKEN_EXPIRED")
                }
                response
            }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookies.forEach { cookie ->
                        val hc = HttpCookie(cookie.name, cookie.value)
                        hc.domain = cookie.domain ?: url.host
                        hc.path = cookie.path
                        hc.secure = cookie.secure
                        if (cookie.expiresAt != Long.MAX_VALUE) {
                            hc.maxAge = maxOf(0L, (cookie.expiresAt - System.currentTimeMillis()) / 1000)
                        }
                        cookieStore.add(URI.create("${url.scheme}://${url.host}"), hc)
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val uri = URI.create("${url.scheme}://${url.host}")
                    return cookieStore.get(uri).map { hc ->
                        Cookie.Builder()
                            .name(hc.name)
                            .value(hc.value)
                            .domain(hc.domain)
                            .path(hc.path)
                            .apply { if (hc.secure) secure() }
                            .build()
                    }
                }
            })
            .build()
    }

    private fun jsonBody(extra: JSONObject = JSONObject()): okhttp3.RequestBody {
        val root = JSONObject()
        if (token.isNotEmpty()) root.put("authorization", "bearer$token")
        extra.keys().forEach { key -> root.put(key, extra.get(key)) }
        return root.toString().toRequestBody("application/json".toMediaType())
    }

    suspend fun getSeatTree(date: String): Result<List<SeatTreeNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = jsonBody(JSONObject().put("date", date))
            val res = client.newCall(Request.Builder().url("$BASE/api/Seat/tree").post(body).build()).execute()
            val bodyStr = res.body?.string() ?: ""
            Log.d("SeatApi", "getSeatTree: code=${res.code}, body=$bodyStr")
            if (res.code != 200 || bodyStr.isEmpty()) throw IOException("HTTP ${res.code}: $bodyStr")
            val json = JSONObject(bodyStr)
            val data = json.optJSONArray("data") ?: throw Exception("No data")
            val nodes = mutableListOf<SeatTreeNode>()
            fun parse(arr: JSONArray, parentId: String?) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.getString("id")
                    val name = item.getString("name")
                    val type = item.optInt("type", 0)
                    nodes.add(SeatTreeNode(id = id, name = name, type = type, parentId = parentId))
                    item.optJSONArray("children")?.let { parse(it, id) }
                }
            }
            parse(data, null)
            nodes
        }
    }

    suspend fun getSeatDates(buildId: String? = null): Result<List<SeatDate>> = withContext(Dispatchers.IO) {
        runCatching {
            val extra = if (buildId != null) JSONObject().put("build_id", buildId) else JSONObject()
            val res = client.newCall(Request.Builder().url("$BASE/api/Seat/date").post(jsonBody(extra)).build()).execute()
            val bodyStr = res.body?.string() ?: ""
            if (res.code != 200 || bodyStr.isEmpty()) throw IOException("HTTP ${res.code}")
            val json = JSONObject(bodyStr)
            val data = json.optJSONArray("data") ?: throw Exception("No data")
            val dates = mutableListOf<SeatDate>()
            for (i in 0 until data.length()) {
                val day = data.getJSONObject(i)
                val timesArr = day.getJSONArray("times")
                for (j in 0 until timesArr.length()) {
                    val t = timesArr.getJSONObject(j)
                    dates.add(SeatDate(
                        day = day.getString("day"),
                        segmentId = if (t.isNull("id")) "" else t.optString("id", ""),
                        start = if (t.isNull("start")) "" else t.optString("start", ""),
                        end = if (t.isNull("end")) "" else t.optString("end", "")
                    ))
                }
            }
            dates
        }
    }

    suspend fun getSeats(area: String, segment: String, day: String, startTime: String, endTime: String): Result<List<Seat>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = jsonBody(JSONObject().apply {
                    put("area", area); put("segment", segment)
                    put("day", day); put("startTime", startTime); put("endTime", endTime)
                })
                val res = client.newCall(Request.Builder().url("$BASE/api/Seat/seat").post(body).build()).execute()
                val bodyStr = res.body?.string() ?: ""
                if (res.code != 200 || bodyStr.isEmpty()) throw IOException("HTTP ${res.code}")
                val json = JSONObject(bodyStr)
                val data = json.optJSONArray("data") ?: throw Exception("No data")
                val seats = mutableListOf<Seat>()
                for (i in 0 until data.length()) {
                    val s = data.getJSONObject(i)
                    seats.add(Seat(
                        id = s.getString("id"),
                        no = s.getString("no"),
                        status = when (s.getString("status")) {
                            "1" -> SeatStatus.AVAILABLE
                            "2" -> SeatStatus.RESERVED
                            else -> SeatStatus.OCCUPIED
                        },
                        areaId = area
                    ))
                }
                seats
            }
        }

    suspend fun confirmSeat(seatId: String, segment: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = jsonBody(JSONObject().apply { put("seat_id", seatId); put("segment", segment) })
                Log.d("SeatApi", "confirmSeat: seatId=$seatId, segment=$segment")
                val res = client.newCall(Request.Builder().url("$BASE/api/Seat/confirm").post(body).build()).execute()
                val bodyStr = res.body?.string() ?: "{}"
                Log.d("SeatApi", "confirmSeat response: code=${res.code}, body=$bodyStr")
                if (res.code != 200 || bodyStr.isEmpty()) throw IOException("HTTP ${res.code}: $bodyStr")
                val json = JSONObject(bodyStr)
                if (json.optInt("code", -1) != 1) {
                    throw IOException("预约失败: ${json.optString("msg", "未知错误")}")
                }
                true
            }
        }

    suspend fun cancelSeat(seatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val body = jsonBody(JSONObject().put("seat_id", seatId))
            val res = client.newCall(Request.Builder().url("$BASE/api/Space/cancel").post(body).build()).execute()
            val bodyStr = res.body?.string() ?: "{}"
            if (bodyStr.isEmpty()) throw IOException("Empty response")
            val json = JSONObject(bodyStr)
            if (json.optInt("code", -1) != 1) {
                throw IOException("取消失败: ${json.optString("msg", "未知错误")}")
            }
            true
        }
    }
}
