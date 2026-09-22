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

    /** 点击通知打开 App 的入口（与组件共用同一套 `bit101_goto` 约定）。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
    private const val EXTRA_GOTO = "bit101_goto"

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
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setAutoCancel(true)
            .setPriority(
                if (reminder.kind == ReminderKind.DDL) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(gotoIntent(context, reminder))
            .build()

        // 权限被拒时 notify 会抛 SecurityException —— 静默放弃（设置页会提示去授权）
        runCatching {
            NotificationManagerCompat.from(context).notify(reminder.key.hashCode(), notification)
        }
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
