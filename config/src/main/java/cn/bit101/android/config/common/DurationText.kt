package cn.bit101.android.config.common

import java.time.Duration

/**
 * 时长 → 人类可读文本：`42 分钟` / `1 小时` / `1 小时 5 分`。
 *
 * ## 为什么要放 config（共用一份）
 *
 * 同一件事（把时长说成人话）仓库里一度有两份实现：
 * - `SeatStatusLogic.durationText` —— `1 小时 5 分`
 * - `TaskListLogic.signInCountdown` —— `1 小时 5 分钟`
 *
 * 而组件（`features:widget`）**不依赖**座位模块 ⇒ 它没法复用座位侧那份 ⇒
 * 要么复制第三份（越来越乱），要么把这份放到**两边都依赖**的 `config` 里。
 *
 * ⚠️ 措辞差异（「分」vs「分钟」）**不在这里统一** ——
 * 改既有文案 = 改用户可见的东西 + 改一批测试，属于单独一轮的事，
 * 不该搭在「组件倒计时现算」这轮里顺手做掉。
 */
fun durationText(d: Duration): String {
    val total = d.toMinutes().coerceAtLeast(0)
    if (total < 60) return "$total 分钟"
    val h = total / 60
    val m = total % 60
    return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
}
