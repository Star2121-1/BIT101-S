package cn.bit101.android.data.repo

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.eclass.EclassActivityLogic
import cn.bit101.android.data.eclass.EclassDdlItem
import cn.bit101.android.data.eclass.EclassDdlLogic
import cn.bit101.android.data.net.WebViewCookieSync
import cn.bit101.android.data.net.base.APIManager
import cn.bit101.android.data.repo.base.EclassRepo
import cn.bit101.api.model.http.eclass.GetEclassActivitiesDataModel.Activity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EclassRepo] 的实现：课程列表 → 逐课程拉动态 → 纯逻辑映射。
 *
 * 依赖注入用的是 `APIManager`（data 层拿 `Bit101Api` 的标准入口），
 * eclass 的 Retrofit 挂在带 cookieJar 的 `schoolClient` 上（见 `Bit101ApiFactory`）。
 *
 * ## 两条链路共用一次取数
 *
 * DDL 与「动态」用的是**同一个接口**，区别只在筛选口径：
 *
 * | 产物 | 映射 | 筛选 |
 * |---|---|---|
 * | DDL 条目 | [EclassDdlLogic.toDdlItems] | 只留作业，按截止时间升序 |
 * | 动态 | [EclassActivityLogic.toActivities] | 全留，按时间倒序 |
 *
 * 取数与容错逻辑抽在 [fetchRawActivities] 里共用 —— 两边各写一遍的话，
 * 早晚会改歪一条（比如只在一处加了 try/catch）。
 */
@Singleton
internal class DefaultEclassRepo @Inject constructor(
    private val apiManager: APIManager,
    private val loginStatus: LoginStatus,
) : EclassRepo {

    private companion object {
        const val ECLASS_BASE = "https://zy-eclass.bit.edu.cn"
    }

    /** 一门课的原始 activities（还没映射）。 */
    private class CourseActivities(
        val courseId: Int,
        val courseName: String,
        val activities: List<Activity>,
    )

    override suspend fun isSessionAlive(): Boolean =
        runCatching { apiManager.api.eclass.getVisitedCourses() }
            .map { it.isSuccessful }
            .getOrDefault(false)

    override suspend fun fetchHomework(now: LocalDateTime): List<EclassDdlItem> =
        fetchRawActivities()
            .flatMap { EclassDdlLogic.toDdlItems(it.activities, it.courseName, now) }
            // 合并后再整体排序：单门课内部已排过，但跨课程需要重新排一次
            .sortedBy { it.time }

    override suspend fun fetchActivities(limit: Int): List<EclassActivityLogic.EclassActivity> =
        EclassActivityLogic.recent(
            fetchRawActivities().flatMap {
                EclassActivityLogic.toActivities(it.activities, it.courseId, it.courseName)
            },
            limit,
        )

    override fun syncCookies() {
        WebViewCookieSync.sync(loginStatus.cookieManager, listOf(ECLASS_BASE))
    }

    /**
     * 取数与映射的唯一入口：课程列表 → 逐课程并发拉 activities。
     *
     * ⚠️ **单个课程失败不该拖垮整次同步** —— 逐课程 try/catch，
     * 一门课超时/无权限只丢它自己。课程多、网络不稳时这一点很重要。
     */
    private suspend fun fetchRawActivities(): List<CourseActivities> {
        syncCookies()

        val courses = runCatching { apiManager.api.eclass.getVisitedCourses() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.visitedCourses
            .orEmpty()

        if (courses.isEmpty()) return emptyList()

        // 并发而不是串行：课程之间互不依赖，串行会让同步时间随课程数线性增长
        return coroutineScope {
            courses
                .map { course ->
                    async {
                        val activities = runCatching {
                            val resp = apiManager.api.eclass.getActivities(course.id)
                            if (resp.isSuccessful) resp.body()?.activities.orEmpty() else emptyList()
                        }.getOrDefault(emptyList())

                        CourseActivities(
                            courseId = course.id,
                            courseName = course.name,
                            activities = activities,
                        )
                    }
                }
                .awaitAll()
        }
    }
}
