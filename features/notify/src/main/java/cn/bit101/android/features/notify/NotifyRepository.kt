package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.FALLBACK_TIME_TABLE
import cn.bit101.android.config.setting.base.NotifySettings
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.DDLScheduleRepo
import cn.bit101.android.data.repo.base.TeachingAdjustmentRepo
import cn.bit101.android.data.school.DayPlan
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒的取数与策略组装。
 *
 * 只做三件事：读设置、读本地数据（走 data 模块的**公开仓库接口**，
 * 因为 DAO 是 `internal` 的）、把两者交给 [NotifyLogic]。
 * **不做任何 Android 通知操作** —— 那是 [NotifyCenter] 的职责。
 */
@Singleton
class NotifyRepository @Inject constructor(
    private val coursesRepo: CoursesRepo,
    private val ddlRepo: DDLScheduleRepo,
    private val notifySettings: NotifySettings,
    private val courseScheduleSettings: CourseScheduleSettings,
    /**
     * 座位侧的签到数据源。
     *
     * ⚠️ 这是**接口**，实现由 `features:seat` 提供并绑定（见 [SeatReminderSource] 的说明）。
     * notify 模块的代码并不依赖座位模块 —— 依赖方向依然是 `seat → notify`。
     */
    private val seatReminderSource: SeatReminderSource,
    /**
     * 教学安排调整（放假 / 调休 / 补课）。
     *
     * ⚠️ 它的实现**静默降级**（取不到就退回缓存、再不行返回 null），所以这里可以放心直接调；
     * 但返回 null 意味着「不知道有没有调整」—— [adjustmentRefreshed] 据此**选择不发**
     * （宁可漏一次，也不要按可能过期的安排发错）。
     */
    private val teachingAdjustmentRepo: TeachingAdjustmentRepo,
) {

    /**
     * 算出此刻该排的全部提醒。
     *
     * 任何一项取数失败都**按空处理**（提醒少发一条可以接受，崩掉整个 App 不行）——
     * 与组件侧 `WidgetRepository` 同一原则。
     */
    suspend fun plan(now: LocalDateTime = LocalDateTime.now()): List<Reminder> {
        val policy = policyOrNull() ?: return emptyList()

        val courses = runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
        // 只取未来（含刚过期一点的）DDL；过期与已完成在 NotifyLogic 里再过滤一次
        val ddls = runCatching { ddlRepo.getFutureDDL(now.minusDays(1)).first() }
            .getOrDefault(emptyList())
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val table = runCatching { courseScheduleSettings.timeTable.get() }.getOrNull()
            ?: FALLBACK_TIME_TABLE
        // 座位侧取不到（未登录 / 会话失效）时按空处理 —— 这条源是「有就更好」
        val seatSignIns = if (policy.seatEnabled) {
            runCatching { seatReminderSource.pendingSignIns(now) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        // 考试安排跟着课表一起同步到本地，不需要额外网络请求。
        // 取的是**全部学期**：往期的考试都已开考，会被 NotifyLogic 的时间过滤挡掉
        val exams = if (policy.examEnabled) {
            runCatching { coursesRepo.getExamsFromLocal().first() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        // 教学调整走它自己的缓存（12h TTL）+ 静默降级；取不到就按「没有调整」处理
        // —— 课表页那条提示条同时也会缺失，两边口径一致
        // ⚠️ **上课提醒也要用它**（放假那天不排、补课日按被指定那天排），
        //    所以不能只在 adjustmentEnabled 时才取
        val adjustments = if (policy.adjustmentEnabled || policy.classEnabled) {
            runCatching { teachingAdjustmentRepo.load() }.getOrNull()?.entries.orEmpty()
        } else {
            emptyList()
        }

        return NotifyLogic.plan(
            courses = courses,
            ddls = ddls,
            now = now,
            firstDay = firstDay,
            table = table,
            policy = policy,
            sentKeys = NotifySentStore.read(),
            seatSignIns = seatSignIns,
            exams = exams,
            adjustments = adjustments,
        )
    }

    /**
     * **worker 执行时的二次校验 + 标题校正**；返回 null = 这次不该发。
     *
     * 排期与执行之间用户可能：删了课、把 DDL 标记为完成、DDL 改期、
     * 座位预约被取消或已刷卡签到 —— 只有仍然成立才发通知，迟到的错误提醒比不发更烦人。
     *
     * ⚠️ 返回的是**可能改过标题**的提醒：三类提醒的标题用的是**相对时间**
     * （「10 分钟后上课」/「15 分钟后截止签到」/「1 小时后开考」），
     * 而 WorkManager 会被 Doze / 省电策略大幅延后（**实机迟到 2 小时 23 分钟**）。
     * 沿用排期时写死的数字就会**说假话**，所以到这里按实际剩余时间重算一次。
     */
    suspend fun refreshed(reminder: Reminder, now: LocalDateTime = LocalDateTime.now()): Reminder? {
        if (!enabled()) return null
        if (NotifySentStore.contains(reminder.key)) return null

        return when (reminder.kind) {
            ReminderKind.CLASS -> classRefreshed(reminder, now)
            ReminderKind.DDL -> if (ddlStillValid(reminder, now)) reminder else null
            ReminderKind.SEAT_SIGN_IN -> seatRefreshed(reminder, now)
            ReminderKind.EXAM -> examRefreshed(reminder, now)
            ReminderKind.ADJUSTMENT -> adjustmentRefreshed(reminder, now)
        }
    }

    /**
     * 座位签到是否仍然待办：**当前**仍有一条未签到、且截止时刻未过的预约，
     * 座位号与提醒里的一致。
     *
     * ⚠️ 只比座位号、不比截止时刻 —— 服务端可能把时段微调（如整体延后几分钟），
     * 那种情况下用户仍然需要被提醒。
     *
     * ⚠️ 标题按**实际剩余分钟**重算：座位是「发现得晚也必须立刻补发」，
     * 过了截止照发（`seatTitle` 对 ≤0 写「签到即将截止」），但不能沿用排期时的数字。
     */
    private suspend fun seatRefreshed(reminder: Reminder, now: LocalDateTime): Reminder? {
        val seatNo = NotifyLogic.seatNoOfKey(reminder.key) ?: return null
        val signIns = runCatching { seatReminderSource.pendingSignIns(now) }
            .getOrDefault(emptyList())
        if (signIns.none { it.seatNo.trim() == seatNo }) return null

        // 取不到截止时刻就保留原标题（宁可这条数字略旧，也不要因此漏发）
        val deadline = NotifyLogic.seatDeadlineOfKey(reminder.key) ?: return reminder
        val left = NotifyLogic.seatLeadAtFireTime(deadline, now)
        return reminder.copy(title = NotifyLogic.seatTitle(left))
    }

    /**
     * 课程是否仍然成立：那天（星期几）有课、周次覆盖当周，**且课还没开始**。
     *
     * ⚠️ 依据是**去重键里的上课日期**（`course:{id}:{date}:{节次}:{提前量}`），
     * 不是提醒时刻的日期 —— 提前量跨零点时两者不同。
     *
     * ⚠️ 刻意不比课程号：用户改了课名/教室，依然想被提醒。
     *
     * ⚠️⚠️ **必须拦「课已开始」**：WorkManager 会被系统大幅延后（**实机迟到 2 小时 23 分钟**），
     * 少了这一条就会在**下课之后**弹出排期时写好的「10 分钟后上课」
     * （2026-09-29 用户截图实证）。DDL 与考试早就有这道闸
     * （`time.isAfter(now)` / `examStartAt().isAfter(now)`），**上课提醒当初漏了**。
     */
    private suspend fun classRefreshed(reminder: Reminder, now: LocalDateTime): Reminder? {
        val date = NotifyLogic.dateOfKey(reminder.key) ?: return null
        // 已经过了那一天就别再发了（迟到的提醒只会让人困惑）
        if (now.toLocalDate().isAfter(date)) return null

        // ⚠️ 提前量取不到就**放弃这次提醒**（与 `examDateOfKey` 同一口径：宁可漏一次，
        //    也不要发错）。这不是失手 —— `courseKey` 的往返（键末段 == 排期时的提前量）
        //    由单测锁着，真改了键格式会先红在测试上，不会静默丢掉所有上课提醒。
        val lead = NotifyLogic.trailingLeadMinutes(reminder.key) ?: return null
        val left = NotifyLogic.classLeadAtFireTime(reminder.at, lead, now) ?: return null

        val courses = runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()

        // ⚠️ 二次校验要用**与排期时同一个口径**的有效星期几，否则补课日那条
        //    会在到点被自己判成「那天没课」而**静默丢掉**（周六查周四的课 → 查不到）。
        //    反过来，放假那天 [effectiveWeekday] 返回 null ⇒ 不补发，也是对的。
        val adjustments = runCatching { teachingAdjustmentRepo.load() }.getOrNull()?.entries.orEmpty()
        val weekday = NotifyLogic.effectiveWeekday(date) { d ->
            adjustments.firstOrNull { it.date == d }?.plan
        } ?: return null

        val week = NotifyLogic.weekOf(firstDay, date)
        val exists = courses.any { course ->
            course.weekday == weekday &&
                (week <= 0 || NotifyLogic.weeksContains(course.weeks, week))
        }
        if (!exists) return null

        return reminder.copy(title = NotifyLogic.classTitle(left))
    }

    /** DDL 是否仍然「未完成且未过期」（用户可能已提交、或老师改期）。标题中立，无需校正。 */
    private suspend fun ddlStillValid(reminder: Reminder, now: LocalDateTime): Boolean {
        val uid = reminder.key.split(":").getOrNull(1) ?: return false
        val ddls = runCatching { ddlRepo.getDDLByUIDs(listOf(uid)) }.getOrDefault(emptyList())
        return ddls.any { !it.done && it.time.isAfter(now) }
    }

    /**
     * 考试是否仍然成立：**那天仍有考试**且**尚未开考**。
     *
     * ⚠️ 依据是去重键里的考试日期（`exam:{课程号或课名}:{日期}:{窗口}`）。
     * 刻意**不比具体时刻、也不比教室** —— 服务端把 08:00 微调成 08:30、
     * 或临时换考场，用户依然需要被提醒；比了反而漏提醒。
     *
     * ⚠️ 考试**过了时刻不补发**（与座位相反）；标题同样按实际剩余时间重算，
     * 免得迟到的通知还在说「1 小时后开考」。
     */
    private suspend fun examRefreshed(reminder: Reminder, now: LocalDateTime): Reminder? {
        val date = NotifyLogic.dateOfKey(reminder.key) ?: return null

        val exams = runCatching { coursesRepo.getExamsFromLocal().first() }
            .getOrDefault(emptyList())
        val exam = exams.firstOrNull { it.date == date && NotifyLogic.examStartAt(it).isAfter(now) }
            ?: return null

        val startAt = NotifyLogic.examStartAt(exam)
        val left = NotifyLogic.examLeadAtFireTime(startAt, now) ?: return null
        return reminder.copy(title = NotifyLogic.examTitle(left))
    }

    /**
     * 补课是否仍然成立：**那天仍是 `MakeUp`**，且**还没到那天**。
     *
     * ⚠️ 与考试同向：补课当天才弹「按周四上课」是句废话（人都该出门了）⇒ 过时不发。
     * 标题也按**到点时的实际日期**重算（「明天」→「今天」）—— 排期到执行之间
     * WorkManager 可能被 Doze 延后，沿用排期时的「明天」就会说假话。
     *
     * ⚠️ 取不到教学调整数据时**不发**（而不是按缓存发）：学校可能已经改了安排，
     * 而我们手上没有权威信息。宁可漏一次，也不要让用户按错的课表出门。
     * （这与课表页「取不到就静默降级」不是一回事：那边降级的是**展示**，
     * 这边降级会变成**主动发出的一条错通知**。）
     */
    private suspend fun adjustmentRefreshed(reminder: Reminder, now: LocalDateTime): Reminder? {
        val date = NotifyLogic.dateOfKey(reminder.key) ?: return null

        val adjustments = runCatching { teachingAdjustmentRepo.load() }.getOrNull() ?: return null
        val plan = adjustments.entries.firstOrNull { it.date == date }?.plan
        if (plan !is DayPlan.MakeUp) return null

        val title = NotifyLogic.adjustmentTitleAtFireTime(date, plan.targetWeekday, now)
            ?: return null
        return reminder.copy(title = title)
    }

    /** 读设置组装策略；设置读取失败时**返回 null**（宁可这次不排，也不要按错误策略发）。 */
    private suspend fun policyOrNull(): NotifyPolicy? = runCatching {
        if (!notifySettings.enabled.get()) null
        else NotifyPolicy(
            classEnabled = notifySettings.classEnabled.get(),
            classLeadMinutes = notifySettings.classLeadMinutes.get(),
            ddlEnabled = notifySettings.ddlEnabled.get(),
            ddlDayEnabled = notifySettings.ddlDayEnabled.get(),
            ddlHourEnabled = notifySettings.ddlHourEnabled.get(),
            seatEnabled = notifySettings.seatEnabled.get(),
            seatSignInLeadMinutes = notifySettings.seatSignInLeadMinutes.get(),
            examEnabled = notifySettings.examEnabled.get(),
            examDayEnabled = notifySettings.examDayEnabled.get(),
            examLeadMinutes = notifySettings.examLeadMinutes.get(),
            adjustmentEnabled = notifySettings.adjustmentEnabled.get(),
        )
    }.getOrNull()

    /** 供排期器判断「是否该排提醒」（总开关关掉时要把已排的清掉）。 */
    suspend fun enabled(): Boolean = runCatching { notifySettings.enabled.get() }.getOrDefault(false)

    /** 出分提醒是否打开（独立于总开关之外还要看这一项）。 */
    suspend fun scoreNotifyEnabled(): Boolean =
        enabled() && runCatching { notifySettings.scoreEnabled.get() }.getOrDefault(false)
}
