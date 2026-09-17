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
