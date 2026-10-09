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
     * 考试提醒。
     *
     * ⚠️ 也给 HIGH：考试只有一次机会，错过无法补救（不像上课迟到还能进教室）。
     * 必须在锁屏就能看见 —— 而且要给出**考场与座位号**。
     */
    private const val CHANNEL_EXAM = "exam_reminder"

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

    /**
     * 座位违约提醒。
     *
     * ⚠️ 与「签到提醒」是两件事：签到提醒是**事前**（还能补救），这条是**事后**
     * （已经记上了）。它仍然给 HIGH —— 用户需要知道自己离「暂停 7 天」还有多远，
     * 否则可能一路攒到 5 次才察觉。
     */
    private const val CHANNEL_SEAT_VIOLATION = "seat_violation_reminder"

    /**
     * 图书馆借阅到期提醒。
     *
     * ⚠️ 正文**不含书名** —— 通知会显示在锁屏上，书目比分数更私人
     * （与「出分通知只有课名」同一条边界，单测已锁）。
     */
    private const val CHANNEL_LIB_DUE = "lib_due_reminder"

    /**
     * 教学安排调整（补课）提醒。
     *
     * ⚠️ 给 HIGH：**缺课是真实损失**（点名、进度落下）。而且这条说的是「课表变了」——
     * 用户按平时的课表出门就会走错，必须在锁屏就能看见。
     */
    private const val CHANNEL_ADJUSTMENT = "adjustment_reminder"

    /** 点击通知打开 App 的入口（与组件共用同一套 `bit101_goto` 约定）。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
    private const val EXTRA_GOTO = "bit101_goto"

    /** 出分提醒用固定 id：多次出分只更新同一条，不刷屏。 */
    private const val NOTIFY_ID_SCORES = 41001

    /** 网费提醒固定 id：每天至多一条（NetFeeChecker 按天去重）。 */
    private const val NOTIFY_ID_NETFEE = 42001

    /** 流量提醒固定 id：一个周期内至多两条（270 / 300 GB），后发覆盖前一条。 */
    private const val NOTIFY_ID_NETFLOW = 42002

    /** 违约提醒固定 id：后发的覆盖前一条，不刷屏。 */
    private const val NOTIFY_ID_SEAT_VIOLATION = 43001

    /** 图书馆借阅提醒固定 id：后发的覆盖前一条。 */
    private const val NOTIFY_ID_LIB_DUE = 44001

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
        ensureChannel(manager, CHANNEL_EXAM, "考试提醒", NotificationManager.IMPORTANCE_HIGH, "考前一天与考前提醒，显示时间、考场与座位号")
        ensureChannel(manager, CHANNEL_SCORE, "出分提醒", NotificationManager.IMPORTANCE_DEFAULT, "有新课出分时提醒（通知里不含分数）")
        ensureChannel(manager, CHANNEL_NETFEE, "网费不足", NotificationManager.IMPORTANCE_DEFAULT, "校园网账户余额不足时提醒充值（每日至多一次）")
        ensureChannel(manager, CHANNEL_NETFLOW, "校园网流量", NotificationManager.IMPORTANCE_DEFAULT, "本月流量接近 / 超过 300 GB 限速阈值时提醒（每周期至多两条）")
        ensureChannel(manager, CHANNEL_SEAT_VIOLATION, "座位违约", NotificationManager.IMPORTANCE_HIGH, "预约未签到被记违约时提醒，并显示累计次数（累计 5 次暂停预约 7 天）")
        ensureChannel(manager, CHANNEL_LIB_DUE, "图书馆借阅", NotificationManager.IMPORTANCE_DEFAULT, "借阅图书临近应还日或已逾期时提醒（通知里不含书名）")
        ensureChannel(manager, CHANNEL_ADJUSTMENT, "课表调整", NotificationManager.IMPORTANCE_HIGH, "学校调休补课时提醒（如「明天按周四课表上课」）")
    }

    /**
     * 座位违约提醒（固定 id：后发覆盖前一条）。
     *
     * 跳座位页 —— 违约台账就在「我的预约」那块，用户要核对/撤销都得去那儿。
     */
    internal fun notifySeatViolation(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_SEAT_VIOLATION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                gotoPendingIntent(
                    context,
                    NOTIFY_ID_SEAT_VIOLATION,
                    PageShowOnNav.Seat.toPageData().value,
                    "seatviolation",
                )
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_SEAT_VIOLATION, notification)
        }
    }

    /**
     * 图书馆借阅到期提醒（固定 id：后发覆盖前一条）。
     *
     * 跳到「我」页 —— 借阅卡片与「校园服务」入口都在那儿
     * （⚠️ 跳转只认底栏页 route，见 MEMORY 的跳转纪律）。
     */
    internal fun notifyLibDue(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_LIB_DUE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                gotoPendingIntent(
                    context,
                    NOTIFY_ID_LIB_DUE,
                    PageShowOnNav.Mine.toPageData().value,
                    "libdue",
                )
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_LIB_DUE, notification)
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
            ReminderKind.EXAM -> CHANNEL_EXAM
            ReminderKind.ADJUSTMENT -> CHANNEL_ADJUSTMENT
        }

        // DDL / 座位签到 / 考试 / 课表调整都值得「弹出来」：DDL 交不上去要扣分，
        // 座位错过会记违约（累计 5 次暂停 7 天），考试只有一次机会，
        // 课表调整则是「按平时的课表出门会走错」
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
        val limit = CampusNetLogic.formatTraffic(CampusNetLogic.LIMIT_BYTES)
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
