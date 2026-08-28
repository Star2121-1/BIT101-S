package cn.bit101.android.features.seat.model

data class SeatTreeNode(
    val id: String,
    val name: String,
    val type: Int,
    val parentId: String? = null
)
