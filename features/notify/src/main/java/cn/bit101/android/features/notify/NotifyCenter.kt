package cn.bit101.android.features.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData

/**
 * 通知的发送出口。
 *
 * 只有这一个地方创建渠道与发通知 —— 想改文案/图标/跳转都来这里，
 * 避免出现「同一个 App 两套座位通知」那种混乱（座位侧的前台服务通知保持原样，不走这里）。
 */
internal object NotifyCenter {

    /** 上课提醒。 */
    private const val CHANNEL_CLASS = "class_reminder"

    /** 作业截止 —— 更紧急，给 HIGH 让用户能在锁屏看到。 */
    private const val CHANNEL_DDL = "ddl_reminder"

    /**
     * 座位签到提醒。
     *
     * ⚠️ 也给 HIGH：错过签到会**记一次违约**，累计 5 次暂停 7 天
     * （见 `docs/seatlib-contract.md` 第 10 节）—— 这是有实际后果的提醒，
     * 值得让用户在锁屏就能看见。
     */
    private const val CHANNEL_SEAT = "seat_reminder"

    /**
     * 出分提醒。
     *
     * ⚠️ 通知里**只有课名、没有分数** —— 这是用户明确的隐私决定，
     * 想看分数必须点进 App。DEFAULT 优先级：它是「知道了就行」的信息型提醒。
     */
    private const val CHANNEL_SCORE = "score_reminder"

    /** 点击通知打开 App 的入口（与组件共用同一套 `bit101_goto` 约定）。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
    private const val EXTRA_GOTO = "bit101_goto"

    /** 出分提醒用固定 id：多次出分只更新同一条，不刷屏。 */
    private const val NOTIFY_ID_SCORES = 41001

    /** 建渠道。幂等，可重复调用（系统只在首次真正创建）。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_CLASS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CLASS,
                    "上课提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "上课前提醒，显示节次、课程名与教室" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_DDL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DDL,
                    "作业截止",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "作业/DDL 截止前提醒" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_SEAT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SEAT,
                    "座位签到",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "预约座位的签到时限提醒（错过会记违约）" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_SCORE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SCORE,
                    "出分提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "有新课出分时提醒（通知里不含分数）" }
            )
        }
    }

    /**
     * 发一条提醒。
     *
     * ⚠️ 通知 id 用**去重键的 hash**：同一个键重复发会覆盖同一条，而不是刷屏。
     */
    fun notify(context: Context, reminder: Reminder) {
        ensureChannels(context)

        val channel = when (reminder.kind) {
            ReminderKind.CLASS -> CHANNEL_CLASS
            ReminderKind.DDL -> CHANNEL_DDL
            ReminderKind.SEAT_SIGN_IN -> CHANNEL_SEAT
        }

        // DDL 与座位签到都值得「弹出来」：前者交不上去要扣分，
        // 后者错过会记违约（累计 5 次暂停 7 天）
        val urgent = reminder.kind != ReminderKind.CLASS

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setAutoCancel(true)
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(gotoIntent(context, reminder))
            .build()

        // 权限被拒时 notify 会抛 SecurityException —— 静默放弃（设置页会提示去授权）
        runCatching {
            NotificationManagerCompat.from(context).notify(reminder.key.hashCode(), notification)
        }
    }

    /**
     * 发出分提醒（一条汇总通知）。
     *
     * ⚠️ [text] 由 `ScoreLogic.summaryText` 生成，**保证不含分数** ——
     * 这里只负责展示，不做第二道判断。
     */
    fun notifyScores(context: Context, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_SCORE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("出分了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(gotoScoresIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_SCORES, notification)
        }
    }

    private fun gotoScoresIntent(context: Context): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            // 成绩在「网」页里（bit101.cn/score/）—— 跳到「网」这个底栏页，
            // 用户再点一次成绩入口。⚠️ 不能直接 navigate 到 Web 路由：
            // 那是带 url 参数的顶层路由，而 GotoRequest 的消费方（IndexScreen）
            // 只认底栏页的 route 值，写别的会被静默忽略（点了没反应）
            putExtra(EXTRA_GOTO, PageShowOnNav.BIT101Web.toPageData().value)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bit101://notify/scores")
        }
        return PendingIntent.getActivity(
            context,
            NOTIFY_ID_SCORES,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun gotoIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            putExtra(EXTRA_GOTO, reminder.route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // data 唯一化：不同提醒各自一个 PendingIntent，避免系统复用导致跳错页
            data = Uri.parse("bit101://notify/${reminder.key.hashCode()}")
        }
        return PendingIntent.getActivity(
            context,
            reminder.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
