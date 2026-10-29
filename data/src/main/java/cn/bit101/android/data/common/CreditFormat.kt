package cn.bit101.android.data.common

/**
 * 学分的展示格式化。
 *
 * 学分改成 `Double` 之后（支持 1.5 这种小数学分），直接 `toString()` 会把整数学分
 * 显示成 `3.0` —— 用户看到的是课程信息里多出来的 `.0`，观感不对。
 * 规则：**整数就显示整数，有小数才显示小数**。
 */
object CreditFormat {

    /** 例：`3.0 → "3"`、`1.5 → "1.5"`、`2.50 → "2.5"`；非有限值回落 `"0"`。 */
    fun text(credit: Double): String = when {
        !credit.isFinite() -> "0"
        credit % 1.0 == 0.0 -> credit.toLong().toString()
        else -> credit.toString()
    }
}
