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
import java.time.Duration
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
 * 2. **请求结果缓存 [SPOTS_TTL_MS]** —— 但**只缓存空教室那份**。
 *    「现在有没有课」每次实时算：它是本地数据，且**缓存它会让状态说谎**
 *    （10:00 上课，10:05 打开却显示 9:55 缓存下来的「推荐空教室」）。
 */
@Singleton
internal class DefaultStudySpotRepository @Inject constructor(
    private val freeClassroomRepo: FreeClassroomRepo,
    private val coursesRepo: CoursesRepo,
    private val teachingAdjustmentRepo: TeachingAdjustmentRepo,
    private val courseScheduleSettings: CourseScheduleSettings,
) : StudySpotRepository {

    @Volatile
    private var spotsCached: List<StudySpotLogic.BuildingFree> = emptyList()

    @Volatile
    private var spotsAtMillis = 0L

    override suspend fun suggest(now: LocalDateTime): StudySpotLogic.Suggestion? =
        withContext(Dispatchers.IO) {
            val table = runCatching { courseScheduleSettings.timeTable.get() }.getOrNull()
            if (table.isNullOrEmpty()) return@withContext null

            val course = runCatching { currentCourse(now, table) }.getOrNull()
            val spots = freeSpots(now.toLocalTime(), table)

            // ⚠️ 座位预约传 null：解析格式在 features:seat，data 层拿不到
            //    （分支本身已实现且有单测，接上了传进来即可）
            StudySpotLogic.suggest(
                now = now.toLocalTime(),
                course = course,
                booking = null,
                buildings = spots,
            )
        }

    /** 当前正在上的课，或 [COMING_SOON_MINUTES] 内就要开始的课。 */
    private suspend fun currentCourse(now: LocalDateTime, table: TimeTable): StudySpotLogic.NextCourse? {
        val date = now.toLocalDate()
        val entries = runCatching { teachingAdjustmentRepo.load() }.getOrNull()?.entries.orEmpty()
        val planOf: (LocalDate) -> cn.bit101.android.data.school.DayPlan? =
            { d -> entries.firstOrNull { it.date == d }?.plan }

        // ⚠️ 放假那天没有课（effectiveWeekday 返回 null）
        val weekday = CourseWeekdayLogic.effectiveWeekday(date, planOf) ?: return null
        val firstDay = runCatching { courseScheduleSettings.firstDay.get() }.getOrNull()
        val week = CourseWeekdayLogic.weekOf(firstDay, date)

        val courses = runCatching { coursesRepo.getCoursesFromLocal().first() }
            .getOrDefault(emptyList())
            .filter { it.weekday == weekday }
            .filter { week <= 0 || CourseWeekdayLogic.weeksContains(it.weeks, week) }
        if (courses.isEmpty()) return null

        val section = StudySpotLogic.sectionFor(table, now.toLocalTime()) ?: return null

        // 正在上
        val ongoing = courses.firstOrNull { it.start_section <= section && section <= it.end_section }
        if (ongoing != null) {
            return StudySpotLogic.NextCourse(
                name = ongoing.name,
                classroom = ongoing.classroom,
                endsAt = table.getOrNull(ongoing.end_section - 1)?.endTime,
            )
        }

        // 马上要上（课间时 sectionFor 返回的已经是下一节）
        val start = table.getOrNull(section - 1)?.startTime ?: return null
        val minutes = Duration.between(now.toLocalTime(), start).toMinutes()
        if (minutes < 0 || minutes > COMING_SOON_MINUTES) return null

        val coming = courses.firstOrNull { it.start_section == section } ?: return null
        return StudySpotLogic.NextCourse(
            name = coming.name,
            classroom = coming.classroom,
            endsAt = table.getOrNull(coming.end_section - 1)?.endTime,
        )
    }

    /** 各楼各楼层在当前时段的空闲教室数。**只缓存这一份**（网络慢）。 */
    private suspend fun freeSpots(now: LocalTime, table: TimeTable): List<StudySpotLogic.BuildingFree> {
        val cached = spotsCached
        if (cached.isNotEmpty() && System.currentTimeMillis() - spotsAtMillis < SPOTS_TTL_MS) {
            return cached
        }

        val section = StudySpotLogic.sectionFor(table, now)
        val campus = runCatching { freeClassroomRepo.getCurrentCampusCode().first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        val buildings = runCatching { freeClassroomRepo.getBuildingInfos(campus) }
            .getOrDefault(emptyList())

        // ⚠️ **并发**拉各栋楼：串行时 8 栋楼要十几秒，表现成「进了页面十几秒后才冒出一张卡」。
        //    与「空教室」页 refreshAllClassroomInfo 的做法一致（那边也是一栋一个协程）。
        //    ⚠️ 用 awaitAll 而不是往共享 list 里写 —— 后者是并发写，顺序与结果都不确定。
        val grouped = coroutineScope {
            buildings.take(MAX_BUILDINGS).map { building ->
                async {
                    // ⚠️ 一栋楼失败只丢它自己 —— 别的楼照样能给出建议
                    runCatching { freeClassroomRepo.getClassroomInfos(building.buildingIndex) }
                        .getOrNull()
                        ?.let { rooms -> freeGroupsOf(building, rooms, section) }
                        .orEmpty()
                }
            }.awaitAll()
        }
        val out = grouped.flatten()

        if (out.isNotEmpty()) {
            spotsCached = out
            spotsAtMillis = System.currentTimeMillis()
        }
        return out
    }

    /** 一栋楼里按「楼层」分组的空闲教室数。 */
    private fun freeGroupsOf(
        building: BuildingInfo,
        rooms: List<ClassroomInfo>,
        section: Int?,
    ): List<StudySpotLogic.BuildingFree> {
        val out = mutableListOf<StudySpotLogic.BuildingFree>()
        rooms.filter { StudySpotLogic.isFreeAt(it.busyTimeStr, section) }
            .groupBy { StudySpotLogic.floorOf(it.classroomName, building.buildingName) }
            .forEach { (floor, list) ->
                out += StudySpotLogic.BuildingFree(
                    buildingId = building.buildingIndex,
                    buildingName = building.buildingName,
                    freeCount = list.size,
                    floor = floor,
                )
            }
        return out
    }

    companion object {
        /** 最多拉几栋楼 —— 教室必须逐栋请求，全拉（良乡 30 栋）太重。 */
        const val MAX_BUILDINGS = 8

        /** 多久内开始的课算「马上有课」—— 这时候推荐人去自习是蠢话。 */
        const val COMING_SOON_MINUTES = 10L

        /** 空教室结果的缓存时长。**「有没有课」不缓存**，见类注释。 */
        const val SPOTS_TTL_MS = 10 * 60_000L
    }
}
