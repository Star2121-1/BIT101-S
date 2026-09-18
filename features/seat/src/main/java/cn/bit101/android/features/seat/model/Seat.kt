package cn.bit101.android.features.seat.model

/**
 * 座位状态。
 *
 * 与服务端 `status` 的对应关系取自**官方前端代码**（`assets/seat-map.*.js` 按状态选底图）：
 *
 * | 服务端 | 含义 | 本枚举 |
 * |---|---|---|
 * | `1` | 空闲 | [AVAILABLE] |
 * | `2` / `10` / `11` | 已预约（**不区分是不是本人**） | [RESERVED] |
 * | `6` / `8` / `9` | 在用 | [IN_USE] |
 * | `7` | 临时离开 | [LEAVE] |
 * | `3` / `4` / `5` | 暂停使用 | [UNAVAILABLE] |
 * | 其它 | 未知 | [UNAVAILABLE] |
 *
 * ⚠️ [MINE] 不是服务端状态：服务端不会告诉某个已预约座位是不是**你自己**订的。
 * 由「我的预约」（`/api/index/subscribe`）的 `space` 字段交叉比对后标记。
 * 早期版本直接把服务端 `2` 当作「我已预约」并允许点它取消 —— 但 `2` 也可能是别人的预约。
 */
enum class SeatStatus {
    /** 空闲可约 */
    AVAILABLE,

    /** 已被预约（含他人） */
    RESERVED,

    /** 我已预约（由「我的预约」交叉标记） */
    MINE,

    /** 在用 */
    IN_USE,

    /** 临时离开 */
    LEAVE,

    /** 暂停使用 / 未知 */
    UNAVAILABLE;

    /** 是否可选为预约目标（单人预约只允许空位） */
    val isFree: Boolean get() = this == AVAILABLE
}

data class Seat(
    val id: String,
    val no: String,
    val status: SeatStatus,
    val areaId: String? = null,
    /** 服务端原文状态名（如「空闲」「已预约」），比数字更稳定，仅用于展示/排查 */
    val statusName: String? = null,
    /** 底图坐标：**百分比**（服务端实测 55/55 全部有值，x 10.2~82.8、y 15.9~90.2） */
    val pointX: Float? = null,
    val pointY: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
) {
    /** 是否具备底图定位能力（四项坐标齐全） */
    val hasMapPosition: Boolean
        get() = pointX != null && pointY != null && width != null && height != null

    /** 标记为「我已预约」。 */
    fun asMine(): Seat = if (status == SeatStatus.RESERVED) copy(status = SeatStatus.MINE) else this
}

/** `/api/seat/map` 返回的五张状态底图。 */
data class SeatMapImages(
    val free: String? = null,
    val book: String? = null,
    val close: String? = null,
    val leave: String? = null,
    val use: String? = null,
) {
    /** 按座位状态取底图：与服务端 `seat-<key>.jpg` 的命名一一对应。 */
    fun forStatus(status: SeatStatus): String? = when (status) {
        SeatStatus.AVAILABLE -> free
        SeatStatus.RESERVED, SeatStatus.MINE -> book
        SeatStatus.IN_USE -> use
        SeatStatus.LEAVE -> leave
        SeatStatus.UNAVAILABLE -> close
    }

    val isEmpty: Boolean
        get() = free == null && book == null && close == null && leave == null && use == null
}

data class SeatDate(
    val day: String,
    val segmentId: String,
    val start: String,
    val end: String
)

// ---------- 座位号处理（实测自真实服务端响应） ----------

/**
 * 座位号比较：把补零写法与裸数字视为相同。
 *
 * ⚠️ 实测服务端返回的座位号是**补零字符串**（`"001"`、`"002"`…），
 * 而用户习惯输入 `"1"`。早期实现用 `it.no == seatNo` 直接比较，
 * 填 `"1"` 时永远匹配不上 —— 表现为监控任务一直提示「未找到座位」但并不报错，
 * 属于静默失效。其余情况按去空格后的字符串比较。
 */
fun seatNumberEquals(actual: String, expected: String): Boolean {
    val a = actual.trim()
    val b = expected.trim()
    if (a == b) return true
    val na = a.toIntOrNull()
    val nb = b.toIntOrNull()
    return na != null && nb != null && na == nb
}

/**
 * 座位号排序：数字按**数值**排（`"10"` 在 `"9"` 之后，而不是之前），
 * 非数字的排在最后并按字符串排。
 *
 * 用于优先预约挑「最早的空位」。直接按字符串排会在座位号宽度不一致时给出错误顺序。
 */
val SeatNumberComparator: Comparator<String> =
    compareBy({ it.trim().toIntOrNull() ?: Int.MAX_VALUE }, { it.trim() })
