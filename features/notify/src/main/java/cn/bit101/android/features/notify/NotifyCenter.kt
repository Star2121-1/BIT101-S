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
import cn.bit101.android.config.setting.base.AppRoutes
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.school.CampusNetLogic

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

    /** 网费不足提醒（校园网，与四类提醒独立：BIT101 不在线也该提醒）。 */
    const val CHANNEL_NETFEE = "netfee_reminder"

    /** 校园网流量提醒（270 GB 临近 / 300 GB 超限，各一次）。 */
    private const val CHANNEL_NETFLOW = "netflow_reminder"

    /** 点击通知打开 App 的入口（与组件共用同一套 `bit101_goto` 约定）。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
    private const val EXTRA_GOTO = "bit101_goto"

    /** 出分提醒用固定 id：多次出分只更新同一条，不刷屏。 */
    private const val NOTIFY_ID_SCORES = 41001

    /** 网费提醒固定 id：每天至多一条（NetFeeChecker 按天去重）。 */
    private const val NOTIFY_ID_NETFEE = 42001

    /** 流量提醒固定 id：一个周期内至多两条（270 / 300 GB），后发覆盖前一条。 */
    private const val NOTIFY_ID_NETFLOW = 42002

    /** 建一个渠道（幂等：已存在就跳过）。 */
    private fun ensureChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        importance: Int,
        description: String,
    ) {
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply { this.description = description }
        )
    }

    /** 建渠道。幂等，可重复调用（系统只在首次真正创建）。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        ensureChannel(manager, CHANNEL_CLASS, "上课提醒", NotificationManager.IMPORTANCE_DEFAULT, "上课前提醒，显示节次、课程名与教室")
        ensureChannel(manager, CHANNEL_DDL, "作业截止", NotificationManager.IMPORTANCE_HIGH, "作业/DDL 截止前提醒")
        ensureChannel(manager, CHANNEL_SEAT, "座位签到", NotificationManager.IMPORTANCE_HIGH, "预约座位的签到时限提醒（错过会记违约）")
        ensureChannel(manager, CHANNEL_SCORE, "出分提醒", NotificationManager.IMPORTANCE_DEFAULT, "有新课出分时提醒（通知里不含分数）")
        ensureChannel(manager, CHANNEL_NETFEE, "网费不足", NotificationManager.IMPORTANCE_DEFAULT, "校园网账户余额不足时提醒充值（每日至多一次）")
        ensureChannel(manager, CHANNEL_NETFLOW, "校园网流量", NotificationManager.IMPORTANCE_DEFAULT, "本月流量接近 / 超过 300 GB 限速阈值时提醒（每周期至多两条）")
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
            .setContentIntent(
                gotoPendingIntent(context, reminder.key.hashCode(), reminder.route, reminder.key.hashCode().toString())
            )
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
            .setContentIntent(gotoPendingIntent(context, NOTIFY_ID_SCORES, AppRoutes.SCORE, "scores"))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_SCORES, notification)
        }
    }

    /**
     * 发网费不足提醒（固定 id：每天至多一条，去重在 [NetFeeChecker]）。
     *
     * 跳转走「我」底栏页 —— 校园服务卡在那儿（内网详情页校外打不开，
     * 别把用户带到一张空页）。
     */
    fun notifyNetFee(context: Context, balance: Double) {
        ensureChannels(context)
        val text = "校园网余额仅剩 ¥%.2f（低于 ${NetFeeChecker.THRESHOLD_YUAN} 元），记得充值".format(balance)
        val notification = NotificationCompat.Builder(context, CHANNEL_NETFEE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("网费不足")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(gotoPendingIntent(context, NOTIFY_ID_NETFEE, PageShowOnNav.Mine.toPageData().value, "netfee"))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_NETFEE, notification)
        }
    }

    /**
     * 发校园网流量提醒（270 GB 临近 / 300 GB 超限）。
     *
     * 跳「我」页（校园服务卡在那儿）—— 详情页在校外打不开，别把用户带到空页。
     */
    fun notifyNetFlow(context: Context, alert: NetFlowChecker.Alert, usedBytes: Long) {
        ensureChannels(context)
        val used = CampusNetLogic.formatTraffic(usedBytes)
        val limit = CampusNetLogic.formatTraffic(NetFlowChecker.LIMIT_BYTES)
        val (title, text) = when (alert) {
            NetFlowChecker.Alert.Near ->
                "校园网流量接近限速阈值" to "本月已用 $used（限速阈值 $limit），超出后将限速"
            NetFlowChecker.Alert.Exceeded ->
                "校园网流量已超限速阈值" to "本月已用 $used，已超过 $limit，网速可能被限制"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_NETFLOW)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(gotoPendingIntent(context, NOTIFY_ID_NETFLOW, PageShowOnNav.Mine.toPageData().value, "netflow"))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_NETFLOW, notification)
        }
    }

    /** 跳 App 的 PendingIntent（data 唯一化，避免系统复用导致跳错页）。 */
    private fun gotoPendingIntent(
        context: Context,
        requestCode: Int,
        route: String,
        dataSuffix: String,
    ): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            putExtra(EXTRA_GOTO, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bit101://notify/$dataSuffix")
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
