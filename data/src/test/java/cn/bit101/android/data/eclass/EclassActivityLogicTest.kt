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

    // ------------------------------------------------------------ 按设置过滤

    private fun item(
        id: Int = 1,
        title: String = "一条动态",
        kind: ActivityKind = ActivityKind.MATERIAL,
        time: LocalDateTime? = null,
    ) = EclassActivityLogic.EclassActivity(
        id = "$id",
        courseId = 1,
        courseName = "课",
        title = title,
        kind = kind,
        time = time,
        targetUrl = EclassDdlLogic.LOGIN_URL,
    )

    /** 默认设置（只看作业关、显示过期开）→ 原样全留。 */
    @Test
    fun `默认设置下全部保留`() {
        val list = listOf(
            item(1, "资料", ActivityKind.MATERIAL, LocalDateTime.of(2026, 9, 22, 9, 30)),
            item(2, "过期作业", ActivityKind.HOMEWORK, LocalDateTime.of(2026, 9, 20, 23, 59)),
            item(3, "公告", ActivityKind.ANNOUNCEMENT, LocalDateTime.of(2026, 9, 21, 9, 0)),
        )

        val kept = EclassActivityLogic.applySettings(
            activities = list,
            onlyHomework = false,
            showExpired = true,
            now = now,
        )

        assertEquals(listOf("资料", "过期作业", "公告"), kept.map { it.title })
    }

    @Test
    fun `只看作业时只留作业`() {
        val list = listOf(
            item(1, "资料", ActivityKind.MATERIAL, LocalDateTime.of(2026, 9, 22, 9, 30)),
            item(2, "作业", ActivityKind.HOMEWORK, LocalDateTime.of(2026, 9, 30, 23, 59)),
            item(3, "公告", ActivityKind.ANNOUNCEMENT, LocalDateTime.of(2026, 9, 21, 9, 0)),
        )

        val kept = EclassActivityLogic.applySettings(
            activities = list,
            onlyHomework = true,
            showExpired = true,
            now = now,
        )

        assertEquals(listOf("作业"), kept.map { it.title })
    }

    @Test
    fun `关闭过期显示时丢掉截止已过的作业`() {
        val list = listOf(
            item(1, "过期作业", ActivityKind.HOMEWORK, LocalDateTime.of(2026, 9, 20, 23, 59)),
            item(2, "未过期作业", ActivityKind.HOMEWORK, LocalDateTime.of(2026, 9, 30, 23, 59)),
        )

        val kept = EclassActivityLogic.applySettings(
            activities = list,
            onlyHomework = false,
            showExpired = false,
            now = now,
        )

        assertEquals(listOf("未过期作业"), kept.map { it.title })
    }

    /**
     * ⚠️ 过期判定**只针对作业**：资料 / 公告的 time 是发布时间，本来就都在过去，
     * 一起判会把它们全部误杀。
     */
    @Test
    fun `关闭过期显示不误杀资料与公告`() {
        val list = listOf(
            item(1, "旧资料", ActivityKind.MATERIAL, LocalDateTime.of(2026, 8, 1, 9, 0)),
            item(2, "旧公告", ActivityKind.ANNOUNCEMENT, LocalDateTime.of(2026, 8, 1, 9, 0)),
        )

        val kept = EclassActivityLogic.applySettings(
            activities = list,
            onlyHomework = false,
            showExpired = false,
            now = now,
        )

        assertEquals(2, kept.size)
    }

    /** 作业没时间（解析不出）时不能当成过期丢掉 —— 与排序里「没时间的保留」一致。 */
    @Test
    fun `没有时间的作业不当作过期`() {
        val list = listOf(item(1, "无时间作业", ActivityKind.HOMEWORK, time = null))

        val kept = EclassActivityLogic.applySettings(
            activities = list,
            onlyHomework = true,
            showExpired = false,
            now = now,
        )

        assertEquals(1, kept.size)
    }

    // ------------------------------------------------------------ 过滤 + 截断的顺序

    /**
     * ⚠️ 回归：**先过滤、后截断**。
     *
     * 反过来的话，「只看作业」会「吃不饱」—— 混合列表里作业稀疏，按上限先截出来的
     * 那几条里可能一条作业都没有。这里上限 2、列表是「作业 + 两条资料」：
     * 正确结果是那条作业**在**，而不是被资料挤出窗口。
     */
    @Test
    fun `只看作业时先过滤再截断`() {
        val list = listOf(
            item(1, "第三章作业", ActivityKind.HOMEWORK, now.plusDays(3)),
            item(2, "第二章课件", ActivityKind.MATERIAL, now.minusDays(1)),
            item(3, "第一章课件", ActivityKind.MATERIAL, now.minusDays(2)),
        )

        val visible = EclassActivityLogic.visible(
            activities = list,
            onlyHomework = true,
            showExpired = true,
            limit = 2,
            now = now,
        )

        assertEquals(listOf("第三章作业"), visible.map { it.title })
    }

    /** 上限在**过滤之后**生效：过滤后的条数多于上限时才截。 */
    @Test
    fun `上限在过滤之后生效`() {
        val list = (1..5).map {
            item(it, "作业$it", ActivityKind.HOMEWORK, now.plusDays(it.toLong()))
        }

        val visible = EclassActivityLogic.visible(
            activities = list,
            onlyHomework = false,
            showExpired = true,
            limit = 2,
            now = now,
        )

        assertEquals(2, visible.size)
        assertEquals(listOf("作业1", "作业2"), visible.map { it.title })
    }

    /** 上限为 0 / 负数时不应崩，也不应「全给」——那就是用户选了「不显示」。 */
    @Test
    fun `上限非正时返回空`() {
        val list = listOf(item(1, "作业", ActivityKind.HOMEWORK, now.plusDays(1)))

        assertEquals(
            0,
            EclassActivityLogic.visible(list, onlyHomework = false, showExpired = true, limit = 0, now = now).size,
        )
        assertEquals(
            0,
            EclassActivityLogic.visible(list, onlyHomework = false, showExpired = true, limit = -3, now = now).size,
        )
    }

    // ------------------------------------------------------------ 取数上限的放大

    /**
     * 取数时「只看作业」要**放大**请求条数，否则过滤后无米下锅（[EclassActivityLogic.visible]）。
     *
     * 放大不是无脑翻倍：要有硬顶 [EclassActivityLogic.MAX_FETCH_LIMIT]，
     * 否则用户把上限调大时请求量会失控。
     */
    @Test
    fun `只看作业时取数上限放大且不超硬顶`() {
        // 关着「只看作业」→ 原样，不多取
        assertEquals(60, EclassActivityLogic.fetchLimit(limit = 60, onlyHomework = false))
        assertEquals(0, EclassActivityLogic.fetchLimit(limit = 0, onlyHomework = false))

        // 开着 → 4 倍
        assertEquals(30 * 4, EclassActivityLogic.fetchLimit(limit = 30, onlyHomework = true))
        assertEquals(60 * 4, EclassActivityLogic.fetchLimit(limit = 60, onlyHomework = true))

        // 4 倍超过硬顶时被截到硬顶（100 * 4 = 400 → 240）
        assertEquals(
            EclassActivityLogic.MAX_FETCH_LIMIT,
            EclassActivityLogic.fetchLimit(limit = 100, onlyHomework = true),
        )
        assertEquals(
            EclassActivityLogic.MAX_FETCH_LIMIT,
            EclassActivityLogic.fetchLimit(limit = 1000, onlyHomework = true),
        )
    }

    /**
     * ⚠️ 回归本 bug 的核心：**混合列表里作业稀疏时，「只看作业」仍要拿满上限**。
     *
     * 列表按时间倒序，每 4 条才有一条作业。若先按上限截断再过滤（旧实现），
     * 上限 4 只能截到「作业、资料、资料、资料」，过滤后只剩 **1** 条；
     * 先按放大上限取数、过滤之后再截断，才拿得到 **4** 条作业。
     */
    @Test
    fun `混合列表里作业稀疏时只看作业仍能拿满上限`() {
        val raw = (1..42).map { i ->
            if (i % 4 == 1) {
                item(i, "作业$i", ActivityKind.HOMEWORK, now.minusDays(i.toLong()))
            } else {
                item(i, "资料$i", ActivityKind.MATERIAL, now.minusDays(i.toLong()))
            }
        }
        val limit = 4

        // 取数阶段按放大后的上限「取」这么多条 —— 模拟 repo 的截断
        val fetched = raw.take(EclassActivityLogic.fetchLimit(limit, onlyHomework = true))
        val visible = EclassActivityLogic.visible(
            activities = fetched,
            onlyHomework = true,
            showExpired = true,
            limit = limit,
            now = now,
        )

        assertEquals(limit, visible.size)
        assertTrue(visible.all { it.kind == ActivityKind.HOMEWORK })

        // 反向验证：不放大就「吃不饱」，只能拿到 1 条
        val withoutWiden = EclassActivityLogic.visible(
            activities = raw.take(limit),
            onlyHomework = true,
            showExpired = true,
            limit = limit,
            now = now,
        )
        assertEquals(1, withoutWiden.size)
    }
}
