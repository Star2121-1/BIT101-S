package cn.bit101.android.data.repo

import android.util.Log
import cn.bit101.api.model.common.BuildingInfo
import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.repo.base.FreeClassroomRepo
import cn.bit101.android.data.repo.base.StudySpotRepository
import cn.bit101.android.data.repo.base.TeachingAdjustmentRepo
import cn.bit101.android.data.school.CampusLocations
import cn.bit101.android.data.school.CourseWeekdayLogic
import cn.bit101.android.data.school.DayPlan
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
 * ## ⚠️ 两个绕不开的成本
 *
 * 1. **教室必须逐栋请求**（`getClassroomInfos` 要传 buildingId）。良乡有 30 栋 ——
 *    全拉太重，所以有上限与**优先序**，见 [pickBuildings]。
 * 2. **网络结果缓存 [SPOTS_TTL_MS]** —— 只缓存「楼 → 原始教室列表」这一层。
 *
 * ## ⚠️⚠️ 缓存的边界：只缓存**与节次无关**的东西
 *
 * 早先缓存的是**已经按当前节次筛过的空闲结果**，于是 09:55 打开「我」页算出的空闲教室，
 * 10:05（已经换了一节课）还是那一份 —— **界面在说上一节课的空闲情况**，
 * 而它看起来完全正常（数字照样有理有据）。
 *
 * ⇒ 现在缓存的是 `getClassroomInfos` 的**原始返回**（楼 + 每间教室的占用节次串，
 *   与「现在几点」无关），**节次筛选每次现算**。
 *   既省掉网络，又不会因为跨了课间而说谎；顺带让「按到达时刻算」变成零成本。
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

    /** 一次取数的全部中间结果（[plan] 与 [suggest] 各取自己需要的那部分）。 */
    private class Loaded(
        val table: TimeTable,
        val course: StudySpotLogic.NextCourse?,
        /** 用户**实际会到那儿**的时刻，见 [arriveAtOf] */
        val arriveAt: LocalTime,
        /** 教室级候选（App 卡片与详情页用） */
        val rooms: List<StudySpotLogic.RoomFree>,
        /** 楼级汇总（**只给组件**，见 [StudySpotLogic.suggest]） */
        val buildings: List<StudySpotLogic.BuildingFree>,
        /** 距离的参考点；`null` = 不给距离 */
        val reference: StudySpotLogic.Reference?,
        val lastClassroom: String?,
    )

    @Volatile
    private var roomsCached: List<BuildingRooms> = emptyList()

    @Volatile
    private var roomsAtMillis = 0L

    override suspend fun plan(now: LocalDateTime): StudySpotLogic.Plan? = load(now)?.let { l ->
        StudySpotLogic.planOf(
            at = l.arriveAt,
            table = l.table,
            course = l.course,
            // ⚠️ 座位预约传 null：解析格式在 features:seat，data 层拿不到
            //    （分支本身已实现且有单测，接上了传进来即可）
            booking = null,
            rooms = l.rooms,
            reference = l.reference,
        )
    }

    override suspend fun suggest(now: LocalDateTime): StudySpotLogic.Suggestion? = load(now)?.let { l ->
        StudySpotLogic.suggest(
            now = now.toLocalTime(),
            course = l.course,
            booking = null,
            buildings = l.buildings,
            lastClassroom = l.lastClassroom,
        )
    }

    /**
     * 取数的**唯一实现**（[plan] 与 [suggest] 都走它）。
     *
     * ⚠️ 时间表拿不到就返回 `null` —— 节次→钟点全靠它，没有它连「现在第几节」都不知道，
     *    给出任何建议都是瞎猜。
     */
    private suspend fun load(now: LocalDateTime): Loaded? = withContext(Dispatchers.IO) {
        val table = runCatching { courseScheduleSettings.timeTable.get() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null

        val allCourses = runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
        val ofDay = coursesOfDay(allCourses, now)

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
        val section = StudySpotLogic.sectionFor(table, arriveAt)

        // 用户自己的楼优先（他一定会去那些楼），见 pickBuildings
        val ownClassrooms = allCourses.mapNotNull { it.classroom.takeIf { c -> c.isNotBlank() } }.toSet()
        val raw = fetchRooms(ownClassrooms)

        Loaded(
            table = table,
            course = course,
            arriveAt = arriveAt,
            rooms = raw.flatMap { freeRoomsOf(it.building, it.rooms, section, table) },
            buildings = raw.flatMap { freeGroupsOf(it.building, it.rooms, section, table) },
            reference = referenceOf(course, lastClassroom),
            lastClassroom = lastClassroom,
        )
    }

    /**
     * 用户**实际会到那儿**的时刻。
     *
     * ⚠️ 正在（或马上要）上课时不传「现在」而传**下课钟点**：空教室是按节次算的，
     *    拿「现在这一节」的数说「下课后去某楼」—— 那是**现在**的数，人到了已经换了一节。
     */
    private fun arriveAtOf(now: LocalDateTime, course: StudySpotLogic.NextCourse?): LocalTime =
        course?.endsAt ?: now.toLocalTime()

    /**
     * 距离的**参考点**（用户大概在哪）。
     *
     * ⚠️ 只有两种情况**敢**拿它当位置代理：
     * 1. 正在上 / 马上要上的课 —— 人在那栋楼，或者正往那去
     * 2. **90 分钟内**刚下课 —— 人大概还在附近（窗口见 [StudySpotLogic.recentClassroomOf]）
     *
     * 其余情况一律**不给参考点**。不拿一个站不住的前提去算「离你多远」：
     * 晚上 9 点在宿舍，算「离上午上课的理教楼多远」毫无意义。
     */
    private fun referenceOf(
        course: StudySpotLogic.NextCourse?,
        lastClassroom: String?,
    ): StudySpotLogic.Reference? = when {
        course != null && course.classroom.isNotBlank() -> StudySpotLogic.Reference(
            classroom = course.classroom,
            label = if (course.ongoing) "你现在上课" else "你下节课",
        )

        lastClassroom != null -> StudySpotLogic.Reference(lastClassroom, "你上一节课")

        else -> null
    }

    /**
     * **当天**的课（已按星期几 / 教学周 / 放假补课过滤好）。
     *
     * ⚠️ 放假那天没有课（[CourseWeekdayLogic.effectiveWeekday] 返回 null）——
     *    补课日装的则是**被指定那天**的课表。
     */
    private suspend fun coursesOfDay(
        allCourses: List<CourseScheduleEntity>,
        now: LocalDateTime,
    ): List<StudySpotLogic.CourseAt> {
        val date = now.toLocalDate()
        val entries = runCatching { teachingAdjustmentRepo.load() }.getOrNull()?.entries.orEmpty()
        val planOn: (LocalDate) -> DayPlan? = { d -> entries.firstOrNull { it.date == d }?.plan }

        val weekday = CourseWeekdayLogic.effectiveWeekday(date, planOn) ?: return emptyList()
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val week = CourseWeekdayLogic.weekOf(firstDay, date)

        return allCourses
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
     * 该拉哪几栋楼。
     *
     * ## ⚠️⚠️ 原来是「服务端顺序的前 8 栋」，真机上直接看到了后果
     *
     * 2026-10-12 实测：良乡服务端顺序的**前 8 栋恰好全是「文萃楼\*」** ⇒
     * **综教A/B、理科楼、前沿楼永远进不了候选**。当天的原话级证据：
     * 用户**正坐在综教B301**，推荐里一条综教都没有；下一节课在**理教楼406**，
     * 7 个候选仍然全是文萃楼。
     *
     * ## 两条改动
     *
     * 1. **用户自己课表里出现过的楼排在最前** —— 他一定会去那些楼，
     *    而且「就在你下节课那栋楼」这类理由只有它们在时才成立
     * 2. 上限从 8 放宽到 [MAX_BUILDINGS]
     *
     * ⚠️ 「是不是用户自己的楼」**走坐标表的别名匹配**，不用字符串前缀 ——
     *    课表里写的是「理教楼406」，而接口给的楼名是「理科教学大楼」/「理科教学楼」，
     *    前缀比对**一对都认不出来**（2026-10-12 真机：用户正在理科教学楼上课，
     *    推荐里却一间理科教学楼的教室都没有）。别名表是唯一认得全两边的地方。
     *
     * ⚠️ 放宽会增加请求数（并发发）。实际耗时看 `StudySpot` 标签下的日志
     *    （⚠️ 开发机 NP05J 的 logcat 是**被压掉的**，那儿读不到；换机器时才有用）。
     */
    private fun pickBuildings(
        buildings: List<BuildingInfo>,
        ownClassrooms: Set<String>,
    ): List<BuildingInfo> {
        if (buildings.size <= MAX_BUILDINGS) return buildings

        // 用户自己的楼 = 他那几个教室匹配出的地点，与楼名匹配出的地点**同一个**
        val ownLocations = ownClassrooms.mapNotNull { CampusLocations.match(it)?.name }.toSet()
        val mine = buildings.filter { b ->
            val location = CampusLocations.match(b.buildingName)?.name
            location != null && location in ownLocations
        }
        val mineSet = mine.toSet()
        return (mine + buildings.filterNot { it in mineSet }).take(MAX_BUILDINGS)
    }

    /**
     * 拉各栋楼的**原始**教室列表（唯一走网络的地方，其余全部本地现算）。
     *
     * ⚠️ **并发**拉各栋楼：串行时 8 栋楼就要十几秒，表现成「进了页面十几秒后才冒出一张卡」。
     *    ⚠️ 用 `awaitAll` 而不是往共享 list 里写 —— 后者是并发写，顺序与结果都不确定。
     */
    private suspend fun fetchRooms(ownClassrooms: Set<String>): List<BuildingRooms> {
        val cached = roomsCached
        if (cached.isNotEmpty() && System.currentTimeMillis() - roomsAtMillis < SPOTS_TTL_MS) {
            return cached
        }

        val campus = runCatching { freeClassroomRepo.getCurrentCampusCode().first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        val buildings = runCatching { freeClassroomRepo.getBuildingInfos(campus) }
            .getOrDefault(emptyList())
        val picked = pickBuildings(buildings, ownClassrooms)

        val startedAt = System.currentTimeMillis()
        val fetched = coroutineScope {
            picked.map { building ->
                async {
                    // ⚠️ 一栋楼失败只丢它自己 —— 别的楼照样能给出建议。
                    // ⚠️ **空列表也算失败**：真实教学楼不会一间教室都没有，
                    //    拿到空列表基本就是「会话没就绪 / 被重定向到登录页」。
                    //    不挡的话会被当成「这栋楼 0 间空闲」**缓存 10 分钟**，
                    //    表现是「整块推荐消失」，而界面看起来完全正常。
                    runCatching { freeClassroomRepo.getClassroomInfos(building.buildingIndex) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { BuildingRooms(building, it) }
                }
            }.awaitAll().filterNotNull()
        }

        // 排障用：一眼看出「请求了几栋 / 回来几栋 / 花了多久」。
        // ⚠️ 一栋都没回来时用 warn 级别 —— 那正是「静默降级成没有候选」的那种情况。
        val cost = System.currentTimeMillis() - startedAt
        if (fetched.isEmpty()) {
            Log.w(TAG, "一栋都没取到（请求 ${picked.size} 栋，耗时 $cost ms）")
        } else {
            Log.d(TAG, "取到 ${fetched.size}/${picked.size} 栋，耗时 $cost ms")
        }

        if (fetched.isNotEmpty()) {
            roomsCached = fetched
            roomsAtMillis = System.currentTimeMillis()
        }
        return fetched
    }

    /**
     * 一栋楼里按「楼层」分组的空闲教室数 —— **楼级汇总，只给组件用**。
     *
     * ⚠️ [section] 为 `null` = 当天课都上完了，此时所有教室都空闲（见 [StudySpotLogic.isFreeAt]）；
     *    这时候**不算「还能空多久」**（`freeSectionsAhead = 0`）—— 全天剩下来的时间
     *    没有「到几点为止」这回事，硬编一个时间就是在骗人。
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
                    else list.maxOf {
                        StudySpotLogic.freeSectionsAhead(it.busyTimeStr, section, table.size)
                    },
                )
            }

    /**
     * **每间**空闲教室各一条 —— 教室级候选（App 内用）。
     *
     * ⚠️ `freeSectionsAhead` 是**这一间自己**的连续空闲节数，
     *    与楼级那个「各层取最大」不同 —— 这正是教室级的意义（主语终于准确了）。
     */
    private fun freeRoomsOf(
        building: BuildingInfo,
        rooms: List<ClassroomInfo>,
        section: Int?,
        table: TimeTable,
    ): List<StudySpotLogic.RoomFree> {
        val free = rooms.filter { StudySpotLogic.isFreeAt(it.busyTimeStr, section) }
        if (free.isEmpty()) return emptyList()

        return free.map { room ->
            StudySpotLogic.RoomFree(
                buildingId = building.buildingIndex,
                buildingName = building.buildingName,
                classroomName = room.classroomName,
                floor = StudySpotLogic.floorOf(room.classroomName, building.buildingName),
                freeSectionsAhead = if (section == null) 0
                else StudySpotLogic.freeSectionsAhead(room.busyTimeStr, section, table.size),
                buildingFreeCount = free.size,
            )
        }
    }

    private companion object {
        const val TAG = "StudySpot"

        /**
         * 最多拉几栋楼。
         *
         * ⚠️ 原为 8 —— 那让良乡只剩「文萃楼\*」，见 [pickBuildings] 里记的真机证据。
         * 现在 16 + 「用户自己的楼优先」，覆盖到综教A/B、理科楼、理学楼。
         */
        const val MAX_BUILDINGS = 16

        /** 网络结果（各楼原始教室列表）的缓存时长。**与节次相关的计算一律不缓存**，见类注释。 */
        const val SPOTS_TTL_MS = 10 * 60_000L
    }
}
