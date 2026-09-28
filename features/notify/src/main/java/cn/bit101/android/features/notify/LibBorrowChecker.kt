package cn.bit101.android.features.notify

import android.content.Context
import androidx.core.content.edit
import cn.bit101.android.data.school.LibBorrowLogic
import cn.bit101.android.data.school.LibBorrowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 图书馆借阅**到期提醒**：应还日前 [LibBorrowLogic.DUE_SOON_DAYS] 天、以及逾期后，
 * 各记录只提醒一次。
 *
 * ## 两条纪律
 *
 * 1. ⚠️ **只有「已登录且确实取到」才判** —— [LibBorrowResult.LoggedOut] /
 *    [LibBorrowResult.Failed] 一律直接返回。否则网络抖一下就会被当成
 *    「没有快到期的书」，**把该发的提醒静默吃掉**（这正是把「取不到」和
 *    「没有」分开的原因）。
 * 2. 正文**不含书名**（在 [LibBorrowLogic.noticeBody] 里锁死）——
 *    通知会显示在锁屏上。
 *
 * ## 为什么这里**没有**「首次只建基线、不通知」这一步
 *
 * 那条铁律针对的是**“检测到新增事件”**类提醒（如「新出现一条违约」）——
 * 首次同步会把存量当增量，发出**不实**的通知。
 * 这里通知说的是**当前事实**（「有 N 本已逾期」），存量本身就是要提醒的内容，
 * 首次运行就该说。⚠️ **别为了「一致」照搬那条规则，那会让逾期书永远不提醒。**
 */
object LibBorrowChecker {

    private const val PREF_NAME = "lib_due_alert"

    /** 已提醒过的记录键集合（见 `BorrowRecord.key`）。 */
    private const val KEY_ALERTED = "alerted_keys"

    /** 拿一次结果判一次。失败 / 未登录时**什么都不做**。 */
    suspend fun check(
        context: Context,
        result: LibBorrowResult?,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val records = result?.recordsOrNull ?: return

        withContext(Dispatchers.IO) {
            val alerts = LibBorrowLogic.alertRecords(records, now)
            if (alerts.isEmpty()) return@withContext

            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val alerted = prefs.getStringSet(KEY_ALERTED, emptySet()).orEmpty()
            // 只报「还没提醒过的」；已提醒过的不再重复打扰（同一本书一天只吵一次）
            val fresh = alerts.filter { it.key !in alerted }
            if (fresh.isEmpty()) return@withContext

            NotifyCenter.notifyLibDue(
                context,
                LibBorrowLogic.noticeTitle(alerts, now),
                LibBorrowLogic.noticeBody(alerts, now),
            )
            // commit=true（同步落盘）：apply 的异步写会被 force-stop 丢掉，一丢就重复提醒
            prefs.edit(commit = true) {
                putStringSet(KEY_ALERTED, alerted + fresh.map { it.key })
            }
        }
    }
}
