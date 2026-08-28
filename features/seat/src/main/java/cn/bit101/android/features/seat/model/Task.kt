package cn.bit101.android.features.seat.model

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

data class ReservationTask(
    val id: String = UUID.randomUUID().toString(),
    val mode: TaskMode,
    val status: TaskStatus = TaskStatus.IDLE,
    val areaId: String = "",
    val segment: String = "",
    val seatNo: String = "",
    val reserveDate: String = "",
    val startTime: String = "08:00",
    val endTime: String = "22:30",
    val campusName: String = "",
    val floorName: String = "",
    val areaName: String = "",
    val message: String = ""
)
