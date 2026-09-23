package cn.bit101.android.data.eclass

import cn.bit101.api.model.http.eclass.GetEclassActivitiesDataModel.Activity
import cn.bit101.android.data.eclass.EclassActivityLogic.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 延河课堂「动态」纯逻辑的单测。
 *
 * 锁的是三件容易出错的事：**分类**（不猜 type）、**该用哪个时间**（作业看截止、
 * 资料看发布）、**排序**（最近的在前，没时间的排最后而不是被丢掉）。
 */
class EclassActivityLogicTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 23, 12, 0)

    private fun activity(
        id: Int = 1,
        title: String = "第二章课件",
        type: String? = "material",
        startTime: String? = "2026-09-22 09:30:00",
        endTime: String? = null,
        visibleEndAt: String? = null,
        submitTimes: Int? = null,
        isReviewHomework: Boolean? = null,
    ) = Activity(
        id = id,
        title = title,
        type = type,
        courseId = 20268,
        startTime = startTime,
        endTime = endTime,
        visibleEndAt = visibleEndAt,
        submitTimes = submitTimes,
        isReviewHomework = isReviewHomework,
    )

    // ------------------------------------------------------------ 分类

    @Test
    fun `带作业特有字段的判为作业`() {
        assertEquals(
            ActivityKind.HOMEWORK,
            EclassActivityLogic.kindOf(activity(type = "assignment", submitTimes = 3, endTime = "2026-09-30 23:59:00")),
        )
        assertEquals(
            ActivityKind.HOMEWORK,
            EclassActivityLogic.kindOf(activity(type = null, isReviewHomework = true, endTime = "2026-09-30 23:59:00")),
        )
    }

    @Test
    fun `material 判为资料`() {
        assertEquals(ActivityKind.MATERIAL, EclassActivityLogic.kindOf(activity(type = "material")))
    }

    @Test
    fun `像公告的 type 判为公告`() {
        assertEquals(ActivityKind.ANNOUNCEMENT, EclassActivityLogic.kindOf(activity(type = "announcement")))
        assertEquals(ActivityKind.ANNOUNCEMENT, EclassActivityLogic.kindOf(activity(type = "Bulletin")))
    }

    /** ⚠️ 认不出的 type 归 OTHER 并原样展示，**绝不猜**。 */
    @Test
    fun `认不出的 type 归为动态`() {
        assertEquals(ActivityKind.OTHER, EclassActivityLogic.kindOf(activity(type = "quiz")))
        assertEquals(ActivityKind.OTHER, EclassActivityLogic.kindOf(activity(type = null)))
    }

    // ------------------------------------------------------------ 时间选择

    @Test
    fun `作业用截止时间`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(
                activity(
                    type = "assignment",
                    submitTimes = 1,
                    startTime = "2026-09-20 08:00:00",
                    endTime = "2026-09-30 23:59:00",
                )
            ),
            courseId = 20268,
            courseName = "操作系统",
        )

        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59), list[0].time)
    }

    @Test
    fun `资料用发布时间`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(activity(type = "material", startTime = "2026-09-22 09:30:00")),
            courseId = 20268,
            courseName = "操作系统",
        )

        assertEquals(LocalDateTime.of(2026, 9, 22, 9, 30), list[0].time)
        assertEquals(ActivityKind.MATERIAL, list[0].kind)
        assertEquals("操作系统", list[0].courseName)
    }

    /** 作业缺 endTime 时回落到 visibleEndAt —— 与 DDL 侧的兜底保持一致。 */
    @Test
    fun `作业缺 endTime 时回落 visibleEndAt`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(
                activity(
                    type = "assignment",
                    submitTimes = 1,
                    startTime = "2026-09-20 08:00:00",
                    endTime = null,
                    visibleEndAt = "2026-10-01 12:00:00",
                )
            ),
            courseId = 1,
            courseName = "课",
        )

        assertEquals(LocalDateTime.of(2026, 10, 1, 12, 0), list[0].time)
    }

    @Test
    fun `标题为空的活动被丢掉`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(activity(title = "   "), activity(title = "有效")),
            courseId = 1,
            courseName = "课",
        )

        assertEquals(1, list.size)
        assertEquals("有效", list[0].title)
    }

    @Test
    fun `时间都解析不出时 time 为 null 但条目保留`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(activity(startTime = "乱七八糟", endTime = null)),
            courseId = 1,
            courseName = "课",
        )

        assertEquals(1, list.size)
        assertEquals(null, list[0].time)
    }

    // ------------------------------------------------------------ 排序

    @Test
    fun `最近的排在最前`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(
                activity(id = 1, title = "早", startTime = "2026-09-01 08:00:00"),
                activity(id = 2, title = "近", startTime = "2026-09-22 08:00:00"),
                activity(id = 3, title = "中", startTime = "2026-09-10 08:00:00"),
            ),
            courseId = 1,
            courseName = "课",
        )

        val recent = EclassActivityLogic.recent(list, limit = 10)

        assertEquals(listOf("近", "中", "早"), recent.map { it.title })
    }

    /** 没有时间的排在最后，但**不能丢** —— 宁可排后面也别让用户看不到。 */
    @Test
    fun `没有时间的排最后且不被丢弃`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(
                activity(id = 1, title = "无时间", startTime = "坏值"),
                activity(id = 2, title = "有时间", startTime = "2026-09-22 08:00:00"),
            ),
            courseId = 1,
            courseName = "课",
        )

        val recent = EclassActivityLogic.recent(list, limit = 10)

        assertEquals(listOf("有时间", "无时间"), recent.map { it.title })
    }

    @Test
    fun `按上限截断`() {
        val list = (1..10).map {
            EclassActivityLogic.EclassActivity(
                id = "$it",
                courseId = 1,
                courseName = "课",
                title = "第 $it 条",
                kind = ActivityKind.MATERIAL,
                time = LocalDateTime.of(2026, 9, 1, 0, 0).plusDays(it.toLong()),
                targetUrl = EclassDdlLogic.LOGIN_URL,
            )
        }

        val recent = EclassActivityLogic.recent(list, limit = 3)

        assertEquals(3, recent.size)
        assertEquals("第 10 条", recent[0].title)
    }

    // ------------------------------------------------------------ 时间文案

    @Test
    fun `相对时间文案`() {
        assertEquals("今天 09:30", EclassActivityLogic.agoText(LocalDateTime.of(2026, 9, 23, 9, 30), now))
        assertEquals("昨天 09:30", EclassActivityLogic.agoText(LocalDateTime.of(2026, 9, 22, 9, 30), now))
        assertEquals("3 天前", EclassActivityLogic.agoText(LocalDateTime.of(2026, 9, 20, 9, 30), now))
        assertEquals("8月25日", EclassActivityLogic.agoText(LocalDateTime.of(2026, 8, 25, 9, 30), now))
        assertEquals("", EclassActivityLogic.agoText(null, now))
    }

    @Test
    fun `组件用的短文案`() {
        assertEquals("09:30", EclassActivityLogic.shortAgoText(LocalDateTime.of(2026, 9, 23, 9, 30), now))
        assertEquals("昨天", EclassActivityLogic.shortAgoText(LocalDateTime.of(2026, 9, 22, 9, 30), now))
        assertEquals("3 天前", EclassActivityLogic.shortAgoText(LocalDateTime.of(2026, 9, 20, 9, 30), now))
        assertEquals("8/25", EclassActivityLogic.shortAgoText(LocalDateTime.of(2026, 8, 25, 9, 30), now))
        assertTrue(EclassActivityLogic.shortAgoText(null, now).isEmpty())
    }

    // ------------------------------------------------------------ 点击跳转目标

    /** 课程给了完整地址 → 就用它（落到课程页，而不是延河课堂首页）。 */
    @Test
    fun `有课程地址时跳到课程页`() {
        assertEquals(
            "https://zy-eclass.bit.edu.cn/user/courses/20268",
            EclassActivityLogic.openUrlOf("https://zy-eclass.bit.edu.cn/user/courses/20268"),
        )
    }

    /**
     * ⚠️ 只认完整地址：`url` 字段的真实取值没被印证过，
     * 万一是相对路径就直接交给 WebView 只会白屏，所以一律退回首页。
     */
    @Test
    fun `地址不完整时退回首页`() {
        val fallback = EclassDdlLogic.LOGIN_URL
        assertEquals(fallback, EclassActivityLogic.openUrlOf(null))
        assertEquals(fallback, EclassActivityLogic.openUrlOf(""))
        assertEquals(fallback, EclassActivityLogic.openUrlOf("   "))
        assertEquals(fallback, EclassActivityLogic.openUrlOf("/user/courses/20268"))
        assertEquals(fallback, EclassActivityLogic.openUrlOf("zy-eclass.bit.edu.cn/user/index"))
    }

    @Test
    fun `映射出的每条动态都带上跳转目标`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(activity(id = 1), activity(id = 2, title = "第三章作业", submitTimes = 1)),
            courseId = 20268,
            courseName = "操作系统",
            courseUrl = "https://zy-eclass.bit.edu.cn/user/courses/20268",
        )

        assertEquals(2, list.size)
        assertTrue(list.all { it.targetUrl == "https://zy-eclass.bit.edu.cn/user/courses/20268" })
    }

    /** 没传课程地址（老调用点）也不能崩：目标退回首页。 */
    @Test
    fun `没传课程地址时退回首页`() {
        val list = EclassActivityLogic.toActivities(
            activities = listOf(activity()),
            courseId = 20268,
            courseName = "操作系统",
        )

        assertEquals(EclassDdlLogic.LOGIN_URL, list[0].targetUrl)
    }
}
