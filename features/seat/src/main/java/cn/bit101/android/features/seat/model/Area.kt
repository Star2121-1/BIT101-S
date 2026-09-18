package cn.bit101.android.features.seat.model

data class SeatTreeNode(
    val id: String,
    val name: String,
    val type: Int,
    val parentId: String? = null,
    /**
     * 服务端带的示意图地址：
     * - **楼层**节点是楼层平面图（1365×768，各阅览室用绿块标注），可用于「认路」
     * - **区域**节点的这个地址实测 500（没有配置），座位级底图请用 `/api/seat/map`
     */
    val imageUrl: String? = null
)
