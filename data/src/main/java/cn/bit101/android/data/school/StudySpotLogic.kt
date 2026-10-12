package cn.bit101.android.data.school

import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.TimeTable
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 「现在去哪儿自习」的**纯逻辑**（便于单测，取数在调用方）。
 *
 * ## 判断顺序（越靠前越优先）
 *
 * 1. 现在 / 马上要上课 → 说上课地点（这时候推荐空教室是蠢话）
 * 2. 有进行中或 1 小时内的座位预约 → 说预约位置
 * 3. 否则 → 推荐**当前时段空闲最多**的那栋楼
 * 4. 取不到数据 → `null`（**不给瞎建议**，与提醒那套「取不到宁可不发」同一原则）
 *
 * ## 关于「当前时段」
 *
 * [sectionFor] 在课间返回的是**下一节**而不是 null —— 课间十分钟推荐人去自习，
 * 坐下就该上课了。全天课程结束后才返回 null（此时所有教室都算空闲）。
 */
object StudySpotLogic {

    /**
     * 一个「自习点」（**楼 + 楼层**）在当前时段的空闲情况。
     *
     * @param floor `null` 表示教室名没解析出楼层 —— 此时文案退回到只说楼名
     */
    data class BuildingFree(
        val buildingId: String,
        val buildingName: String,
        val freeCount: Int,
        val floor: Int? = null,
    )

    /**
     * 从教室名解析楼层。
     *
     * **真机样例**（2026-10-12 空教室页实测）：教室名 = 教学楼名 + 房间号 + **房间名后缀**：
     * `文萃楼H125良乡二号机房` / `文萃楼H221良乡四号机房`；也有不带后缀的 `文萃楼I101`。
     * 所以先去掉楼名，再取**第一段连续数字**。
     *
     * - 数字 3 位（`125`）→ 首位是楼层
     * - 数字 4 位（`1001`）→ 前两位是楼层（十层以上）
     * - 其它（解析不出 / 位数不符）→ `null`，**不给楼层**
     *
     * ⚠️ 必须取**第一段**数字，不能取末段 —— 后缀是汉字（「良乡二号机房」），
     *    取末段永远解析不出（v1.9.53 前的实现就是这么错的，界面上一律没有楼层）。
     *
     * ⚠️ 解析不出来就返回 null，绝不硬猜一个楼层出来 —— 显示错的楼层比不显示更糟。
     */
    fun floorOf(classroomName: String, buildingName: String): Int? {
        val rest = if (buildingName.isNotEmpty() && classroomName.startsWith(buildingName)) {
            classroomName.substring(buildingName.length)
        } else {
            classroomName
        }
        val digits = rest.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        return when (digits.length) {
            3 -> digits[0].digitToIntOrNull()
            4 -> digits.substring(0, 2).toIntOrNull()
            else -> null
        }
    }

    /** 显示用的地点名（带楼层则「楼名 N 楼」）。 */
    fun placeName(buildingName: String, floor: Int?): String =
        if (floor == null) buildingName else "${buildingName} ${floor} 楼"

    /** 当前或即将开始的一节课（**由调用方按 10 分钟窗口判好**再传进来）。 */
    data class NextCourse(
        val name: String,
        val classroom: String,
        /** 下课后才能去自习 —— 副文案要用 */
        val endsAt: LocalTime? = null,
        /** 是否**正在上**；`false` = 还没开始，只是快了（[currentCourseOf] 填）。 */
        val ongoing: Boolean = true,
        /** 开课钟点（[ongoing] 为 `false` 时用来写「10:00 上 X」）。 */
        val startsAt: LocalTime? = null,
    )

    /**
     * 「正在上 / 马上要上」那一句话。
     *
     * ⚠️ **只有这一份实现** —— App 内那张卡的标题与桌面组件自习页的那一行都用它，
     * 两边不许各写各的措辞（否则迟早一边说「正在上」另一边说「马上要上」）。
     * ⚠️ 用真实钟点而不是节次号：见 [currentCourseOf] 的说明。
     */
    fun courseLine(course: NextCourse): String = if (course.ongoing) {
        "正在上${course.name}（${course.classroom}）"
    } else {
        val at = course.startsAt?.truncatedTo(ChronoUnit.MINUTES)?.toString()
        if (at == null) "马上要上${course.name}（${course.classroom}）"
        else "$at 上${course.name}（${course.classroom}）"
    }

    /**
     * 判断「此刻算不算有课」所需的最小课程信息。
     *
     * ⚠️ 刻意**不与 Room 实体绑定**：App 内（读本地库）与桌面组件（读已过滤的当天课表）
     * 各自映射过来即可，纯逻辑保持可单测。
     * ⚠️ [startSection] / [endSection] 是 **1-based 节次**。
     */
    data class CourseAt(
        val name: String,
        val classroom: String,
        val startSection: Int,
        val endSection: Int,
    )

    /** 「马上有课」的窗口（分钟）。⚠️ App 内与桌面组件共用，别再各写一个 10。 */
    const val COMING_SOON_MINUTES = 10L

    /**
     * 当前正在上的课，或 [comingSoonMinutes] 分钟内就要开始的课；都没有则 `null`。
     *
     * ⚠️ 这是**唯一一份**「现在算不算有课」的判断 —— App 内那张卡（[DefaultStudySpotRepository]）
     *    与桌面组件的自习页都调它，别各自再写一遍「节次落在区间内 + 10 分钟窗口」。
     * ⚠️ [courses] 必须是**当天**的课（调用方负责按星期几 / 周次 / 放假补课过滤）。
     *
     * ## ⚠️⚠️ 为什么「正在上」必须按**真实钟点**判，不能按 [sectionFor] 的节次号
     *
     * [sectionFor] 的语义是「**课间返回下一节**」（空教室占用判定需要这个语义）。
     * 早先这里直接拿它当节次用（`start <= section <= end`），于是 **9:40-10:00 的课间
     * 会把 10:00 那节课判成「正在上」** —— 人还在路上，界面已经在说「正在上 X」。
     * 这个 bug 由单测 `课间十分钟内要上的课算马上要上` 抓出来（第一版期望值就写错了）。
     * ⇒ 「正在上」看 `now` 落在 `[开始, 结束]` 内；「马上要上」看距开课的分钟数。
     */
    fun currentCourseOf(
        courses: List<CourseAt>,
        table: TimeTable,
        now: LocalTime,
        comingSoonMinutes: Long = COMING_SOON_MINUTES,
    ): NextCourse? {
        // 节次 → 钟点；取不到钟点的课直接跳过（异常数据不该编出一个时间）
        val timed = courses.mapNotNull { course ->
            val start = table.getOrNull(course.startSection - 1)?.startTime ?: return@mapNotNull null
            val end = table.getOrNull(course.endSection - 1)?.endTime ?: return@mapNotNull null
            TimedCourse(course, start, end)
        }

        // 1. 正在上：now 落在真实钟点区间内（**不是**节次号比较）
        timed.firstOrNull { !now.isBefore(it.start) && !now.isAfter(it.end) }
            ?.let {
                return NextCourse(
                    name = it.course.name,
                    classroom = it.course.classroom,
                    endsAt = it.end,
                    ongoing = true,
                    startsAt = it.start,
                )
            }

        // 2. 马上要上：还没开始、且离得最近的那节在窗口内
        val next = timed.filter { now.isBefore(it.start) }.minByOrNull { it.start } ?: return null
        if (Duration.between(now, next.start).toMinutes() > comingSoonMinutes) return null

        return NextCourse(
            name = next.course.name,
            classroom = next.course.classroom,
            endsAt = next.end,
            ongoing = false,
            startsAt = next.start,
        )
    }

    /** [currentCourseOf] 内部用：课 + 它的真实起止钟点。 */
    private data class TimedCourse(
        val course: CourseAt,
        val start: LocalTime,
        val end: LocalTime,
    )

    /** 进行中或 1 小时内的座位预约。 */
    data class Booking(
        val place: String,
        val timeText: String,
    )

    enum class Kind { IN_CLASS, HAS_BOOKING, FREE_ROOM }

    data class Suggestion(
        val kind: Kind,
        /** 主文案（App 内用，含数量）。 */
        val title: String,
        /** 副文案。 */
        val detail: String,
        /** 推荐的那栋楼名 —— 组件只显示到「楼」这一级，不给具体数字（见 [coarseTitle]）。 */
        val buildingName: String? = null,
        /** 点进去要看的那栋楼（仅 [Kind.FREE_ROOM] 有）。 */
        val buildingId: String? = null,
        val freeCount: Int = 0,
    )

    /**
     * 组件用的粗粒度文案 —— **刻意不含数量**。
     *
     * ⚠️ 组件重绘不打网络，具体数字可能是几小时前的，写上去就是在骗人。
     * 所以组件只说「哪栋楼有空教室」，数字留给 App 内那张卡（现拉现算）。
     *
     * ⚠️ **上课中也走「哪栋楼有空教室」这一支**，不说「正在上 X」——
     *    「正在上什么」是**有时效的断言**，存进快照会在几小时后变成假话
     *    （组件真刷数据靠 30 分钟周期任务，Doze 下还会被拖到几小时）。
     *    组件改为**每次重绘用本地课表现算**那一句（见 `WidgetLogic.studyCourseLine`），
     *    快照只负责「哪栋楼有空教室」这件**需要网络**的事。
     */
    fun coarseTitle(suggestion: Suggestion): String? = when (suggestion.kind) {
        // ⚠️ 必须写 ${it}：Kotlin 的中文也算标识符字符，"$it现在" 会被当成一个名字
        Kind.FREE_ROOM, Kind.IN_CLASS -> suggestion.buildingName?.let { "${it}现在有空教室" }
        Kind.HAS_BOOKING -> suggestion.title
    }

    /**
     * 「当前时段」对应的节次（**1-based**，与 `busyTimeStr` 里的节次号一致）。
     *
     * - 正在上课 → 当前这一节
     * - 课间 / 第一节之前 → **下一节**（课间十分钟不推荐人去自习）
     * - 全天课程结束 → null（此时所有教室都算空闲）
     */
    fun sectionFor(table: TimeTable, now: LocalTime): Int? {
        table.forEachIndexed { index, item ->
            if (!now.isBefore(item.startTime) && now.isBefore(item.endTime)) return index + 1
        }
        table.forEachIndexed { index, item ->
            if (now.isBefore(item.startTime)) return index + 1
        }
        return null
    }

    /**
     * 解析占用节次。`busyTimeStr` 是逗号分隔的节次号（如 `1,2,5,6`）。
     *
     * ⚠️ **null / 空串 = 全天空闲**，不是「数据缺失」 —— 这是服务端构造时就定好的
     * （见 `SchoolClassroomService`：没有占用节次才给 null）。单条解析失败只跳过它自己。
     */
    fun parseBusySections(busyTimeStr: String?): Set<Int> =
        busyTimeStr
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

    /** 教室在当前时段是否空闲。 */
    fun isFreeAt(busyTimeStr: String?, section: Int?): Boolean {
        if (section == null) return true
        return section !in parseBusySections(busyTimeStr)
    }

    /** 一栋楼在当前时段的空闲教室数。 */
    fun freeCountOf(
        classrooms: List<ClassroomInfo>,
        table: TimeTable,
        now: LocalTime,
    ): Int {
        val section = sectionFor(table, now)
        return classrooms.count { isFreeAt(it.busyTimeStr, section) }
    }

    /**
     * 生成建议。**取不到数据（[buildings] 为空或全满）返回 null** —— 不给瞎建议。
     */
    fun suggest(
        now: LocalTime,
        course: NextCourse?,
        booking: Booking?,
        buildings: List<BuildingFree>,
    ): Suggestion? {
        val best = buildings.filter { it.freeCount > 0 }.maxByOrNull { it.freeCount }

        // 1. 有课 —— 这时候说「去哪自习」没有意义
        if (course != null) {
            val detail = if (best != null && course.endsAt != null) {
                "${course.endsAt}下课后，${placeName(best.buildingName, best.floor)}有 ${best.freeCount} 间空教室"
            } else {
                "地点：${course.classroom}"
            }
            return Suggestion(
                // ⚠️ 「正在上」与「马上要上」措辞分开 —— 课间那几分钟人还没进教室，
                //    说「正在上」是错的。措辞只有 [courseLine] 一份实现（组件也用）。
                kind = Kind.IN_CLASS,
                title = courseLine(course),
                detail = detail,
                buildingName = best?.buildingName,
                buildingId = best?.buildingId,
                freeCount = best?.freeCount ?: 0,
            )
        }

        // 2. 有预约 —— 已经安排好了，别再推荐别的地方
        if (booking != null) {
            return Suggestion(
                kind = Kind.HAS_BOOKING,
                title = "你 ${booking.timeText} 在 ${booking.place} 有座位预约",
                detail = "已约好，直接去就行",
            )
        }

        // 3. 空教室
        if (best == null) return null
        return Suggestion(
            kind = Kind.FREE_ROOM,
            title = "${placeName(best.buildingName, best.floor)}现在有 ${best.freeCount} 间空教室",
            detail = "当前时段空闲",
            buildingName = best.buildingName,
            buildingId = best.buildingId,
            freeCount = best.freeCount,
        )
    }
}
