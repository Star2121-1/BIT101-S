package cn.bit101.android.features.widget

import android.content.Context
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.config.user.base.SeatLoginStatus
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.DDLScheduleRepo
import cn.bit101.android.data.repo.base.EclassRepo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件的数据入口。
 *
 * 只做两件事：从各仓库取数据 → 交给 [WidgetLogic] 聚合。
 * 不含任何展示逻辑（那是 `WidgetViews` 的事），也不含业务判断（那是 [WidgetLogic] 的事）。
 *
 * ## ⚠️ 唯一一个「有状态」的地方：延河课堂动态的短时缓存
 *
 * 其余数据源都是**本地** Room 查询（几毫秒），怎么重绘都无所谓；
 * 只有「动态」是网络（1 次课程列表 + 每门课 1 次 activities）。而重绘的触发点很密：
 * 点页签、点 DDL 勾选、改尺寸、系统 onUpdate、周期任务……
 * 曾经每一下都真的发一轮请求 —— 表现是「切个页签要等好几秒」（网络差时更像卡死），
 * 外加白耗流量。现在由 [EclassActivityCache] 兜住：TTL 内直接复用，
 * 只有**显式刷新**（用户点刷新键、周期任务）才 [forceEclass] 真拉一次。
 */
@Singleton
class WidgetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coursesRepo: CoursesRepo,
    private val ddlRepo: DDLScheduleRepo,
    private val courseScheduleSettings: CourseScheduleSettings,
    private val loginStatus: LoginStatus,
    private val seatLoginStatus: SeatLoginStatus,
    /** 延河课堂的动态（第四页）。未登录时取不到，会走该页的登录引导。 */
    private val eclassRepo: EclassRepo,
) {

    /** 动态页的数据：列表 + 「是不是登录着」（前者非空时后者必然为真）。 */
    private class EclassData(
        val activities: List<EclassActivityLogic.EclassActivity>,
        val loggedIn: Boolean,
    )

    private val eclassCache = EclassActivityCache(EclassActivityCache.DEFAULT_TTL_MILLIS)
    private val cachedEclass = AtomicReference<EclassActivityCache.Entry<EclassData>?>(null)

    /** 同一时刻只让一个协程去拉动态：多个组件实例同时重绘时不会打出多轮请求。 */
    private val eclassMutex = Mutex()

    /**
     * 组装组件数据。
     *
     * 各数据源**并行且容错**：任何一个失败都不应让整个组件空白 ——
     * 课表拉不到就显示空课程页，但 DDL 页仍应正常。故每项单独 try/catch。
     *
     * @param forceEclass 无视缓存、重新拉一次延河课堂动态。
     *   只有「用户主动点刷新」「周期任务」这类**真的要最新**的场景才该传 true；
     *   页签切换、勾选 DDL 之类一律 false（它们只是重画，不是取数）。
     */
    suspend fun load(
        now: LocalDateTime = LocalDateTime.now(),
        forceEclass: Boolean = false,
    ): WidgetData {
        val today = now.toLocalDate()

        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()

        // ⚠️ 节次→时间用**课表设置里的时间表**，不是硬编码常量：
        //    用户可以自己编辑它（设置 → 课程表 → 时间表），学校改了作息也只需改设置。
        //    读不到才退回内置的学校官方默认表。
        val timeTable = runCatching { courseScheduleSettings.timeTable.get() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: WidgetLogic.FALLBACK_TIME_TABLE

        val courses = runCatching {
            coursesRepo.getCoursesFromLocal().first()
        }.getOrDefault(emptyList())

        // ⚠️ 取**全部** DDL（2026-09-23 用户要求「显示里面所有的 DDL，不要时间限制」）：
        // 以前只取「未来 14 天 + 过去 7 天」，于是截止在 16 天/41 天后的作业在组件上
        // 完全看不见 —— 用户在 App 里看得到、组件里看不到，只会当成 bug。
        // 分区（未完成 / 已完成）与条数上限在 WidgetLogic 里处理。
        val ddls = runCatching { ddlRepo.getAllDDL().first() }.getOrDefault(emptyList())

        val seat = runCatching { SeatWidgetSnapshot.read(context) }.getOrDefault(SeatWidgetSnapshot.Snapshot())

        val eclass = eclassData(fresh = forceEclass)

        // 登录态（与 App 内对应页面的门禁同一来源）。
        // ⚠️ 读取失败时按「已登录」处理（fail-open）：显示可能过期的数据，
        //    也好过把一个明明登录着的用户挡在「未登录」提示外面。
        val bit101LoggedIn = runCatching { loginStatus.status.get() }.getOrDefault(true)
        val seatLoggedIn = runCatching { seatLoginStatus.token.get() }
            .getOrNull()?.isNotBlank() ?: true

        return WidgetLogic.build(
            courses = courses,
            ddls = ddls,
            seat = seat,
            today = today,
            now = now,
            firstDay = firstDay,
            timeTable = timeTable,
            bit101LoggedIn = bit101LoggedIn,
            seatLoggedIn = seatLoggedIn,
            activities = eclass.activities,
            eclassLoggedIn = eclass.loggedIn,
        )
    }

    /**
     * 动态数据：命中新鲜缓存就直接复用，否则（只让一个协程）去拉。
     *
     * ⚠️ 拉失败时**保留上一次的结果**而不是清空 —— 动态拉不到（学校服务器抽风、
     * 网络抖动）不该让组件上的动态页突然变成空白 + 登录引导，
     * 那会让人以为「登录掉了」。
     */
    private suspend fun eclassData(fresh: Boolean): EclassData {
        val cached = cachedEclass.get()
        if (!fresh && eclassCache.isFresh(cached, System.currentTimeMillis())) {
            return cached!!.value
        }

        return eclassMutex.withLock {
            // 双检：等锁期间可能已被别的协程（另一个组件实例）刷新过
            val entry = cachedEclass.get()
            if (!fresh && eclassCache.isFresh(entry, System.currentTimeMillis())) {
                return@withLock entry!!.value
            }

            val previous = entry?.value
            val activities = runCatching { eclassRepo.fetchActivities() }
                .getOrElse { previous?.activities.orEmpty() }

            // 空的时候再问一次会话是否有效 —— 用来区分「没登录」和「登录了但没动态」，
            // 前者要显示登录引导，后者只显示空态。
            val data = EclassData(
                activities = activities,
                loggedIn = activities.isNotEmpty() ||
                    runCatching { eclassRepo.isSessionAlive() }.getOrDefault(false),
            )
            cachedEclass.set(
                EclassActivityCache.Entry(System.currentTimeMillis(), data)
            )
            data
        }
    }

    /**
     * 切换一条 DDL 的完成状态（组件里点条目即可，不必打开 App）。
     *
     * ⚠️ 只改 `done`，其余字段原样写回 —— 表里还有同步来的 title / time / group，
     * 漏掉任何一个都会把数据改坏。取不到这条（已被删除）时静默什么都不做。
     */
    suspend fun toggleDdlDone(uid: String) {
        val item = runCatching { ddlRepo.getDDLByUIDs(listOf(uid)).firstOrNull() }
            .getOrNull() ?: return
        runCatching { ddlRepo.updateDDL(item.copy(done = !item.done)) }
    }

    /**
     * 供 App 内部（课表同步、DDL 同步后）调用的刷新触发器。
     *
     * 组件不在前台，只能主动通知系统重绘；数据由 Provider 重新读一遍。
     *
     * ⚠️ 传 false：这些变更都在**本地**（课表 / DDL / 座位），
     * 跟延河课堂的动态无关，没必要为此多打一轮网络。
     */
    fun refresh() {
        runCatching { BIT101WidgetProvider.requestRenderAll(context) }
    }

}
