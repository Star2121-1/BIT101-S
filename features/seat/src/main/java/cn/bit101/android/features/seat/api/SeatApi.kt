package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.SeatLoginStatus
import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.SeatTreeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeatApi @Inject constructor(
    seatHttp: SeatHttp,
    private val seatLoginStatus: SeatLoginStatus,
) {

    companion object {
        private const val TAG = "SeatApi"

        /**
         * 认证失效的统一信号：拦截器遇到 401 时抛出，
         * ViewModel 与前台服务据此清理会话。避免这个字符串散落多处。
         */
        const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    }

    /** 用于异步落盘 token，生命周期与应用一致。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _token = MutableStateFlow("")

    /**
     * token 的变化必须可被观察：前台服务侧遇到 401 也会清空它，
     * 若 UI 不订阅这个流，就会继续显示「已登录」而实际已经没有会话。
     */
    val tokenFlow: StateFlow<String> = _token.asStateFlow()

    /**
     * seatlib 的 JWT。**赋值即持久化** —— ViewModel 与前台服务共享同一份会话，
     * 因此 token 的读写统一收敛在这里，不再由调用方各自维护。
     */
    var token: String
        get() = _token.value
        set(value) {
            if (_token.value == value) return
            _token.value = value
            scope.launch { seatLoginStatus.token.set(value) }
        }

    /** 从持久化存储恢复 token（不回写）。冷启动时调用一次。 */
    suspend fun restoreToken(): String {
        val saved = seatLoginStatus.token.get()
        _token.value = saved
        return saved
    }

    private val client: OkHttpClient by lazy {
        seatHttp.base()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("lang", "zh")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .apply { if (token.isNotEmpty()) header("Authorization", "bearer$token") }
                    .build()

                val response = chain.proceed(request)
                // 这个 client 只用于座位业务接口，出现 401 必然意味着会话失效。
                // 早期只在「本地 token 非空」时才抛 TOKEN_EXPIRED，本地 token 被清空后
                // 服务端返回的 401 会被当成普通 HTTP 错误，401 自愈链路就断了。
                if (response.code == 401) {
                    response.close()
                    throw IOException(TOKEN_EXPIRED)
                }
                response
            }
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
            val res = client.newCall(Request.Builder().url("${SeatHttp.BASE}/api/Seat/tree").post(body).build()).execute()
            val bodyStr = res.body?.string() ?: ""
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
            SeatLog.d(TAG) { "getSeatTree ok: date=$date, ${nodes.size} node(s)" }
            nodes
        }
    }

    suspend fun getSeatDates(buildId: String? = null): Result<List<SeatDate>> = withContext(Dispatchers.IO) {
        runCatching {
            val extra = if (buildId != null) JSONObject().put("build_id", buildId) else JSONObject()
            val res = client.newCall(Request.Builder().url("${SeatHttp.BASE}/api/Seat/date").post(jsonBody(extra)).build()).execute()
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
                    dates.add(
                        SeatDate(
                            day = day.getString("day"),
                            segmentId = if (t.isNull("id")) "" else t.optString("id", ""),
                            start = if (t.isNull("start")) "" else t.optString("start", ""),
                            end = if (t.isNull("end")) "" else t.optString("end", "")
                        )
                    )
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
                val res = client.newCall(Request.Builder().url("${SeatHttp.BASE}/api/Seat/seat").post(body).build()).execute()
                val bodyStr = res.body?.string() ?: ""
                if (res.code != 200 || bodyStr.isEmpty()) throw IOException("HTTP ${res.code}")
                val json = JSONObject(bodyStr)
                val data = json.optJSONArray("data") ?: throw Exception("No data")
                val seats = mutableListOf<Seat>()
                for (i in 0 until data.length()) {
                    val s = data.getJSONObject(i)
                    seats.add(
                        Seat(
                            id = s.getString("id"),
                            no = s.getString("no"),
                            status = when (s.getString("status")) {
                                "1" -> SeatStatus.AVAILABLE
                                "2" -> SeatStatus.RESERVED
                                else -> SeatStatus.OCCUPIED
                            },
                            areaId = area
                        )
                    )
                }
                seats
            }
        }

    suspend fun confirmSeat(seatId: String, segment: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = jsonBody(JSONObject().apply { put("seat_id", seatId); put("segment", segment) })
                val res = client.newCall(Request.Builder().url("${SeatHttp.BASE}/api/Seat/confirm").post(body).build()).execute()
                val bodyStr = res.body?.string() ?: "{}"
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
            val res = client.newCall(Request.Builder().url("${SeatHttp.BASE}/api/Space/cancel").post(body).build()).execute()
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
