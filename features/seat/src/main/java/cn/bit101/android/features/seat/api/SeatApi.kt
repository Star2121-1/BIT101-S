package cn.bit101.android.features.seat.api

import cn.bit101.android.config.user.base.SeatLoginStatus
import cn.bit101.android.features.seat.SeatLog
import cn.bit101.android.features.seat.model.ReservationRecord
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatDate
import cn.bit101.android.features.seat.model.SeatMapImages
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.parseReservations
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

/**
 * 座位业务接口。
 *
 * 认证语义（2026-09-18 实测）：只有 `confirm` 与 `cancel` 需要认证，
 * `tree` / `date` / `seat` 无需任何凭据即可返回数据；认证失败由
 * **HTTP 200 + 业务码 10001** 表达，不是 401（见 [seatAuthFailure]）。
 */
@Singleton
class SeatApi @Inject constructor(
    seatHttp: SeatHttp,
    private val seatLoginStatus: SeatLoginStatus,
) {

    companion object {
        private const val TAG = "SeatApi"

        /** 认证失效的统一信号。ViewModel 与前台服务据此清理会话。 */
        const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    }

    /** 用于异步落盘 token，生命周期与应用一致。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _token = MutableStateFlow("")

    /**
     * token 的变化必须可被观察：前台服务侧遇到认证失效也会清空它，
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
                // 兜底：实测量 seatlib 用 HTTP 200 + 业务码表达未登录，
                // 但无法排除网关或未来改版确实返回 401。
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

    /** 发送 POST 并返回响应体；非 200 或空体直接抛错。 */
    private fun post(path: String, body: okhttp3.RequestBody): String {
        val res = client.newCall(Request.Builder().url(SeatHttp.BASE + path).post(body).build()).execute()
        val text = res.body?.string() ?: ""
        res.close()
        if (res.code != 200 || text.isEmpty()) throw IOException("HTTP ${res.code}: $text")
        return text
    }

    suspend fun getSeatTree(date: String): Result<List<SeatTreeNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(post("/api/Seat/tree", jsonBody(JSONObject().put("date", date))))
            val data = json.optJSONArray("data") ?: throw IOException("响应缺少 data")
            parseSeatTree(data).also { SeatLog.d(TAG) { "getSeatTree ok: date=$date, ${it.size} node(s)" } }
        }
    }

    suspend fun getSeatDates(buildId: String? = null): Result<List<SeatDate>> = withContext(Dispatchers.IO) {
        runCatching {
            val extra = if (buildId != null) JSONObject().put("build_id", buildId) else JSONObject()
            val json = JSONObject(post("/api/Seat/date", jsonBody(extra)))
            val data = json.optJSONArray("data") ?: throw IOException("响应缺少 data")
            parseSeatDates(data)
        }
    }

    suspend fun getSeats(
        area: String,
        segment: String,
        day: String,
        startTime: String,
        endTime: String,
    ): Result<List<Seat>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = jsonBody(
                JSONObject().apply {
                    put("area", area); put("segment", segment)
                    put("day", day); put("startTime", startTime); put("endTime", endTime)
                }
            )
            val json = JSONObject(post("/api/Seat/seat", body))
            val data = json.optJSONArray("data") ?: throw IOException("响应缺少 data")
            parseSeats(data, area)
        }
    }

    /**
     * 取区域座位底图（官方前端用的那套）。
     *
     * ⚠️ 参数名必须是 `id`：传 `area` / `area_id` 一律返回 `{"code":0,"msg":"Error"}`（实测）。
     * 返回五张 1920×1080 的底图 URL（free/book/close/leave/use），按座位状态选一张显示。
     * 区域没有配置底图时服务端会直接 500，此处按失败返回，由 UI 回落方格。
     */
    suspend fun getSeatMap(areaId: String): Result<SeatMapImages> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(post("/api/seat/map", jsonBody(JSONObject().put("id", areaId))))
            val data = json.optJSONObject("data") ?: throw IOException("响应缺少 data")
            SeatMapImages(
                free = data.optString("free").takeIf { it.isNotBlank() },
                book = data.optString("book").takeIf { it.isNotBlank() },
                close = data.optString("close").takeIf { it.isNotBlank() },
                leave = data.optString("leave").takeIf { it.isNotBlank() },
                use = data.optString("use").takeIf { it.isNotBlank() },
            )
        }
    }

    /** 「我的预约」（有效记录）。数据源与取消预约一致。 */
    suspend fun getMyReservations(): Result<List<ReservationRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = jsonBody(JSONObject().put("type", "1"))
            val json = JSONObject(post("/api/index/subscribe", body))
            parseReservations(json.optJSONArray("data") ?: JSONArray())
        }
    }

    suspend fun confirmSeat(seatId: String, segment: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            // seat_id 必须是数字（服务端按 int 处理；字符串形态未验证通过）
            val seatIdNum = seatId.toLongOrNull() ?: seatId.trim().toLongOrNull()
                ?: throw IOException("座位 id 非法: $seatId")
            val body = jsonBody(
                JSONObject().apply { put("seat_id", seatIdNum); put("segment", segment) }
            )
            val json = JSONObject(post("/api/Seat/confirm", body))
            val code = json.intOrZero("code")
            if (code != 1) {
                seatAuthFailure(code, json.errorText())?.let { throw it }
                throw IOException("预约失败: ${json.errorText()}")
            }
            true
        }
    }

    /**
     * 取消预约。
     *
     * ⚠️ `/api/Space/cancel` 的参数是**预约记录 id**（`/api/index/subscribe` 里的 `id`），
     * 不是座位 id —— 传座位 id 恒返回「操作失败」（2026-09-18 真实请求实测：
     * 预约 3427299 用 seat_id 取消失败，用 `{"id":"3427299"}` 取消成功）。
     */
    suspend fun cancelSeat(seatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val recordId = getMyReservations().getOrNull()
                ?.firstOrNull { it.seatId == seatId && it.isActive }?.id
                ?: getMyReservations().getOrNull()?.firstOrNull { it.seatId == seatId }?.id
                ?: throw IOException("未找到该座位的有效预约记录")
            cancelReservation(recordId).getOrElse { throw it }
        }
    }

    /** 按**预约记录 id** 取消。 */
    suspend fun cancelReservation(recordId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val body = jsonBody(JSONObject().put("id", recordId))
            val json = JSONObject(post("/api/Space/cancel", body))
            val code = json.intOrZero("code")
            if (code != 1) {
                seatAuthFailure(code, json.errorText())?.let { throw it }
                throw IOException("取消失败: ${json.errorText()}")
            }
            true
        }
    }
}
