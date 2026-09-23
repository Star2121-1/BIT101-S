package cn.bit101.android.data.eclass

import cn.bit101.api.model.http.eclass.GetEclassActivitiesDataModel.Activity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 延河课堂「课程动态」的**纯逻辑**：分类 + 映射 + 排序 + 时间文案。
 *
 * ## 与 DDL 的关系
 *
 * DDL 只关心**作业**（`EclassDdlLogic` 负责挑出来）；而「动态」页要把
 * **课程里发生的一切**展示出来 —— 资料发布、作业、测试、公告都算。
 * 所以两者共用同一份 activities 数据，只是筛选口径不同：
 *
 * | 用途 | 筛选 |
 * |---|---|
 * | DDL 页 | 只留作业（有 `submitTimes` / `isReviewHomework` 之类字段） |
 * | 动态页 | 全留，按类型打标签（作业 / 资料 / 公告 / 动态） |
 *
 * ⚠️ 分类**刻意保守**：认不出的 `type` 一律归为 [ActivityKind.OTHER] 并原样展示，
 * 不编造类型标签 —— 服务端新增类型时界面不会崩、也不会误导。
 */
object EclassActivityLogic {

    /** 动态的类型（用于列表上的标签）。 */
    enum class ActivityKind(val label: String) {
        /** 作业（带截止时间，也会出现在 DDL 页）。 */
        HOMEWORK("作业"),

        /** 学习资料（课件、讲义）。 */
        MATERIAL("资料"),

        /** 公告 / 通知。 */
        ANNOUNCEMENT("公告"),

        /** 其它动态 —— `type` 认不出时的兜底。 */
        OTHER("动态"),
    }

    /** 一条动态（UI 直接可用）。 */
    data class EclassActivity(
        /** 活动 id（与 DDL 的 uid 同源：`eclass:{id}`）。 */
        val id: String,
        val courseId: Int,
        val courseName: String,
        val title: String,
        val kind: ActivityKind,
        /**
         * 展示用的时间。
         *
         * - 作业：**截止时间**（学生关心的是什么时候交）
         * - 资料 / 其它：**开始时间**（即发布时刻）
         */
        val time: LocalDateTime?,
        /**
         * 点这条动态时该打开的地址（**已保证可用**，见 [openUrlOf]）。
         *
         * 单条活动自身的 URL 服务端没给过（抓包时 activities 里没有可用地址），
         * 所以落到**这条动态所属的课程页** —— 动态就发生在课程里，
         * 比丢到延河课堂首页更接近用户想看的东西。
         */
        val targetUrl: String,
    )

    /** 已知的资料类型 —— 唯一确认过取值的 type。 */
    private const val TYPE_MATERIAL = "material"

    /** 看起来像公告的 type 片段（未实测，命中即归类，认不出就当 OTHER）。 */
    private val ANNOUNCEMENT_HINTS = listOf("announce", "bulletin", "notice", "notification")

    /**
     * 判断动态类型。
     *
     * ⚠️ 作业判定复用 [EclassDdlLogic.isHomework]（按「作业特有字段是否存在」），
     * 而不是枚举 `type`：作业的 `type` 真实取值至今未确认
     * （开学前几周课程里只有资料），枚举一旦猜错就是"一条作业都不显示"。
     */
    fun kindOf(activity: Activity): ActivityKind = when {
        EclassDdlLogic.isHomework(activity) -> ActivityKind.HOMEWORK
        activity.type.equals(TYPE_MATERIAL, ignoreCase = true) -> ActivityKind.MATERIAL
        ANNOUNCEMENT_HINTS.any { activity.type?.contains(it, ignoreCase = true) == true } ->
            ActivityKind.ANNOUNCEMENT
        else -> ActivityKind.OTHER
    }

    /**
     * 把接口返回的 activities 映射成动态列表。
     *
     * 标题为空的活动直接丢弃：列表里出现一行空白没有任何意义。
     *
     * [courseUrl] 是这门课的 `url` 字段（调用方从课程列表里带进来）——
     * 只用来决定点击跳转，缺失不影响列表本身。
     */
    fun toActivities(
        activities: List<Activity>,
        courseId: Int,
        courseName: String,
        courseUrl: String? = null,
    ): List<EclassActivity> {
        val targetUrl = openUrlOf(courseUrl)
        return activities.mapNotNull { activity ->
            val title = activity.title.trim()
            if (title.isEmpty()) return@mapNotNull null

            val kind = kindOf(activity)
            // 作业看截止时间；其余看开始时间。两个都没有 → time = null（界面显示占位）
            val time = if (kind == ActivityKind.HOMEWORK) {
                EclassDdlLogic.parseTime(activity.endTime)
                    ?: EclassDdlLogic.parseTime(activity.visibleEndAt)
                    ?: EclassDdlLogic.parseTime(activity.startTime)
            } else {
                EclassDdlLogic.parseTime(activity.startTime)
                    ?: EclassDdlLogic.parseTime(activity.endTime)
            }

            EclassActivity(
                id = activity.id.toString(),
                courseId = courseId,
                courseName = courseName,
                title = title,
                kind = kind,
                time = time,
                targetUrl = targetUrl,
            )
        }
    }

    /**
     * 点击跳转目标：**课程页地址**优先，拿不到时退回延河课堂首页（[EclassDdlLogic.LOGIN_URL]）。
     *
     * ⚠️ 只接受**完整地址**（`http://` / `https://` 开头）。
     * 课程里的 `url` 字段值至今没被真机印证过（抓包时响应前半段就截断了），
     * 万一是相对路径（`/courses/20268`），直接交给 WebView 只会白屏 ——
     * 退回首页至少用户还能自己点进课程。宁可少走一步，也不要白屏。
     */
    fun openUrlOf(courseUrl: String?): String {
        val url = courseUrl?.trim().orEmpty()
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            EclassDdlLogic.LOGIN_URL
        }
    }

    /**
     * 排序 + 截断：**最新的在前**。
     *
     * 动态是"最近发生了什么"，与 DDL（按截止时间从近到远）方向相反。
     * 时间缺失的排在最后（通常是服务端没给时间的活动），而不是被丢掉。
     */
    fun recent(activities: List<EclassActivity>, limit: Int): List<EclassActivity> =
        activities
            .sortedWith(
                compareByDescending<EclassActivity> { it.time != null }
                    .thenByDescending { it.time }
            )
            .take(limit.coerceAtLeast(0))

    /**
     * 相对时间文案：`今天` / `昨天` / `3 天前` / `8月25日` / `去年12月3日`。
     *
     * 与 DDL 的「剩余时间」相反 —— 这里都是过去时刻（资料已发布、作业已布置）。
     */
    fun agoText(time: LocalDateTime?, now: LocalDateTime): String {
        if (time == null) return ""

        val days = ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> "今天 ${hm(time)}"
            days == 1L -> "昨天 ${hm(time)}"
            days in 2..6 -> "$days 天前"
            time.year == now.year -> "${time.monthValue}月${time.dayOfMonth}日"
            else -> "${time.year}年${time.monthValue}月${time.dayOfMonth}日"
        }
    }

    /** 列表行右侧的短文案：`今天 09:30` 太长，组件上只用它。 */
    fun shortAgoText(time: LocalDateTime?, now: LocalDateTime): String {
        if (time == null) return ""
        val date: LocalDate = time.toLocalDate()
        val days = ChronoUnit.DAYS.between(date, now.toLocalDate())
        return when {
            days == 0L -> hm(time)
            days == 1L -> "昨天"
            days in 2..6 -> "$days 天前"
            else -> "${time.monthValue}/${time.dayOfMonth}"
        }
    }

    private fun hm(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)
}
