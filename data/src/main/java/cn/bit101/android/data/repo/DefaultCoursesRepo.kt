package cn.bit101.android.data.repo

import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.database.BIT101Database
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.CustomScheduleEntity
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.data.database.entity.toEntity
import cn.bit101.android.data.net.SchoolCookieStore
import cn.bit101.android.data.net.base.APIManager
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.data.schedule.CourseOverlayLogic
import cn.bit101.api.model.common.SchoolCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

internal class DefaultCoursesRepo @Inject constructor(
    private val database: BIT101Database,
    private val apiManager: APIManager,
    private val courseScheduleSettings: CourseScheduleSettings,
    private val loginStatus: LoginStatus,
) : CoursesRepo {
    private suspend fun schoolCookies(): List<SchoolCookie> =
        SchoolCookieStore.snapshot(loginStatus.cookieManager.cookieStore)

    private suspend fun webVpn(): Boolean = loginStatus.webVpn.get()

    override suspend fun getCoursesFromNet(
        term: String
    ) = withContext(Dispatchers.IO) {
        if(term.isBlank()) throw Exception("term is empty error")

        logApiCall(TAG, "获取课程表(term=$term)", result = { "成功, 共 ${it.size} 条课程" }) {
            apiManager.api.schoolJxzxehall.getCourseSchedule(
                cookies = schoolCookies(),
                webVpn = webVpn(),
                term = term,
            ).map { it.toEntity() }
        }
    }

    override suspend fun getExamsFromNet(
        term: String
    ) = withContext(Dispatchers.IO) {
        if(term.isBlank()) throw Exception("term is empty error")

        logApiCall(TAG, "获取考试安排(term=$term)", result = { "成功, 共 ${it.size} 场考试" }) {
            apiManager.api.schoolJxzxehall.getExamList(
                cookies = schoolCookies(),
                webVpn = webVpn(),
                term = term,
            ).map { it.toEntity() }
        }
    }

    override suspend fun saveCourses(
        courses: List<CourseScheduleEntity>
    ) = withContext(Dispatchers.IO) {
        // 删掉所有课程
        database.coursesDao().deleteAllCourses()

        // 放入进现在获得的所有课程
        database.coursesDao().insertCourses(courses)
    }

    override suspend fun saveExams(
        exams: List<ExamScheduleEntity>
    ) = withContext(Dispatchers.IO) {
        database.examsDao().deleteAllExams()

        database.examsDao().insertExams(exams)
    }

    override fun getCoursesFromLocal(
        term: String,
    ) = database.coursesDao().getCoursesByTerm(term)
        .withOverlay(database.courseOverlayDao().getByTerm(term))

    /**
     * 指定学期 + 指定周。
     *
     * ⚠️ 这里**刻意不直接在 SQL 里按周过滤**（`getCoursesByTermWeek`）：
     * 那个 SQL 的 `weeks LIKE '%[n]%'` 作用在**原始表**上，而用户手动改过周次之后，
     * 原始行的周次可能压根不包含本周 → 这一行查不出来 → 合并时也就没有基准，
     * 用户的修改在那一周**凭空消失**。
     * 所以：先取整学期（含覆盖层合并），再在 Kotlin 侧按周过滤 —— 合并后的周次才是用户看到的。
     */
    override fun getCoursesFromLocal(
        term: String,
        week: Int
    ) = getCoursesFromLocal(term)
        .map { courses -> courses.filter { it.weeks.contains("[$week]") } }

    override fun getCoursesFromLocal() =
        database.coursesDao().getAllCourses()
            .withOverlay(database.courseOverlayDao().getAll())

    /**
     * 把**手动修改的覆盖层**合并进课表读数。
     *
     * 合并放在读路径是刻意的：调用方（App 表格、桌面组件、通知排期）都走
     * `getCoursesFromLocal`，谁也不用知道覆盖层的存在，也就不会有人漏掉它。
     */
    private fun Flow<List<CourseScheduleEntity>>.withOverlay(
        overlays: Flow<List<CourseOverlayEntity>>,
    ): Flow<List<CourseScheduleEntity>> = combine(overlays) { courses, overlayList ->
        CourseOverlayLogic.merge(courses, overlayList)
    }

    override fun getExamsFromLocal(
        term: String,
    ) = database.examsDao().getExamsByTerm(term)

    override fun getExamsFromLocal() = database.examsDao().getAllExams()

    override suspend fun addCustomSchedule(
        schedule: CustomScheduleEntity,
    ) = withContext(Dispatchers.IO) {
        database.customScheduleDao().insertSchedule(schedule)
    }

    override fun getCustomSchedules() = database.customScheduleDao().getAllSchedules()

    override suspend fun deleteCustomSchedule(scheduleEntity: CustomScheduleEntity) = withContext(Dispatchers.IO) {
        database.customScheduleDao().deleteSchedule(scheduleEntity)
    }

    override suspend fun updateCustomSchedule(scheduleEntity: CustomScheduleEntity) = withContext(Dispatchers.IO) {
        database.customScheduleDao().updateSchedule(scheduleEntity)
    }

    override suspend fun getTermListFromNet() = withContext(Dispatchers.IO) {
        logApiCall(TAG, "获取学期列表", result = { "成功: $it" }) {
            apiManager.api.schoolJxzxehall.getTermList(
                cookies = schoolCookies(),
                webVpn = webVpn(),
            )
        }
    }

    override suspend fun getCurrentTermFromNet() = withContext(Dispatchers.IO) {
        logApiCall(TAG, "获取当前学期", result = { "成功: $it" }) {
            apiManager.api.schoolJxzxehall.getCurrentTerm(
                cookies = schoolCookies(),
                webVpn = webVpn(),
            )
        }
    }

    override fun getCurrentTermFromLocal() = courseScheduleSettings.term.flow

    override suspend fun getFirstDayFromNet(
        term: String
    ) = withContext(Dispatchers.IO) {
        val firstDay = logApiCall(TAG, "获取学期开始日期(term=$term)", result = { "成功: $it" }) {
            apiManager.api.schoolJxzxehall.getFirstDay(
                cookies = schoolCookies(),
                webVpn = webVpn(),
                term = term,
            )
        }
        LocalDate.parse(firstDay)
    }

    override fun getFirstDayFromLocal() =
        courseScheduleSettings.firstDay.flow

    private companion object {
        const val TAG = "CoursesRepo"
    }
}
