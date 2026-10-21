package cn.bit101.android.features.schedule.classroom

import cn.bit101.android.features.schedule.classroom.FreeClassroomSearchViewModel.ClassroomBusyData
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 「隐藏非空闲教室」把所有教室都过滤掉时的空态文案。
 *
 * 原来的空态只有一句「未找到教室 :(」，看起来像加载失败；其实最常见的情况是
 * **整栋楼都在上课**。这里把原始教室数、在课数和最近空闲时间说清楚，
 * 让用户知道是「被过滤了」而不是「坏了」。
 *
 * 纯函数抽出来是为了能单测边界（全忙 / 只有快上课的 / 今天没更长的空闲 / 原本就没教室）。
 */
internal object ClassroomEmptyStateLogic {

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    data class Summary(
        /** 过滤前的教室总数。 */
        val total: Int,
        /** 正在上课（当前不空闲）的数量。 */
        val busy: Int,
        /** 现在空闲、但距下节课不足阈值的数量。 */
        val aboutToStart: Int,
        /** 正在上课的教室里，最近一个恢复空闲的时刻；今天不再空闲则为 null。 */
        val earliestFree: LocalTime?,
    )

    /**
     * @param isFreeNow 必须与列表过滤用的是**同一个**判定，否则文案会和列表对不上。
     */
    fun summarize(
        classrooms: List<ClassroomBusyData>,
        isFreeNow: (ClassroomBusyData) -> Boolean,
    ): Summary {
        var busy = 0
        var aboutToStart = 0
        var earliestFree: LocalTime? = null

        classrooms.forEach { classroom ->
            when {
                classroom.nextBusyTime == LocalTime.MIN -> {
                    busy++
                    val nextFree = classroom.nextFreeTime ?: return@forEach
                    if (earliestFree == null || nextFree < earliestFree!!) {
                        earliestFree = nextFree
                    }
                }

                classroom.nextBusyTime != LocalTime.MAX && !isFreeNow(classroom) -> {
                    aboutToStart++
                }
            }
        }

        return Summary(
            total = classrooms.size,
            busy = busy,
            aboutToStart = aboutToStart,
            earliestFree = earliestFree,
        )
    }

    /**
     * 空态文案；**原本就没有教室**时返回 null，由调用方保留旧文案。
     */
    fun text(summary: Summary): String? {
        if (summary.total <= 0) return null

        val detail = buildList {
            if (summary.busy > 0) add("${summary.busy} 间正在上课")
            if (summary.aboutToStart > 0) add("${summary.aboutToStart} 间即将上课")
            val earliest = summary.earliestFree
            if (earliest != null) {
                add("最近 ${earliest.format(HH_MM)} 起空闲")
            } else if (summary.busy > 0) {
                add("今天没有更长的空闲时段")
            }
        }

        val head = "共 ${summary.total} 间教室，当前都不满足空闲条件"
        return if (detail.isEmpty()) head else "$head\n${detail.joinToString(" · ")}"
    }
}
