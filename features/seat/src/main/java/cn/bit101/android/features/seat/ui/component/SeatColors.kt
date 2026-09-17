package cn.bit101.android.features.seat.ui.component

import androidx.compose.ui.graphics.Color
import cn.bit101.android.features.seat.model.SeatStatus

/**
 * 座位状态的配色与文案，**唯一来源**。
 *
 * 抽出来的原因：座位格（[SeatGrid]）与座位图图例（`SeatMapScreen`）原先各自硬编码了一份颜色，
 * 文案也不一致（图例写「空闲」、格子写「可约」）。两处独立演化迟早会对不上 ——
 * 图例存在的意义就是解释格子，对不上比没有图例更糟。
 */
internal object SeatColors {

    val available = Color(0xFF4CAF50)
    val occupied = Color(0xFFF44336)
    val reserved = Color(0xFFFF9800)

    /** 选中态。不属于任何座位状态，是独立的交互高亮。 */
    val selected = Color(0xFF1565C0)

    fun of(status: SeatStatus): Color = when (status) {
        SeatStatus.AVAILABLE -> available
        SeatStatus.OCCUPIED -> occupied
        SeatStatus.RESERVED -> reserved
    }

    /** 座位格上的短标签。 */
    fun shortLabel(status: SeatStatus): String = when (status) {
        SeatStatus.AVAILABLE -> "可约"
        SeatStatus.OCCUPIED -> "占用"
        SeatStatus.RESERVED -> "预约"
    }

    /** 图例里的说明文案。 */
    fun legendLabel(status: SeatStatus): String = when (status) {
        SeatStatus.AVAILABLE -> "可预约"
        SeatStatus.OCCUPIED -> "已被占用"
        SeatStatus.RESERVED -> "我已预约"
    }
}
