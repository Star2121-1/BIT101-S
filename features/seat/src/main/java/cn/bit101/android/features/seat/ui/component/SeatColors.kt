package cn.bit101.android.features.seat.ui.component

import androidx.compose.ui.graphics.Color
import cn.bit101.android.features.seat.model.SeatStatus

/**
 * 座位状态的配色与文案，**唯一来源**。
 *
 * 抽出来的原因：座位格（[SeatGrid]）与座位图图例（`SeatMapScreen`）原先各自硬编码了一份颜色，
 * 文案也不一致（图例写「空闲」、格子写「可约」）。两处独立演化迟早会对不上 ——
 * 图例存在的意义就是解释格子，对不上比没有图例更糟。
 *
 * ⚠️ 底图模式下，颜色其实**不再由这里决定** —— 座位图标是服务端图片自带的
 * （绿=可用、文件=已预约、人=在用、时钟=临时离开、黄=暂停），我们只按状态挑图。
 * 这里的颜色只服务于**方格兜底模式**与图例文字，取值尽量贴近服务端底图的观感，
 * 免得两种模式看起来像两个 App。
 */
internal object SeatColors {

    /** 与服务端底图里「可用座位」的绿色一致（实测约 `#4CAF50`）。 */
    val available = Color(0xFF4CAF50)
    val occupied = Color(0xFFF44336)
    val reserved = Color(0xFFFF9800)
    val inUse = Color(0xFF9E9E9E)
    val leave = Color(0xFF9E9E9E)
    val unavailable = Color(0xFFFDD835)

    /** 我订的座位。与「他人的预约」区分开：服务端状态码不区分归属。 */
    val mine = Color(0xFF2E7D32)

    /** 选中态。不属于任何座位状态，是独立的交互高亮。 */
    val selected = Color(0xFF1565C0)

    /**
     * 覆盖服务端底图自带「返回 Back」按钮用的底色。
     *
     * 取值 `(0, 98, 60)` 是底图图例带的**深绿背景**实测值 —— 用它涂掉那个假按钮后
     * 与周围完全融合，看不出补丁痕迹。
     */
    val serverBackButtonMask = Color(0xFF00623C)

    fun of(status: SeatStatus): Color = when (status) {
        SeatStatus.AVAILABLE -> available
        SeatStatus.RESERVED -> reserved
        SeatStatus.MINE -> mine
        SeatStatus.IN_USE -> inUse
        SeatStatus.LEAVE -> leave
        SeatStatus.UNAVAILABLE -> unavailable
    }

    /** 座位格上的短标签。 */
    fun shortLabel(status: SeatStatus): String = when (status) {
        SeatStatus.AVAILABLE -> "可约"
        SeatStatus.RESERVED -> "预约"
        SeatStatus.MINE -> "我的"
        SeatStatus.IN_USE -> "在用"
        SeatStatus.LEAVE -> "离开"
        SeatStatus.UNAVAILABLE -> "不可用"
    }

    /** 图例里的说明文案。用服务端底图图例的**原词**（Occupied/Reserved/Available/…）。 */
    fun legendLabel(status: SeatStatus): String = when (status) {
        SeatStatus.AVAILABLE -> "可用座位"
        SeatStatus.RESERVED -> "预约座位"
        SeatStatus.MINE -> "我已预约"
        SeatStatus.IN_USE -> "在用座位"
        SeatStatus.LEAVE -> "临时离开"
        SeatStatus.UNAVAILABLE -> "暂停使用"
    }
}
