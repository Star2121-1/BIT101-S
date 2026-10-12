package cn.bit101.android.data.repo

import cn.bit101.api.model.common.BuildingInfo
import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.FreeClassroomRepo
import cn.bit101.android.data.repo.base.StudySpotRepository
import cn.bit101.android.data.repo.base.TeachingAdjustmentRepo
import cn.bit101.android.data.school.CourseWeekdayLogic
import cn.bit101.android.data.school.StudySpotLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StudySpotRepository] 的实现。
 *
 * ## ⚠️ 两个绕不开的成本，都用上限兜住
 *
 * 1. **教室必须逐栋请求**（`getClassroomInfos` 要传 buildingId），而良乡校区有 30 栋
 *    ⇒ 只取前 [MAX_BUILDINGS] 栋。全拉既慢又容易打到学校接口的风控。
 * 2. **网络结果缓存 [SPOTS_TTL_MS]** —— 但**只缓存「楼 → 原始教室列表」这一层**。
 *
 * ## ⚠️⚠️ 缓存的边界：只缓存**与节次无关**的东西
 *
 * 早先缓存的是**已经按当前节次筛过的空闲结果**（`List<BuildingFree>`），于是：
 * 09:55 打开「我」页算出的空闲教室，10:05（已经换了一节课）还是那一份 ——
 * **界面在说上一节课的空闲情况**，而它看起来完全正常（数字照样有理有据）。
 * 「现在有没有课」不缓存是同一条道理，只是当时只想到了那一半。
 *
 * ⇒ 现在缓存的是 `getClassroomInfos` 的**原始返回**（楼 + 每间教室的占用节次串，
 *   与「现在几点」无关），**节次筛选用每次调用时的 `now` 现算**。
 *   既省掉网络，又不会因为跨了课间而说谎。
 */
@Singleton
internal class DefaultStudySpotRepository @Inject constructor(
    private val freeClassroomRepo: FreeClassroomRepo,
    private val coursesRepo: CoursesRepo,
    private val teachingAdjustmentRepo: TeachingAdjustmentRepo,
    private val courseScheduleSettings: CourseScheduleSettings,
) : StudySpotRepository {

    /** 一栋楼的原始教室列表 —— 缓存的就是它（与节次无关）。 */
    private class BuildingRooms(
        val building: BuildingInfo,
        val rooms: List<ClassroomInfo>,
    )

    @Volatile
    private var roomsCached: List<BuildingRooms> = emptyList()

    @Volatile
    private var roomsAtMillis = 0L

    override suspend fun suggest(now: LocalDateTime): StudySpotLogic.Suggestion? =
        load(now)?.let { StudySpotLogic.suggestionOf(it) }

    override suspend fun plan(now: LocalDateTime): StudySpotLogic.Plan? = load(now)

    /**
     * 三份数据拼一条建议的**唯一实现**（[suggest] 与 [plan] 都走它）。
     *
     * ⚠️ 时间表拿不到就返回 `null` —— 节次→钟点全靠它，没有它连「现在第几节」都不知道，
     *    给出任何建议都是瞎猜。
     */
    private suspend fun load(now: LocalDateTime): StudySpotLogic.Plan? = withContext(Dispatchers.IO) {
        val table = runCatching { courseScheduleSettings.timeTable.get() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null

        val ofDay = runCatching { coursesOfDay(now, table) }.getOrDefault(emptyList())

        // ⚠️ 判断本身**只有一份实现**，在 [StudySpotLogic]（桌面组件的自习页也调它）。
        //    这里只负责「把当天的课凑出来」，别把「节次落在区间内 + 10 分钟窗口」再写一遍。
        val course = StudySpotLogic.currentCourseOf(
            courses = ofDay,
            table = table,
            now = now.toLocalTime(),
        )

        // 「刚在哪栋楼上完课」—— 就近推断的位置代理（零权限、纯本地）。
        // ⚠️ 有窗口：太久以前下课的课不能拿来说「你在附近」（见 recentClassroomOf）。
        val lastClassroom = StudySpotLogic.recentClassroomOf(
            courses = ofDay,
            table = table,
            now = now.toLocalTime(),
        )

        // ⚠️ 空教室按**到达时刻**算，不是「现在」—— 正在上课的人到不了（见 arriveAtOf）
        val arriveAt = arriveAtOf(now, course)
        val spots = freeSpots(arriveAt, table)

        StudySpotLogic.planOf(
            at = arriveAt,
            table = table,
            course = course,
            booking = null,     // ⚠️ 见下
            buildings = spots,
            lastClassroom = lastClassroom,
        )
        // ⚠️ 座位预约传 null：解析格式在 features:seat，data 层拿不到
        //    （分支本身已实现且有单测，接上了传进来即可）
    }

    /**
     * 用户**实际会到那儿**的时刻。
     *
     * ⚠️ 正在（或马上要）上课时不传「现在」而传**下课钟点**：空教室是按节次算的，
     *    拿「现在这一节」的数说「下课后去某楼（N 间空）」—— 那个 N 是现在这一节的，
     *    人到了就已经换了一节，数字对不上（见 [StudySpotLogic.planOf] 的说明）。
     */
    private fun arriveAtOf(now: LocalDateTime, course: StudySpotLogic.NextCourse?): LocalTime =
        course?.endsAt ?: now.toLocalTime()

    /**
     * **当天**的课（已按星期几 / 教学周 / 放假补课过滤好）。
     *
     * ⚠️ 放假那天没有课（[CourseWeekdayLogic.effectiveWeekday] 返回 null）——
     *    补课日装的则是**被指定那天**的课表。
     */
    private suspend fun coursesOfDay(
        now: LocalDateTime,
        table: TimeTable,
    ): List<StudySpotLogic.CourseAt> {
        val date = now.toLocalDate()
        val entries = runCatching { teachingAdjustmentRepo.load() }.getOrNull()?.entries.orEmpty()
        val planOn: (LocalDate) -> cn.bit101.android.data.school.DayPlan? =
            { d -> entries.firstOrNull { it.date == d }?.plan }

        val weekday = CourseWeekdayLogic.effectiveWeekday(date, planOn) ?: return emptyList()
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val week = CourseWeekdayLogic.weekOf(firstDay, date)

        return runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
            .filter { it.weekday == weekday }
            .filter { week <= 0 || CourseWeekdayLogic.weeksContains(it.weeks, week) }
            .map {
                StudySpotLogic.CourseAt(
                    name = it.name,
                    classroom = it.classroom,
                    startSection = it.start_section,
                    endSection = it.end_section,
                )
            }
    }

    /**
     * 各楼各楼层在**当前时段**的空闲教室数。
     *
     * ⚠️ 节次在这里现算（`sectionFor(table, now)`），**缓存里没有节次这回事** ——
     *    见类注释里那条「缓存边界」。
     */
    private suspend fun freeSpots(now: LocalTime, table: TimeTable): List<StudySpotLogic.BuildingFree> {
        val section = StudySpotLogic.sectionFor(table, now)
        return fetchRooms().flatMap { freeGroupsOf(it.building, it.rooms, section, table) }
    }

    /**
     * 拉各栋楼的**原始**教室列表（唯一走网络的地方，其余全部本地现算）。
     *
     * ⚠️ **并发**拉各栋楼：串行时 8 栋楼要十几秒，表现成「进了页面十几秒后才冒出一张卡」。
     *    与「空教室」页 refreshAllClassroomInfo 的做法一致（那边也是一栋一个协程）。
     *    ⚠️ 用 awaitAll 而不是往共享 list 里写 —— 后者是并发写，顺序与结果都不确定。
     */
    private suspend fun fetchRooms(): List<BuildingRooms> {
        val cached = roomsCached
        if (cached.isNotEmpty() && System.currentTimeMillis() - roomsAtMillis < SPOTS_TTL_MS) {
            return cached
        }

        val campus = runCatching { freeClassroomRepo.getCurrentCampusCode().first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        val buildings = runCatching { freeClassroomRepo.getBuildingInfos(campus) }
            .getOrDefault(emptyList())

        val fetched = coroutineScope {
            buildings.take(MAX_BUILDINGS).map { building ->
                async {
                    // ⚠️ 一栋楼失败只丢它自己 —— 别的楼照样能给出建议
                    runCatching { freeClassroomRepo.getClassroomInfos(building.buildingIndex) }
                        .getOrNull()
                        ?.let { BuildingRooms(building, it) }
                }
            }.awaitAll().filterNotNull()
        }

        if (fetched.isNotEmpty()) {
            roomsCached = fetched
            roomsAtMillis = System.currentTimeMillis()
        }
        return fetched
    }

    /**
     * 一栋楼里按「楼层」分组的空闲教室数。
     *
     * ⚠️ [section] 为 `null` = 当天课都上完了，此时所有教室都空闲（见 [StudySpotLogic.isFreeAt]）；
     *    这时候**不算「还能空多久」**（`freeSectionsAhead = 0`）—— 全天剩下来的时间没有
     *    「到几点为止」这回事，硬编一个时间就是在骗人。
     */
    private fun freeGroupsOf(
        building: BuildingInfo,
        rooms: List<ClassroomInfo>,
        section: Int?,
        table: TimeTable,
    ): List<StudySpotLogic.BuildingFree> =
        rooms.filter { StudySpotLogic.isFreeAt(it.busyTimeStr, section) }
            .groupBy { StudySpotLogic.floorOf(it.classroomName, building.buildingName) }
            .map { (floor, list) ->
                StudySpotLogic.BuildingFree(
                    buildingId = building.buildingIndex,
                    buildingName = building.buildingName,
                    freeCount = list.size,
                    floor = floor,
                    // 这一层**至少有一间**能一直空下去的节数（各间取最大）
                    freeSectionsAhead = if (section == null) 0
                    else list.maxOf { StudySpotLogic.freeSectionsAhead(it.busyTimeStr, section, table.size) },
                )
            }

    companion object {
        /**
         * 最多拉几栋楼 —— 教室必须逐栋请求，全拉（良乡 30 栋）太重。
         *
         * ⚠️ **这是个已知的取舍，不是「就这样」**：它意味着 30 栋里只看前 8 栋，
         *    如果最好的楼排在后面，推荐就永远看不到它。放宽的代价是服务端压力与耗时
         *    （并发 30 个请求），需要实测才敢动 —— 见 docs。
         */
        const val MAX_BUILDINGS = 8

        /** 网络结果（各楼原始教室列表）的缓存时长。**与节次相关的计算一律不缓存**，见类注释。 */
        const val SPOTS_TTL_MS = 10 * 60_000L
    }
}
