package cn.bit101.android.features.seat.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskMode {
    SINGLE,
    MONITOR,
    PREFER
}

enum class TaskStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

/**
 * 任务是不是「还在进行」。
 *
 * 用于列表排序与分组：进行中的必须浮在最上面，终态沉底折叠。
 * 与 [isTerminal] 互为反面，但语义分开写更好读（一个描述「还会变吗」，一个描述「现在还在跑吗」）。
 */
val TaskStatus.isActive: Boolean
    get() = this == TaskStatus.IDLE || this == TaskStatus.RUNNING

/**
 * 终态：不会再发生变化的任务状态。
 *
 * 存在意义是防止「已结束的任务被改回运行中」：认证失效时仓储会把在跑的任务一次性置为
 * [TaskStatus.FAILED]，而那个刚被取消的协程还可能再执行一次状态更新 ——
 * 没有这个判断就会把 FAILED 改回 RUNNING，UI 上表现为任务「运行中」但实际已经死了。
 */
val TaskStatus.isTerminal: Boolean
    get() = this == TaskStatus.SUCCESS || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED

data class ReservationTask(
    val id: String = UUID.randomUUID().toString(),
    val mode: TaskMode,
    val status: TaskStatus = TaskStatus.IDLE,
    val areaId: String = "",
    val segment: String = "",
    /** 监控模式的目标座位号（单个） */
    val seatNo: String = "",
    /**
     * 优先模式的偏好座位号，**按优先级排序**（空 = 不限，区域内最早空出即可）。
     *
     * 引入原因：优先预约原先只有单座位号，用户想「先抢 018，抢不到再抢 020」表达不出来，
     * 只能被迫选一个；现在可以从座位图上多选并排序。
     */
    val preferredSeats: List<String> = emptyList(),
    val reserveDate: String = "",
    val startTime: String = "08:00",
    val endTime: String = "22:30",
    val campusName: String = "",
    val floorName: String = "",
    val areaName: String = "",
    val message: String = "",
    /**
     * 任务创建时刻（epoch millis）。
     *
     * 用于在卡片上显示「已等待多久」—— 监控/优先任务是后台长跑，用户最想知道
     * 「它到底还在不在干活」。0 表示未知（老数据缺字段），UI 侧会隐藏该行。
     */
    val createdAt: Long = 0L,
    /**
     * 已尝试预约的次数（每次轮询真正发出预约请求算一次）。
     *
     * 这是「后台还活着」最直接的证据：数字在涨 = 服务在跑。
     * 老数据缺字段时回落 0。
     */
    val attempts: Int = 0,
    /**
     * 最近一次尝试的时刻（epoch millis）。0 表示还没尝试过 / 老数据缺字段。
     *
     * 与 [attempts] 配合：次数不动 + 最近尝试很旧 → 服务可能已被系统杀掉，
     * 用户可以据此判断要不要重开。
     */
    val lastAttemptAt: Long = 0L
)

// ---------- 持久化：任务列表 <-> JSON（供 SeatTaskStore 使用） ----------

fun ReservationTask.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("mode", mode.name)
    put("status", status.name)
    put("areaId", areaId)
    put("segment", segment)
    put("seatNo", seatNo)
    put("preferredSeats", JSONArray().apply { preferredSeats.forEach { put(it) } })
    put("reserveDate", reserveDate)
    put("startTime", startTime)
    put("endTime", endTime)
    put("campusName", campusName)
    put("floorName", floorName)
    put("areaName", areaName)
    put("message", message)
    put("createdAt", createdAt)
    put("attempts", attempts)
    put("lastAttemptAt", lastAttemptAt)
}

/** 反序列化单个任务；未知枚举值回落到安全的默认值，避免脏数据导致崩溃。 */
fun JSONObject.toReservationTask(): ReservationTask = ReservationTask(
    id = optString("id").ifEmpty { UUID.randomUUID().toString() },
    mode = TaskMode.entries.firstOrNull { it.name == optString("mode") } ?: TaskMode.MONITOR,
    status = TaskStatus.entries.firstOrNull { it.name == optString("status") } ?: TaskStatus.IDLE,
    areaId = optString("areaId"),
    segment = optString("segment"),
    seatNo = optString("seatNo"),
    preferredSeats = optJSONArray("preferredSeats")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } ?: emptyList(),
    reserveDate = optString("reserveDate"),
    startTime = optString("startTime", "08:00"),
    endTime = optString("endTime", "22:30"),
    campusName = optString("campusName"),
    floorName = optString("floorName"),
    areaName = optString("areaName"),
    message = optString("message"),
    // 老数据没有这三个字段：optLong/optInt 缺省回落 0，UI 侧据此隐藏相应行，
    // 不会出现「1970 年」这种鬼时间
    createdAt = optLong("createdAt", 0L),
    attempts = optInt("attempts", 0),
    lastAttemptAt = optLong("lastAttemptAt", 0L)
)

/** 序列化任务列表；失败时返回空串（调用方按「无任务」处理，不覆盖已有内容需自行判断）。 */
fun serializeTasks(tasks: List<ReservationTask>): String = runCatching {
    val arr = JSONArray()
    tasks.forEach { arr.put(it.toJson()) }
    arr.toString()
}.getOrDefault("")

/** 反序列化任务列表；内容损坏时返回空列表，不抛异常。 */
fun deserializeTasks(json: String): List<ReservationTask> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toReservationTask() }
    }.getOrDefault(emptyList())
}
