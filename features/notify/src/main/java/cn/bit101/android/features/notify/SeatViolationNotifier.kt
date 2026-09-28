package cn.bit101.android.features.notify

import android.content.Context

/**
 * 座位违约提醒对外的入口（`features:seat` 用）。
 *
 * 单独包一层而不是直接把 [NotifyCenter] 改公开：渠道、图标、跳转这些展示细节
 * 只该有一个出口（见 `NotifyCenter` 的顶部注释），座位侧不该知道它们。
 *
 * ⚠️ 与 `SeatReminderSource`（签到**提醒**）是两件事：那条是排期的**事前**提醒，
 * 这条是事后**通知** —— 违约已经记上了，通知的价值是让人知道「现在累计几次」。
 */
object SeatViolationNotifier {

    fun notify(context: Context, title: String, text: String) {
        NotifyCenter.notifySeatViolation(context, title, text)
    }
}
