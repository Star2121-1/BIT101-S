package cn.bit101.android.data.school

import cn.bit101.api.model.common.ClassroomInfo
import cn.bit101.android.config.setting.base.TimeTable
import java.time.LocalTime

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

    /** 一栋楼在当前时段的空闲情况。 */
    data class BuildingFree(
        val buildingId: String,
        val buildingName: String,
        val freeCount: Int,
    )

    /** 当前或即将开始的一节课（**由调用方按 10 分钟窗口判好**再传进来）。 */
    data class NextCourse(
        val name: String,
        val classroom: String,
        /** 下课后才能去自习 —— 副文案要用 */
        val endsAt: LocalTime? = null,
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
     */
    fun coarseTitle(suggestion: Suggestion): String? = when (suggestion.kind) {
        // ⚠️ 必须写 ${it}：Kotlin 的中文也算标识符字符，"$it现在" 会被当成一个名字
        Kind.FREE_ROOM -> suggestion.buildingName?.let { "${it}现在有空教室" }
        Kind.IN_CLASS -> suggestion.title
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
                "${course.endsAt}下课后，${best.buildingName}有 ${best.freeCount} 间空教室"
            } else {
                "现在在 ${course.classroom}"
            }
            return Suggestion(
                kind = Kind.IN_CLASS,
                title = "正在上 ${course.name}（${course.classroom}）",
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
            title = "${best.buildingName}现在有 ${best.freeCount} 间空教室",
            detail = "当前时段空闲",
            buildingName = best.buildingName,
            buildingId = best.buildingId,
            freeCount = best.freeCount,
        )
    }
}
