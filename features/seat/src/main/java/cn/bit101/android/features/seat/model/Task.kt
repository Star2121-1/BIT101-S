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
    val message: String = ""
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
    message = optString("message")
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
