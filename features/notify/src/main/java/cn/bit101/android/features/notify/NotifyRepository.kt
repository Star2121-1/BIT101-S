package cn.bit101.android.features.notify

import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.FALLBACK_TIME_TABLE
import cn.bit101.android.config.setting.base.NotifySettings
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.DDLScheduleRepo
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

        return NotifyLogic.plan(
            courses = courses,
            ddls = ddls,
            now = now,
            firstDay = firstDay,
            table = table,
            policy = policy,
            sentKeys = NotifySentStore.read(),
            seatSignIns = seatSignIns,
        )
    }

    /**
     * **worker 执行时的二次校验**：这条提醒现在还成立吗？
     *
     * 排期与执行之间用户可能：删了课、把 DDL 标记为完成、DDL 改期、
     * 座位预约被取消或已刷卡签到。
     * 只有仍然成立才发通知 —— 迟到的错误提醒比不发更烦人。
     */
    suspend fun stillValid(reminder: Reminder, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!enabled()) return false
        if (NotifySentStore.contains(reminder.key)) return false

        return when (reminder.kind) {
            ReminderKind.CLASS -> classStillValid(reminder, now)
            ReminderKind.DDL -> ddlStillValid(reminder, now)
            ReminderKind.SEAT_SIGN_IN -> seatStillValid(reminder, now)
        }
    }

    /**
     * 座位签到是否仍然待办：**当前**仍有一条未签到、且截止时刻未过的预约，
     * 座位号与提醒里的一致。
     *
     * ⚠️ 只比座位号、不比截止时刻 —— 服务端可能把时段微调（如整体延后几分钟），
     * 那种情况下用户仍然需要被提醒。
     */
    private suspend fun seatStillValid(reminder: Reminder, now: LocalDateTime): Boolean {
        val seatNo = NotifyLogic.seatNoOfKey(reminder.key) ?: return false
        val signIns = runCatching { seatReminderSource.pendingSignIns(now) }
            .getOrDefault(emptyList())
        return signIns.any { it.seatNo.trim() == seatNo }
    }

    /**
     * 课程是否仍然存在：那天（星期几）有课，且周次覆盖当周。
     *
     * ⚠️ 依据是**去重键里的上课日期**（`course:{id}:{date}:{节次}:{提前量}`），
     * 不是提醒时刻的日期 —— 提前量跨零点时两者不同。
     *
     * ⚠️ 刻意不比课程号：用户改了课名/教室，依然想被提醒。
     */
    private suspend fun classStillValid(reminder: Reminder, now: LocalDateTime): Boolean {
        val date = reminder.key.split(":").getOrNull(2)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return false
        // 已经过了那一天就别再发了（迟到的提醒只会让人困惑）
        if (now.toLocalDate().isAfter(date)) return false

        val courses = runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val week = NotifyLogic.weekOf(firstDay, date)
        return courses.any { course ->
            course.weekday == date.dayOfWeek.value &&
                (week <= 0 || NotifyLogic.weeksContains(course.weeks, week))
        }
    }

    /** DDL 是否仍然「未完成且未过期」（用户可能已提交、或老师改期）。 */
    private suspend fun ddlStillValid(reminder: Reminder, now: LocalDateTime): Boolean {
        val uid = reminder.key.split(":").getOrNull(1) ?: return false
        val ddls = runCatching { ddlRepo.getDDLByUIDs(listOf(uid)) }.getOrDefault(emptyList())
        return ddls.any { !it.done && it.time.isAfter(now) }
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
        )
    }.getOrNull()

    /** 供排期器判断「是否该排提醒」（总开关关掉时要把已排的清掉）。 */
    suspend fun enabled(): Boolean = runCatching { notifySettings.enabled.get() }.getOrDefault(false)
}
