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
     * 「这间教室能待到几点」的**钟点**（`20:55`）；算不出来返回 `null`。
     *
     * @param at **用户实际会到那儿的时刻**（正在上课时就是下课后，见 [planOf] 的说明）
     * @param freeSectionsAhead 见 [freeSectionsAhead]；`<= 0` 返回 `null`（**不做断言**）
     *
     * ## ⚠️ 只返回钟点，**不返回整句话**
     *
     * 主语是谁必须由调用方按**它当时在说哪一级**来决定：
     * 教室级是「文萃楼C 501a 空到 20:55」，楼级是「有教室空到 20:55」——
     * 同一份数据换个主语，正确性就变了（「这栋楼空到 20:55」是另一句话，而且多半是假的）。
     * 把措辞塞进这里，迟早有一个调用方把主语说错。
     */
    fun freeUntilTime(table: TimeTable, at: LocalTime, freeSectionsAhead: Int): String? {
        if (freeSectionsAhead <= 0) return null
        // ⚠️ 空表 / 当天课都上完时 sectionFor 返回 null ⇒ 没有「到几点」可言，也不说
        val section = sectionFor(table, at) ?: return null
        // 超出作息表就截到最后一节（不能因为「后面没定义了」就不给结论）
        val last = minOf(section + freeSectionsAhead - 1, table.size)
        val end = table.getOrNull(last - 1)?.endTime ?: return null
        return end.truncatedTo(ChronoUnit.MINUTES).toString()
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

    // ======================================================== 教室级候选（v1.12）
    //
    // ## 为什么还要从「楼」往下走一级
    //
    // 楼级只说得出「文萃楼C现在有 10 间空教室」—— 人到了那栋楼还得自己一间间找。
    // 教室级能直接说「文萃楼C 501a，空到 20:55」，而且「能待多久」终于有了**准确的主语**
    //（楼级只能取各间里最长的那个，于是措辞得绕成「有教室空到…」）。
    //
    // ⚠️ 代价是候选会爆炸：8 栋 × 约 10 间 = 80 间，「换一个」翻 80 次没有意义。
    //    所以必须先**硬门槛**筛、再**同楼限流**，最后只留几个。

    /** 一间教室在当前时段的空闲情况 —— **教室级**的原始事实。 */
    data class RoomFree(
        val buildingId: String,
        val buildingName: String,
        /** 服务端给的完整教室名，如 `文萃楼C501a` / `文萃楼H125良乡二号机房` */
        val classroomName: String,
        /** 解析出的楼层；`null` = 解析不出 */
        val floor: Int? = null,
        /** 从到达时刻起，这间教室连续空几节（含当前节）；`0` = **算不出**（不是「空 0 节」） */
        val freeSectionsAhead: Int = 0,
        /** 这栋楼当前时段一共空多少间 —— 教室级候选也要能看出「这栋楼还有几间」 */
        val buildingFreeCount: Int = 0,
    )

    /**
     * 距离的**参考点**：用户大概在哪。
     *
     * ⚠️ 这是**代理**，不是真实位置（我们不做定位）。所以 [label] 必须如实说清
     * 这个代理是什么（「你上一节课」/「你下节课」），**不许**简化成「你」——
     * 那是在声称一个我们并不知道的位置。
     */
    data class Reference(
        /** 参考教室（`文萃楼I404`） */
        val classroom: String,
        /** 说话时的主语，如 `你上一节课` / `你现在上课` / `你下节课` */
        val label: String,
    )

    /** 一个通过筛选、排好序的**教室**候选。 */
    data class RoomCandidate(
        val room: RoomFree,
        /** 距参考点的档位；`null` = 算不出来（参考点或楼坐标缺一） */
        val distance: CampusLocations.Distance? = null,
    )

    /**
     * ⚠️ 至少要能**连续**空这么多节 —— 空 1 节等于坐下就该走。
     *
     * ⚠️ 门槛只挡「**知道**它空得短」的那种（1 节）。
     *    `freeSectionsAhead == 0` 是「算不出来」，**不因此排除** ——
     *    它分数本来就低、会排到最后；把「不知道」当成「短」是一种假结论。
     */
    const val MIN_FREE_SECTIONS = 2

    /**
     * ⚠️ 同一栋楼最多出几间。
     *
     * 不限的话文萃楼C 一家就能刷满整个列表 —— 而用户真正要比的是**去哪儿**，
     * 不是「这栋楼的第 3 间和第 5 间哪个好」。留 2 间：给一点同楼内的余地。
     */
    const val MAX_ROOMS_PER_BUILDING = 2

    /**
     * ⚠️ 候选**总数**上限。
     *
     * 同楼限流只管住了「一栋楼刷屏」，管不住「楼多」：把拉取上限放宽到 16 栋之后，
     * 真机上立刻出现 **1/27** —— 「换一个」要按 27 次，没人会按。
     * ⇒ 再兜一道总数：8 个已经足够「比一比」，再多用户不会翻。
     */
    const val MAX_ROOM_CANDIDATES = 8

    /**
     * 排序里「能空节数」的**封顶**。
     *
     * 空 8 节和空 6 节对自习的人来说差不多（都够坐到不想坐）。不封顶的话时长会压死距离，
     * 而「走过去要多久」同样是决定性的。
     */
    private const val FREE_SECTIONS_CAP = 4

    /** 同楼的加成（等价于几节）。⚠️ 与楼级的 [SAME_BUILDING_BONUS] 同量级，别再调大。 */
    private const val SAME_BUILDING_ROOM_BONUS = 2

    /** 「紧挨着」的加成（比同楼小一档）。 */
    private const val NEXT_DOOR_ROOM_BONUS = 1

    /**
     * 教室名 → **房间号**。
     *
     * ## 真机样例（2026-10-12 空教室页 + 推荐卡片实测）
     *
     * | 教室名 | 楼名 | 房间号 |
     * |---|---|---|
     * | `文萃楼C501a` | `文萃楼C` | `501a` |
     * | `文萃楼H125良乡二号机房` | `文萃楼H` | `125` |
     * | `理教楼203` | `理科教学大楼` | `203` |
     * | `综教B301` | `综合教学大楼` | `B301` |
     *
     * ⚠️⚠️ 两条规则都别改：
     *
     * 1. **先丢掉开头不是英文/数字的部分**，再取第一段英文/数字。
     *    接口**教室名用简称、楼名用全称**（`理教楼203` vs `理科教学大楼`）——
     *    只按「去掉楼名前缀」处理的话，`理教楼` 这三个字会留在房间号里，
     *    界面上就成了「理科教学大楼 理教楼203」（楼名说两遍）。
     * 2. **取第一段**英文/数字，不是最后一段 —— `501a` 里的 `a` 是房间号的一部分，
     *    而 `良乡二号机房` 这种后缀里也有数字，取末段会取到「二号」的「2」。
     *
     * ⚠️ 解析不出就返回**原串**，不硬编一个房间号。
     */
    fun roomLabelOf(classroomName: String, buildingName: String): String =
        roomParts(classroomName, buildingName).label

    /**
     * 教室名里的**房间名后缀**（`良乡二号机房`）；没有则 `null`。
     *
     * ⚠️ **原样返回，不做类型推断**。它是我们唯一能拿到的「教室性质」线索，
     *    但格式不稳定（文萃楼C 的教室一个后缀都没有），而且「良乡二号机房」里混着校区名 ——
     *    任何「提取出『机房』两个字」的加工都是**我们自己编的结论**，
     *    学校那边一改版就会悄悄说错。原样展示最稳。
     *
     * ⚠️ 房间号都取不出来时返回 `null`（那种情况下**整串都不是后缀**）——
     *    否则会把「理教楼203」整串当成后缀，界面上同一件事说两遍。
     */
    fun roomKindOf(classroomName: String, buildingName: String): String? =
        roomParts(classroomName, buildingName).kind

    private data class RoomParts(val label: String, val kind: String?)

    /**
     * 把「去掉了楼名前缀」的剩余部分拆成（房间号, 房间名后缀）。
     *
     * ⚠️ 房间号取不出来时 [RoomParts.label] 给**原教室名**（不是空串），
     *    这样 [roomPlace] 能认出「它和楼名一样」而不拼成「文萃楼C 文萃楼C」。
     */
    private fun roomParts(classroomName: String, buildingName: String): RoomParts {
        val rest = roomRest(classroomName, buildingName)
        val tail = rest.dropWhile { !it.isAsciiLetterOrDigit() }
        if (tail.isEmpty()) {
            return RoomParts(classroomName.ifBlank { rest }, kind = null)
        }
        val label = tail.takeWhile { it.isAsciiLetterOrDigit() }
        val kind = tail.substring(label.length)
            .trim { it.isWhitespace() || it == '-' || it == '_' }
            .takeIf { it.isNotBlank() }
        return RoomParts(label, kind)
    }

    /** 去掉楼名前缀后的剩余部分（`文萃楼H125良乡二号机房` → `125良乡二号机房`）。 */
    private fun roomRest(classroomName: String, buildingName: String): String =
        (if (buildingName.isNotEmpty() && classroomName.startsWith(buildingName)) {
            classroomName.substring(buildingName.length)
        } else {
            classroomName
        }).trim()

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    /**
     * 把教室候选**筛出来并排好序**：门槛（[MIN_FREE_SECTIONS]）→ 打分 → 同楼限流。
     *
     * ⚠️ **必须有确定性**：并列时按楼名、再按教室名 —— 输入顺序来自并发请求的 `awaitAll`，
     *    不兜住的话同一份数据两次排序给出的第一条会不一样，卡片上的推荐会自己跳。
     */
    fun rankRooms(rooms: List<RoomFree>, reference: Reference? = null): List<RoomCandidate> {
        val ref = CampusLocations.match(reference?.classroom)
        val scored = rooms
            .filter { it.freeSectionsAhead == 0 || it.freeSectionsAhead >= MIN_FREE_SECTIONS }
            .map { room -> RoomCandidate(room, distanceOf(room.buildingName, ref)) }
            .sortedWith(
                compareByDescending<RoomCandidate> { it.score() }
                    .thenByDescending { it.room.freeSectionsAhead }
                    .thenBy { it.room.buildingName }
                    .thenBy { it.room.classroomName },
            )

        // 同楼限流：按排好的顺序过一遍，每栋楼最多留 MAX_ROOMS_PER_BUILDING 间
        val taken = HashMap<String, Int>()
        return scored.filter { candidate ->
            val used = taken[candidate.room.buildingId] ?: 0
            if (used >= MAX_ROOMS_PER_BUILDING) {
                false
            } else {
                taken[candidate.room.buildingId] = used + 1
                true
            }
        }.take(MAX_ROOM_CANDIDATES)
    }

    private fun RoomCandidate.score(): Int {
        val time = minOf(room.freeSectionsAhead, FREE_SECTIONS_CAP)
        val bonus = when (distance) {
            CampusLocations.Distance.SAME -> SAME_BUILDING_ROOM_BONUS
            CampusLocations.Distance.NEXT_DOOR -> NEXT_DOOR_ROOM_BONUS
            else -> 0
        }
        return time + bonus
    }

    /**
     * 一栋楼名 → 它的档位。
     *
     * ⚠️ 两个名字任一匹配不上地点就返回 `null` —— **宁可不说距离，也不给假距离**
     *    （`前沿楼`就不在坐标表里）。
     */
    private fun distanceOf(
        buildingName: String,
        ref: CampusLocations.Location?,
    ): CampusLocations.Distance? {
        val from = CampusLocations.match(buildingName) ?: return null
        if (ref == null) return null
        if (from.name == ref.name) return CampusLocations.Distance.SAME
        return CampusLocations.distanceOf(CampusLocations.distanceMeters(from, ref))
    }

    /**
     * 距离档位 → 一句话。
     *
     * ⚠️ 只说**直线距离**的相对关系，不说「走几分钟」—— 我们没有路网，也没有用户的位置。
     * ⚠️ [label] 是参考点的**主语**（「你上一节课」），**必须带上**：
     *    只说「就在隔壁」用户不知道跟哪儿比。
     */
    fun distanceText(distance: CampusLocations.Distance?, label: String): String? =
        when (distance) {
            null -> null
            // ⚠️ 必须写 ${label}：Kotlin 的中文也算标识符字符，"$label那栋楼" 会被当成一个名字
            CampusLocations.Distance.SAME -> "就在${label}那栋楼"
            CampusLocations.Distance.NEXT_DOOR -> "紧挨着${label}那栋楼"
            CampusLocations.Distance.NEAR -> "距${label}那栋楼一段路"
            CampusLocations.Distance.FAR -> "距${label}那栋楼挺远"
        }

    /**
     * 卡片 / 详情页上的一条候选取舍 —— **一间教室**，文案已渲染好。
     *
     * ⚠️ 数值与主语**必须同范围**：[place] 是**一间教室**，所以 [untilAt] 说的是这一间；
     *    楼的总数单独走 [buildingFreeCount]，而且文案里**明写「这栋楼」**。
     */
    data class SpotOption(
        val buildingId: String,
        val buildingName: String,
        /** 「文萃楼C 501a」—— 楼名 + 房间号 */
        val place: String,
        /** 该教室所在楼层；`null` = 解析不出 */
        val floor: Int? = null,
        /** 这栋楼当前时段一共空多少间 */
        val buildingFreeCount: Int = 0,
        /** 房间名后缀（`良乡二号机房`）；`null` = 没有。**原样展示** */
        val roomKind: String? = null,
        /** 距参考点的档位文案；`null` = 算不出 */
        val distance: String? = null,
        /** 能待到几点（`20:55`）；`null` = 不做断言 */
        val untilAt: String? = null,
    ) {
        /** 标题：`文萃楼C 501a，空到 20:55`。算不出钟点时只说地点（**不编时间**）。 */
        val title: String get() = if (untilAt != null) "$place，空到 $untilAt" else place

        /**
         * 副文案：距哪儿多远 · 几层 · 房间名 · 这栋楼还有几间。
         * 四段都可能没有，全没有时返回 `null`（那一行就不渲染）。
         */
        val detail: String? get() = listOfNotNull(
            distance,
            floor?.let { "$it 层" },
            roomKind,
            buildingFreeCount.takeIf { it > 0 }?.let { "这栋楼还有 $it 间" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
        /**
         * 有东西可展示吗 —— 决定**这张卡要不要出现**。为 `false` 时整块区域不渲染。
         *
         * ⚠️⚠️ 这里问的是「**这张卡能不能显示**」，**不是**「有没有候选」。
         *    2026-10-12 真机栽过一次：空闲态曾经写成 `FREE_ROOM -> options.isNotEmpty()`，
         *    于是**取不到空教室时整张卡静默消失** —— 界面看起来完全正常（就是少了一块），
         *    用户分不清「没查到数据」和「功能坏了」。更糟的是详情页那句兜底文案
         *    [NO_CANDIDATE_HINT_NOW] **永远够不着**：详情页唯一的入口就是这张卡。
         *    ⇒ 「没有候选」必须由 [studySpotCardOf] 用文案表达，**不许在这里退化成「不显示」**。
         */
        val hasContent: Boolean
            get() = when (kind) {
                Kind.IN_CLASS -> courseTitle != null
                Kind.HAS_BOOKING -> bookingTitle != null
                // 空闲态**永远可显示**：至少有「现在没课」+「暂未查到空教室」两句真话可说。
                // 别改回 options.isNotEmpty() —— 那正是上面那个静默消失的成因。
                Kind.FREE_ROOM -> true
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
     * 组装完整建议（**教室级**）。取不到数据也只是 `options` 为空，
     * 由 [Plan.hasContent] 决定要不要显示。
     *
     * @param at **用户实际会到那儿的时刻**。见下。
     * @param rooms 各间教室在当前（到达）时段的空闲情况
     * @param reference 距离的**参考点**（用户大概在哪）；`null` = 不给距离，也不加位置分
     *
     * ## ⚠️ 为什么不是「现在」，而是「到达时刻」
     *
     * 空教室数是**按时段（节次）**算的，而正在上课的人**到不了**——他要等下课。
     * 拿「现在这一节」的数说「下课后去文萃楼F」，那是**现在**的数；
     * 12:25 人到了，第 6 节（13:20 起）的课已经排上，情况可能完全不同。
     * ⇒ 上课中时调用方要传**下课钟点**（[NextCourse.endsAt]），其余情况传当下。
     *   这样标题、数量、时长说的才是**同一时刻**的事。
     */
    fun planOf(
        at: LocalTime,
        table: TimeTable,
        course: NextCourse?,
        booking: Booking?,
        rooms: List<RoomFree>,
        reference: Reference? = null,
    ): Plan {
        val options = rankRooms(rooms, reference)
            .map { optionOf(it, table, at, reference?.label) }

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
     * 教室的显示名：`文萃楼C 501a`。
     *
     * ⚠️ 房间号取不出来时**只说楼名**，不硬编一个号（`文萃楼C` 比 `文萃楼C 0` 诚实）。
     */
    fun roomPlace(room: RoomFree): String {
        val label = roomLabelOf(room.classroomName, room.buildingName)
        return if (label.isBlank() || label == room.buildingName) room.buildingName
        else "${room.buildingName} $label"
    }

    /**
     * 把教室候选渲染成卡片 / 详情页上的一条。
     *
     * @param at 用户实际会到那儿的时刻（见 [planOf]）
     * @param referenceLabel 距离的**主语**（「你上一节课」）；`null` = 没有参考点，不提距离
     *
     * ⚠️ 距离那句话只陈述**事实**（课表上写着参考点是哪间教室 + 坐标表算出的相对远近），
     *    不写「你就在附近」那种**推论** —— 推错的时候用户会觉得这个 App 在瞎猜。
     */
    fun optionOf(
        candidate: RoomCandidate,
        table: TimeTable,
        at: LocalTime,
        referenceLabel: String? = null,
    ): SpotOption {
        val room = candidate.room
        return SpotOption(
            buildingId = room.buildingId,
            buildingName = room.buildingName,
            place = roomPlace(room),
            floor = room.floor,
            buildingFreeCount = room.buildingFreeCount,
            roomKind = roomKindOf(room.classroomName, room.buildingName),
            distance = referenceLabel?.let { distanceText(candidate.distance, it) },
            untilAt = freeUntilTime(table, at, room.freeSectionsAhead),
        )
    }

    /**
     * 有课、但**一个候选都没取到**时的那句话。
     *
     * ⚠️ 为什么不是「地点：X」：标题里已经写着教室了（`正在上操作系统（文萃楼I404）`），
     *    再说一遍等于什么都没说，用户还会以为功能坏了。
     * ⚠️ 为什么也不是干脆不显示：**降级不许无声无息** —— 真机上栽过：
     *    某次逐栋取空教室**全部失败**，卡片只剩标题 + 一句重复的「地点：综教B301」，
     *    看起来完全正常（同一栋楼其实空着 9 间）。
     *    这句话只声明「我没查到」，**不声明「没有空教室」** —— 后者我们并不知道
     *    （只看了前 `MAX_BUILDINGS` 栋，见 `DefaultStudySpotRepository`）。
     */
    const val NO_CANDIDATE_HINT = "下课后暂未查到空教室"

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
                        append("去${it.place}")
                        it.untilAt?.let { at -> append("（空到 $at）") }
                    }
                }
                val tail = option?.let { o -> o.detail?.takeIf { it.isNotBlank() } }
                val detail = listOfNotNull(head, tail).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: NO_CANDIDATE_HINT
                SpotCard(kind = Kind.IN_CLASS, title = title, detail = detail)
            }

            Kind.FREE_ROOM -> option?.let {
                // ⚠️ 副文案全没有时**不占那一行**，不给兜底句：
                //    标题（`文萃楼C 501a，空到 20:55`）自己已经说完了，
                //    再补一句「当前时段空闲」是重复（而重复会让卡片显得啰嗦、没有新信息）
                SpotCard(kind = Kind.FREE_ROOM, title = it.title, detail = it.detail)
            } ?: SpotCard(
                // ⚠️⚠️ 一间候选都没有时**也必须出卡**（这里曾经是 `?: null` ⇒ 整块区域静默消失）。
                //    说两句**真话**：确知的「现在没课」+ 只声明没查到的「暂未查到空教室」。
                //    ⚠️ 别改回 null —— 界面拿到 null 会直接 `return`，详情页就再也进不去了
                //    （见 [Plan.hasContent] 的注释）。
                kind = Kind.FREE_ROOM,
                title = FREE_HEADLINE,
                detail = NO_CANDIDATE_HINT_NOW,
            )
        }
    }

    /**
     * **楼级**的一条建议（**桌面组件走这条**）。
     *
     * ## ⚠️ 为什么组件留在楼级、不跟着走到教室级
     *
     * 组件 30 分钟才真刷一次（Doze 下更久），而快照里那句话是**有时效的断言**。
     * 「文萃楼C 501a 空着」比「文萃楼C 有空教室」细得多，一旦过期就更容易误导 ——
     * 用户按它跑到 501a，结果门开着但里面在上课。
     * ⇒ App 内（现拉现算）给到**教室**；组件只说**哪栋楼**（见 [coarseTitle]）。
     *
     * ⚠️ 排序仍然走 [rank]（空教室数 + 就近加成），所以**楼级的改进对组件自动生效**。
     */
    fun suggest(
        now: LocalTime,
        course: NextCourse?,
        booking: Booking?,
        buildings: List<BuildingFree>,
        lastClassroom: String? = null,
    ): Suggestion? {
        val best = rank(buildings, lastClassroom).firstOrNull()

        // 1. 有课 —— 这时候说「去哪自习」没有意义
        if (course != null) {
            val detail = if (best != null && course.endsAt != null) {
                "${course.endsAt}下课后，${candidatePlace(best)}有 ${best.freeCount} 间空教室"
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
            title = candidateTitle(best),
            detail = "当前时段空闲",
            buildingName = best.buildingName,
            buildingId = best.buildingId,
            freeCount = best.freeCount,
        )
    }

    // ==================================================== 详情页（点卡片进去看）

    /** 详情页上的一行候选（**一间教室**）—— 文案已经渲染好，界面只管画。 */
    data class SpotDetailRow(
        val buildingId: String,
        /** 「文萃楼C 501a」 */
        val place: String,
        /** 「空到 20:55」—— 与 [place] **同范围**（都是这一间教室），界面别自己拼时间 */
        val untilText: String,
        /** 距哪儿多远 · 几层 · 房间名 · 这栋楼还有几间；`null` = 都没有 */
        val detail: String?,
        /** 是不是推荐度最高的那一个（界面给它一个「推荐」标记） */
        val top: Boolean,
    )

    /**
     * 「现在去哪儿自习」详情页要显示的全部内容。
     */
    data class SpotDetail(
        val kind: Kind,
        /** 顶部那一句**事实**（正在上 X / 你 X 在 Y 有预约 / 现在没课） */
        val headline: String,
        /** 一句话交代「这一页在说什么、按什么排的」 */
        val subline: String,
        val rows: List<SpotDetailRow> = emptyList(),
        /** 一行都没有时的说明（**只声明「没查到」**）；有行时为 `null` */
        val emptyHint: String? = null,
    )

    /** 空闲态详情页一行都没有时的说明。⚠️ 同样只声明「没查到」，不声明「没有空教室」。 */
    const val NO_CANDIDATE_HINT_NOW = "暂未查到空教室"

    /**
     * 空闲态的那一句**事实**（「现在没课」）—— 卡片标题与详情页 headline **共用这一份**。
     *
     * ⚠️ 两处各写一个字面量迟早会漂移：同一个状态，卡片和详情页说法不一样，用户会以为出错了。
     */
    const val FREE_HEADLINE = "现在没课"

    /**
     * 组装详情页内容。
     *
     * ⚠️ 与卡片用的是**同一份** [Plan]（同一时刻、同一批候选）——
     *    详情页只是「把那一排候选展开看」，**不自己再算一遍**，
     *    否则点进去看到的数和卡片上的对不上（那才是真的说不清）。
     */
    fun detailOf(plan: Plan): SpotDetail {
        val rows = plan.options.mapIndexed { index, option ->
            SpotDetailRow(
                buildingId = option.buildingId,
                place = option.place,
                // ⚠️ 算不出「空到几点」时说「现在空着」而不是留空：
                //    这间教室在到达时刻确实是空的（取数时就按这个筛过），这是**事实**；
                //    而「空到几点」我们算不出来，所以不说。
                untilText = option.untilAt?.let { "空到 $it" } ?: "现在空着",
                detail = option.detail,
                top = index == 0,
            )
        }

        return when (plan.kind) {
            Kind.HAS_BOOKING -> SpotDetail(
                kind = plan.kind,
                headline = plan.bookingTitle ?: "你有座位预约",
                subline = plan.bookingDetail.orEmpty(),
            )

            Kind.IN_CLASS -> SpotDetail(
                kind = plan.kind,
                headline = plan.courseTitle ?: "有课",
                subline = plan.courseEndsAt?.let { "$it 下课后可以去这些地方" } ?: "下课后可以去这些地方",
                rows = rows,
                emptyHint = if (rows.isEmpty()) NO_CANDIDATE_HINT else null,
            )

            Kind.FREE_ROOM -> SpotDetail(
                kind = plan.kind,
                headline = FREE_HEADLINE,
                subline = if (rows.isEmpty()) "" else "按能待多久、离你多近排的",
                rows = rows,
                emptyHint = if (rows.isEmpty()) NO_CANDIDATE_HINT_NOW else null,
            )
        }
    }
}
