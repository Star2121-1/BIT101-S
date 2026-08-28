package cn.bit101.android.features.seat.model

enum class SeatStatus {
    AVAILABLE,
    RESERVED,
    OCCUPIED
}

data class Seat(
    val id: String,
    val no: String,
    val status: SeatStatus,
    val areaId: String? = null
)

data class SeatDate(
    val day: String,
    val segmentId: String,
    val start: String,
    val end: String
)
