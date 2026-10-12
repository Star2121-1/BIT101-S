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
     * ⚠️ 这是**楼层粒度**的原始事实（一栋楼可能有多条，每层一条），
     * 「用户看到的那一个去处」要先用 [groupByBuilding] 聚到**楼**这一级 —— 见 [Candidate]。
     *
     * @param floor `null` 表示教室名没解析出楼层 —— 此时文案退回到只说楼名
     * @param freeSectionsAhead 从当前时段起，这一层还能**连续**空几节（含当前节）。
     *   `0` = 算不出来 / 不适用 ⇒ 此时**不做任何时长断言**（宁可不说，也不说错）。
     */
    data class BuildingFree(
        val buildingId: String,
        val buildingName: String,
        val freeCount: Int,
        val floor: Int? = null,
        val freeSectionsAhead: Int = 0,
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
     * 「上一节课的教室」还能拿来当**位置代理**的时限（分钟）。
     *
     * 再久人早就走了，拿它推「你在附近」就是瞎猜 —— 超时就不做这次推断（见 [recentClassroomOf]）。
     */
    const val LAST_CLASS_FRESH_MINUTES = 90L

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
        val timed = timedOf(courses, table)

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

    /**
     * 「上一节课的教室」—— 用来做**就近推断**的位置代理；拿不到 / 太久以前则 `null`。
     *
     * ## 为什么需要它
     *
     * 「去哪儿自习」最大的信息缺失是**距离**：空教室再多，在另一头也没用。
     * 而课表里有教室名 ⇒ 「刚在这栋楼上完课」是一个**零权限、纯本地**的位置代理。
     *
     * ## ⚠️ 窗口不能省
     *
     * 上午 10:00 就上完的课，到下午 16:00 拿它当「你在那儿附近」就是瞎猜了 ——
     * 所以只认 [windowMinutes] 内刚下课的。超了就返回 `null`，**不做这次推断**
     * （宁可给一个普通的「空教室最多」的建议，也不要给一个基于错误前提的「就近」建议）。
     *
     * ⚠️ 返回的是**教室名**（`文萃楼I404`）不是楼名 —— 由调用方用 [buildingOf] 去认楼，
     *    因为「楼名」这件事只有拿到候选列表才说得准。
     */
    fun recentClassroomOf(
        courses: List<CourseAt>,
        table: TimeTable,
        now: LocalTime,
        windowMinutes: Long = LAST_CLASS_FRESH_MINUTES,
    ): String? {
        val last = timedOf(courses, table)
            .filter { !now.isBefore(it.end) }
            .maxByOrNull { it.end }
            ?: return null
        if (Duration.between(last.end, now).toMinutes() > windowMinutes) return null
        return last.course.classroom.takeIf { it.isNotBlank() }
    }

    /** 节次 → 真实钟点；取不到钟点的课**直接跳过**（异常数据不该编出一个时间）。 */
    private fun timedOf(courses: List<CourseAt>, table: TimeTable): List<TimedCourse> =
        courses.mapNotNull { course ->
            val start = table.getOrNull(course.startSection - 1)?.startTime ?: return@mapNotNull null
            val end = table.getOrNull(course.endSection - 1)?.endTime ?: return@mapNotNull null
            TimedCourse(course, start, end)
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

    // ============================================================ 候选池与排序
    //
    // ## 为什么不再只给「唯一最优」
    //
    // 早先 [suggest] 是 `maxByOrNull { freeCount }` —— **确定性**地永远给同一栋楼。
    // 用户看到的就是「每次都是文萃楼I」，于是问「能不能换一个」。
    //
    // ⚠️ 但**随机换**是错的：30 栋楼里随机一下多半命中一栋更远的、或者只剩 1 间的，
    //    点一下「换一个」结果更糟 —— 这个交互会反噬信任。正确做法是
    //    **把「唯一最优」变成「排好序的候选列表」**，「换一个」= 顺序切换，
    //    每一步都是有理由的（空教室更多 / 刚在这栋楼上过课），而且稳定可预期。
    //
    // ⚠️ 候选列表**必须在同一次取数里算完**（空教室要逐栋请求，见 [DefaultStudySpotRepository]）——
    //    「换一个」绝不能重新打网络，否则点一下要等十几秒，比原来的问题还糟。

    /** 一栋楼里某一层的空闲情况。 */
    data class FloorFree(
        /** `null` = 教室名没解析出楼层 */
        val floor: Int?,
        val count: Int,
        val freeSectionsAhead: Int = 0,
    )

    /**
     * 一个可推荐的去处 —— **到「楼」这一级**（[floors] 只是细节，不再是一等公民）。
     *
     * ⚠️ 为什么必须聚到楼这一级：服务端返回的是**逐层**的空闲条目，同一栋楼会出现多条。
     *    直接对条目排序，就会出现「文萃楼I 3 楼」和「文萃楼I 5 楼」两个候选抢位置，
     *    用户看到的是同一栋楼的两条 —— 而真正该比的是**楼之间**。
     */
    data class Candidate(
        val buildingId: String,
        val buildingName: String,
        /** 该楼在当前时段的空闲教室**总数**（= 各层之和）。 */
        val freeCount: Int,
        /** 各层空闲数，**多的在前**（[bestFloor] 取第一个）。 */
        val floors: List<FloorFree> = emptyList(),
        /** 上一节课就在这栋楼 ⇒ 排序时给一点加成（见 [SAME_BUILDING_BONUS]）。 */
        val matchLastClass: Boolean = false,
        /** 该楼还能**连续**空几节（各层取最大）；`0` = 算不出来。 */
        val freeSectionsAhead: Int = 0,
    ) {
        /** 空教室最多的那一层 —— 只在「这栋楼只有一层有空」时用来点名。 */
        val bestFloor: Int? get() = floors.firstOrNull()?.floor
    }

    /**
     * 「刚在这栋楼上过课」的排序加成（等价于几间空教室）。
     *
     * ⚠️ **刻意只值 3 间**：加成太大就会出现「近但只剩 2 间」压过「远但有 12 间」，
     *    那是在拿错误的前提（人一定愿意为了近而挤一间几乎满的教室）替用户做决定。
     *    3 间的语义是「同楼时，空教室数差不太多就选近的」。
     */
    const val SAME_BUILDING_BONUS = 3

    /**
     * 把**楼层粒度**的原始事实聚成**楼粒度**的候选。
     *
     * 只保留 `freeCount > 0` 的（全满的楼没什么可去的）。`floors` 按空闲数降序。
     */
    fun groupByBuilding(buildings: List<BuildingFree>): List<Candidate> =
        buildings
            .filter { it.freeCount > 0 }
            .groupBy { it.buildingId }
            .map { (buildingId, floors) ->
                Candidate(
                    buildingId = buildingId,
                    buildingName = floors.first().buildingName,
                    freeCount = floors.sumOf { it.freeCount },
                    floors = floors
                        .map { FloorFree(it.floor, it.freeCount, it.freeSectionsAhead) }
                        .sortedByDescending { it.count },
                    freeSectionsAhead = floors.maxOf { it.freeSectionsAhead },
                )
            }

    /**
     * 教室名（`文萃楼I404`）属于哪栋楼 —— 取**最长**的匹配前缀。
     *
     * ⚠️ 必须取最长：`文萃楼I` 与 `文萃楼` 都能匹配 `文萃楼I404`，
     *    取短的会把 I 区认成隔壁楼。
     */
    fun buildingOf(classroom: String?, candidates: List<Candidate>): String? {
        if (classroom.isNullOrBlank()) return null
        return candidates
            .map { it.buildingName }
            .filter { it.isNotBlank() && classroom.startsWith(it) }
            .maxByOrNull { it.length }
    }

    /**
     * 排序：**空教室多的在前**，同楼加成，并列时按「还能空多久」再按楼名。
     *
     * ⚠️ **必须有确定性**：`sortedByDescending` 在值相等时保留输入顺序，
     *    而输入顺序来自并发请求的 `awaitAll` —— 不额外加 `thenBy` 的话，
     *    同一份数据两次排序可能给出不同的「第一个」，卡片上就会自己跳来跳去。
     *
     * @param lastClassroom 上一节课刚下课的教室（`null` = 不知道 / 太久以前）。见 [recentClassroomOf]。
     */
    fun rank(buildings: List<BuildingFree>, lastClassroom: String? = null): List<Candidate> {
        val groups = groupByBuilding(buildings)
        val lastBuilding = buildingOf(lastClassroom, groups)

        return groups
            .map { if (lastBuilding != null && it.buildingName == lastBuilding) it.copy(matchLastClass = true) else it }
            .sortedWith(
                compareByDescending<Candidate> { it.freeCount + if (it.matchLastClass) SAME_BUILDING_BONUS else 0 }
                    .thenByDescending { it.freeSectionsAhead }
                    .thenBy { it.buildingName },
            )
    }

    // ---------------------------------------------------------------- 文案

    /**
     * 候选的**地点名**：只有一层有空时才点名楼层，否则只说楼名。
     *
     * ⚠️ 与 [candidateTitle] 里的数量**必须同范围** —— 见那个函数的说明。
     */
    fun candidatePlace(candidate: Candidate): String =
        if (candidate.floors.size == 1) placeName(candidate.buildingName, candidate.floors[0].floor)
        else candidate.buildingName

    /**
     * 候选标题：`文萃楼I 3 楼现在有 12 间空教室`。
     *
     * ⚠️⚠️ **名字的范围与数字的范围必须一致**。多层的楼要是写成
     *    「文萃楼I 3 楼现在有 20 间空教室」，那个 20 是**整栋楼**的数 ——
     *    用户跑去 3 楼会发现只有 5 间。这正是「命中对了不代表展示对了」那类坑：
     *    数据是对的，界面说的却是另一件事。
     *    ⇒ 多层时只说楼名（数字 = 楼的总数），楼层分布放 [optionOf] 里讲。
     */
    fun candidateTitle(candidate: Candidate): String =
        "${candidatePlace(candidate)}现在有 ${candidate.freeCount} 间空教室"

    /**
     * 「这里能待多久」。
     *
     * @param at **用户实际会到那儿的时刻**（正在上课时就是下课后，见 [planOf] 的说明）
     * @param freeSectionsAhead 见 [freeSectionsAhead]；`<= 0` 返回 `null`（**不做断言**）
     * @return `有教室空到 20:55`；当前不在任何时段（课都上完了）时返回 `null`
     *
     * ## ⚠️⚠️ 措辞为什么是「**有教室**空到」，而不是「（这栋楼）空到」
     *
     * 这个数的来源是：**各间教室里，「从现在起连续空着」最长的那个房间**。
     * 它保证的是「**至少有 1 间**能一路坐到底」，**不保证**整栋楼/整层都空着。
     *
     * 曾经写成「至少空到 20:55」—— 挂在楼名后面，读起来就是「这栋楼空到 20:55」，
     * 而那是另一句话（而且多半是假的）。同一份数据换个主语，正确性就变了。
     */
    fun freeUntilText(table: TimeTable, at: LocalTime, freeSectionsAhead: Int): String? {
        if (freeSectionsAhead <= 0) return null
        // ⚠️ 空表 / 当天课都上完时 sectionFor 返回 null ⇒ 没有「到几点」可言，也不说
        val section = sectionFor(table, at) ?: return null
        // 超出作息表就截到最后一节（不能因为「后面没定义了」就不给结论）
        val last = minOf(section + freeSectionsAhead - 1, table.size)
        val end = table.getOrNull(last - 1)?.endTime ?: return null
        return "有教室空到 ${end.truncatedTo(ChronoUnit.MINUTES)}"
    }

    /**
     * 从 [from] 节起，某间教室还能**连续**空几节（含 [from] 这一节）。
     *
     * ⚠️ `busyTimeStr` 为 null / 空串 = 全天空闲（见 [parseBusySections]），不是数据缺失。
     */
    fun freeSectionsAhead(busyTimeStr: String?, from: Int, lastSection: Int): Int {
        val busy = parseBusySections(busyTimeStr)
        var count = 0
        var section = from
        while (section <= lastSection && section !in busy) {
            count++
            section++
        }
        return count
    }

    /**
     * 卡片上的一条候选取舍 —— **文案已经渲染好**，界面不需要知道时间表与时刻。
     *
     * ⚠️ 三段说明**分开存**而不是拼成一句：上课中与空闲时要说的话不一样 ——
     *    上课中要的是「下课后去哪儿」，此时「你上一节在 X 上课」是废话
     *    （正在上的那节就在那栋楼）。拼死了就没法按场合取舍。
     */
    data class SpotOption(
        val buildingId: String,
        val buildingName: String,
        val freeCount: Int,
        /** 范围与 [freeCount] **一致**的地点名（单层点名楼层，多层只说楼）—— 见 [candidateTitle] */
        val place: String,
        /** 标题 */
        val title: String,
        /** 为什么推荐它（「你上一节在 X 上课」）；`null` = 没有就近依据 */
        val reason: String? = null,
        /** 各层分布（**只有多层才有**；单层时标题已点名，写两遍是重复） */
        val floors: String? = null,
        /** 「至少空到 12:20」；`null` = 算不出来 ⇒ **不做断言** */
        val until: String? = null,
    ) {
        /** 空闲时的那句副文案。 */
        val detail: String? get() = listOfNotNull(reason, floors, until)
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * 「现在去哪儿自习」的完整结果。
     *
     * ⚠️ `kind` 复用 [Kind]：三种建议各自该跳哪个页面由界面按它决定，逻辑层不管跳转。
     */
    data class Plan(
        val kind: Kind,
        /** [Kind.IN_CLASS] 的那一句（「正在上 X」/「10:00 上 X」） */
        val courseTitle: String? = null,
        /** [Kind.IN_CLASS] 的教室 —— 没有候选可用时的兜底文案要用 */
        val coursePlace: String? = null,
        /** [Kind.IN_CLASS] 的下课钟点（`12:20`）—— 「下课后去哪儿」要说清是几点 */
        val courseEndsAt: String? = null,
        /** [Kind.HAS_BOOKING] 的那一句 */
        val bookingTitle: String? = null,
        val bookingDetail: String? = null,
        /** 候选取舍，按推荐度排序 */
        val options: List<SpotOption> = emptyList(),
    ) {
        /** 有东西可展示吗 —— 三种建议都取不到时**整张卡不渲染**（不给瞎建议）。 */
        val hasContent: Boolean
            get() = when (kind) {
                Kind.IN_CLASS -> courseTitle != null
                Kind.HAS_BOOKING -> bookingTitle != null
                Kind.FREE_ROOM -> options.isNotEmpty()
            }
    }

    /**
     * 卡片要显示的两行。
     *
     * ⚠️ `detail` 可为 `null`（没话可说时**不占那一行**），界面据此决定要不要渲染第二行。
     */
    data class SpotCard(
        val kind: Kind,
        val title: String,
        val detail: String? = null,
    )

    /**
     * 组装完整建议。**取不到数据也只是 `options` 为空**，由 [Plan.hasContent] 决定要不要显示。
     *
     * @param at **用户实际会到那儿的时刻**。见下。
     * @param lastClassroom 上一节课刚下课的教室（见 [recentClassroomOf]）；`null` = 不做就近判断
     *
     * ## ⚠️ 为什么不是「现在」，而是「到达时刻」
     *
     * 空教室数是**按时段（节次）**算的，而正在上课的人**到不了**——他要等下课。
     * 拿「现在这一节」的数说「下课后去文萃楼F（9 间空）」，那 9 间是**现在**的数；
     * 12:25 人到了，第 6 节（13:20 起）的课已经排上，可能就是 4 间了。
     * ⇒ 上课中时调用方要传**下课钟点**（[NextCourse.endsAt]），其余情况传当下。
     *   这样标题、数量、时长说的才是**同一时刻**的事。
     */
    fun planOf(
        at: LocalTime,
        table: TimeTable,
        course: NextCourse?,
        booking: Booking?,
        buildings: List<BuildingFree>,
        lastClassroom: String? = null,
    ): Plan {
        val options = rank(buildings, lastClassroom).map { optionOf(it, table, at) }

        return when {
            // 1. 有课 —— 这时候说「去哪自习」没有意义
            course != null -> Plan(
                kind = Kind.IN_CLASS,
                courseTitle = courseLine(course),
                coursePlace = course.classroom,
                courseEndsAt = course.endsAt?.truncatedTo(ChronoUnit.MINUTES)?.toString(),
                options = options,
            )

            // 2. 有预约 —— 已经安排好了，别再推荐别的地方
            booking != null -> Plan(
                kind = Kind.HAS_BOOKING,
                bookingTitle = "你 ${booking.timeText} 在 ${booking.place} 有座位预约",
                bookingDetail = "已约好，直接去就行",
            )

            // 3. 空教室
            else -> Plan(kind = Kind.FREE_ROOM, options = options)
        }
    }

    /**
     * 把候选渲染成卡片上的一条（三段说明，见 [SpotOption]）。
     *
     * @param at 用户实际会到那儿的时刻（见 [planOf]）
     *
     * ⚠️ 「你上一节在 X 上课」只陈述**事实**（课表上就写着），不写「你就在附近」那种**推论** ——
     *    推错的时候用户会觉得这个 App 在瞎猜（推论不该写进用户可见文案）。
     */
    fun optionOf(candidate: Candidate, table: TimeTable, at: LocalTime): SpotOption = SpotOption(
        buildingId = candidate.buildingId,
        buildingName = candidate.buildingName,
        freeCount = candidate.freeCount,
        place = candidatePlace(candidate),
        title = candidateTitle(candidate),
        reason = if (candidate.matchLastClass) "你上一节在${candidate.buildingName}上课" else null,
        // 多层才列分布：单层时标题已经点了名，再写一遍是重复
        floors = if (candidate.floors.size > 1) {
            candidate.floors.take(MAX_FLOOR_PARTS).joinToString(" · ") { floor ->
                val name = floor.floor?.let { "$it 层" } ?: "其它层"
                "$name ${floor.count} 间"
            }
        } else null,
        until = freeUntilText(table, at, candidate.freeSectionsAhead),
    )

    /**
     * 卡片上的第 [index] 个候选取舍（**取模，循环**）。
     *
     * 「换一个」= index + 1 —— **纯本地换下标，不打网络**（候选在取数时就算好了）。
     * ⚠️ 取模保证永远不会越界：切换途中候选列表可能被刷新（数量变了），
     *    直接拿旧下标取值会崩。
     */
    fun studySpotCardOf(plan: Plan, index: Int = 0): SpotCard? {
        val options = plan.options
        val option = if (options.isEmpty()) null
        else options[((index % options.size) + options.size) % options.size]

        return when (plan.kind) {
            Kind.HAS_BOOKING -> plan.bookingTitle?.let {
                SpotCard(kind = Kind.HAS_BOOKING, title = it, detail = plan.bookingDetail)
            }

            Kind.IN_CLASS -> plan.courseTitle?.let { title ->
                val head = option?.let {
                    buildString {
                        plan.courseEndsAt?.let { at -> append("$at 下课后 · ") }
                        append("去${it.place}（${it.freeCount} 间空）")
                    }
                }
                // ⚠️ 上课中**不说**「你上一节在 X 上课」：正在上的那节就在那栋楼，重复且绕
                val tail = option?.let { o ->
                    listOfNotNull(o.floors, o.until).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                }
                val detail = listOfNotNull(head, tail).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: plan.coursePlace?.let { "地点：$it" }
                SpotCard(kind = Kind.IN_CLASS, title = title, detail = detail)
            }

            Kind.FREE_ROOM -> option?.let {
                // 一段副信息都没有时给一句兜底，否则第二行是空的、卡片看着像坏了
                SpotCard(kind = Kind.FREE_ROOM, title = it.title, detail = it.detail ?: "当前时段空闲")
            }
        }
    }

    /**
     * 兼容入口：旧的一条 [Suggestion]（**桌面组件走这条**）。
     *
     * ⚠️ 保留是为了组件那条路完全不动（它读 [coarseTitle]，不关心候选列表）。
     *    内部走 [planOf] ⇒ 排序改进对组件**自动生效**。
     *
     * ⚠️ 这里拿不到时间表（旧签名如此）⇒ 少「至少空到」那一段。组件只用到 [coarseTitle]，
     *    `detail` 根本不显示，所以没有影响。
     */
    fun suggest(
        now: LocalTime,
        course: NextCourse?,
        booking: Booking?,
        buildings: List<BuildingFree>,
    ): Suggestion? = suggestionOf(planOf(now, emptyList(), course, booking, buildings))

    /**
     * [Plan] → 旧的具体一条建议（[Suggestion]），取推荐度最高的那条候选。
     *
     * ⚠️ 语义与改造前一致（`title` / `buildingName` / `freeCount` 都还是那一套），
     *    只是「最优」的判据从「空教室最多」变成 [rank]（空教室 + 就近加成）。
     */
    fun suggestionOf(plan: Plan): Suggestion? {
        val best = plan.options.firstOrNull()

        plan.courseTitle?.let { title ->
            val detail = if (best != null && plan.courseEndsAt != null) {
                "${plan.courseEndsAt}下课后，${best.place}有 ${best.freeCount} 间空教室"
            } else {
                "地点：${plan.coursePlace ?: "教室"}"
            }
            return Suggestion(
                // ⚠️ 「正在上」与「马上要上」措辞分开 —— 课间那几分钟人还没进教室，
                //    说「正在上」是错的。措辞只有 [courseLine] 一份实现（组件也用）。
                kind = Kind.IN_CLASS,
                title = title,
                detail = detail,
                buildingName = best?.buildingName,
                buildingId = best?.buildingId,
                freeCount = best?.freeCount ?: 0,
            )
        }

        if (plan.kind == Kind.HAS_BOOKING && plan.bookingTitle != null) {
            return Suggestion(
                kind = Kind.HAS_BOOKING,
                title = plan.bookingTitle,
                detail = plan.bookingDetail.orEmpty(),
            )
        }

        if (best == null) return null
        return Suggestion(
            kind = Kind.FREE_ROOM,
            title = best.title,
            detail = best.detail ?: "当前时段空闲",
            buildingName = best.buildingName,
            buildingId = best.buildingId,
            freeCount = best.freeCount,
        )
    }

    /** 副文案里最多列几层（列太多会把那一行挤爆）。 */
    private const val MAX_FLOOR_PARTS = 3
}
