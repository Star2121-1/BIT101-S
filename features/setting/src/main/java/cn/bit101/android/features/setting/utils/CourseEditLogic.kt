package cn.bit101.android.features.setting.utils

import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseOverlayKind
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.schedule.CourseOverlayLogic
import cn.bit101.android.data.schedule.CourseOverlayLogic.withDisplay

/**
 * 「手动修改课程表」页的**纯逻辑**（无 Android 依赖，单测锁）。
 *
 * 页面本身只负责把这里算出来的东西画出来、把用户的操作转成 [CourseForm] 再交回来，
 * 这样「哪种状态」「怎么排」「能不能存」这类容易出错的规则都能脱离设备验证。
 */
enum class CourseRowKind {
    /** 教务原课，还没动过。 */
    ORIGINAL,

    /** 有一条 EDIT 覆盖，显示的是改过的值。 */
    EDITED,

    /** 本地新增（合并结果的 id 为负）。 */
    LOCAL_ADD,
}

/**
 * 课表里的一行。
 *
 * @param course 用户看到的那份（已合并覆盖层）；`LOCAL_ADD` 时 `id` 为负。
 * @param overlayId `EDIT` 是那条 EDIT 的 id，`LOCAL_ADD` 是 `-course.id`；教务原课为 null。
 */
data class CourseRow(
    val course: CourseScheduleEntity,
    val kind: CourseRowKind,
    val overlayId: Int?,
)

/**
 * 编辑 / 新增对话框的表单。
 *
 * `weeks` 存的是**用户可读的输入**（如 `1-16, 18`），落库前由 [CourseEditLogic.parseWeeks] 转成
 * `[1][2]…` 的存储格式 —— 让用户直接面对 `[1][2][3]` 太难填了。
 */
data class CourseForm(
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val weeks: String = "",
    val weekday: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 1,
    val campus: String = "",
    val credit: Int = 0,
    val hour: Int = 0,
    val type: String = "",
    val category: String = "",
    val department: String = "",
    val description: String = "",
    val number: String = "",
)

object CourseEditLogic {

    /** 周次上限 —— 一学期排不到 30 周，超了基本是填错。 */
    const val MAX_WEEK = 30

    fun weekdayText(weekday: Int): String = when (weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "周$weekday"
    }

    /**
     * 解析周次输入。容忍 `[1][2][3]`（存储格式）、`1,2,3`、`1-16`、`1-8,10` 等写法。
     *
     * @return 去重升序的集合；**解析不出或越界返回 null**（调用方据此提示用户）。
     */
    fun parseWeeksToSet(input: String): Set<Int>? {
        val text = input.trim()
        if (text.isEmpty()) return null

        val numbers = linkedSetOf<Int>()
        if (text.contains('[')) {
            val matches = Regex("""\[(\d+)]""").findAll(text).toList()
            if (matches.isEmpty()) return null
            matches.forEach { numbers += it.groupValues[1].toInt() }
        } else {
            val tokens = text.split(',', '，', '、', ';', '；', ' ', '\t', '\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            tokens.forEach { token ->
                val range = Regex("""^(\d+)\s*[-~—–至]\s*(\d+)$""").find(token)
                if (range != null) {
                    val from = range.groupValues[1].toInt()
                    val to = range.groupValues[2].toInt()
                    if (from > to) return null
                    for (w in from..to) numbers += w
                } else {
                    numbers += token.toIntOrNull() ?: return null
                }
            }
        }

        if (numbers.isEmpty()) return null
        if (numbers.any { it < 1 || it > MAX_WEEK }) return null
        return numbers
    }

    /** 解析成存储格式 `[1][2][3]`；失败返回 null。 */
    fun parseWeeks(input: String): String? =
        parseWeeksToSet(input)?.sorted()?.joinToString("") { "[$it]" }

    /**
     * 存储格式 → 给人看的紧凑写法：`[1][2][3][5]` → `1-3, 5`。
     * 解析不出时原样返回（不因为一行脏数据就崩）。
     */
    fun displayWeeks(stored: String): String {
        val set = parseWeeksToSet(stored) ?: return stored.trim()
        if (set.isEmpty()) return ""
        val sorted = set.sorted()
        val parts = ArrayList<String>()
        var from = sorted.first()
        var prev = from
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts += if (from == prev) "$from" else "$from-$prev"
            from = w
            prev = w
        }
        parts += if (from == prev) "$from" else "$from-$prev"
        return parts.joinToString(", ")
    }

    fun courseToForm(course: CourseScheduleEntity) = CourseForm(
        name = course.name,
        teacher = course.teacher,
        classroom = course.classroom,
        weeks = displayWeeks(course.weeks),
        weekday = course.weekday,
        startSection = course.start_section,
        endSection = course.end_section,
        campus = course.campus,
        credit = course.credit,
        hour = course.hour,
        type = course.type,
        category = course.category,
        department = course.department,
        description = course.description,
        number = course.number,
    )

    fun overlayToForm(overlay: CourseOverlayEntity) = CourseForm(
        name = overlay.name,
        teacher = overlay.teacher,
        classroom = overlay.classroom,
        weeks = displayWeeks(overlay.weeks),
        weekday = overlay.weekday,
        startSection = overlay.startSection,
        endSection = overlay.endSection,
        campus = overlay.campus,
        credit = overlay.credit,
        hour = overlay.hour,
        type = overlay.type,
        category = overlay.category,
        department = overlay.department,
        description = overlay.description,
        number = overlay.number,
    )

    /**
     * 给课表列表逐行定状态并按「星期 → 开始节次 → 课程名」排序。
     *
     * 判断依据（与 `CourseOverlayLogic.merge` 对齐）：
     * - `id < 0` → 本地新增，覆盖层 id = `-id`
     * - 否则这条 EDIT 覆盖**渲染出来的那一行**（[CourseOverlayLogic.toCourse]，id 归零后比内容）就是它
     * - 都不是 → 教务原课
     *
     * ⚠️ 这里**不能**拿 `CourseOverlayLogic.keyOf(overlay)`（锚点键）来找原课：
     * 传进来的 [courses] 是**合并后**的列表，改过时间的课那一行是新时间，
     * 而锚点键是老时间 —— 键对不上，改完时间的课就会被误判成"教务原课"。
     * 覆盖层渲染出来的行与原行内容完全一致（合并就是 `toCourse(edit, id = 原 id)`），所以按内容比对。
     *
     * 注意：被 `HIDE` 的课不会出现在 `courses` 里（合并时就滤掉了），所以这里不用管。
     */
    fun buildRows(
        courses: List<CourseScheduleEntity>,
        overlays: List<CourseOverlayEntity>,
    ): List<CourseRow> {
        val editByRenderedCourse = overlays
            .filter { it.kind == CourseOverlayKind.EDIT.name }
            .associateBy { CourseOverlayLogic.toCourse(it, id = 0) }

        return courses.map { course ->
            when {
                // 合并结果里「本地新增」的 id = -overlayId，反过来就拿到覆盖层 id
                course.id < 0 -> CourseRow(
                    course = course,
                    kind = CourseRowKind.LOCAL_ADD,
                    overlayId = -course.id,
                )

                else -> {
                    val edit = editByRenderedCourse[course.copy(id = 0)]
                    if (edit != null) {
                        CourseRow(course = course, kind = CourseRowKind.EDITED, overlayId = edit.id)
                    } else {
                        CourseRow(course = course, kind = CourseRowKind.ORIGINAL, overlayId = null)
                    }
                }
            }
        }.sortedWith(
            compareBy(
                { it.course.weekday },
                { it.course.start_section },
                { it.course.name },
            )
        )
    }

    /** 已隐藏的课（直接拿覆盖层渲染，因为它们已经不在合并结果里了）。 */
    fun hiddenOverlays(overlays: List<CourseOverlayEntity>): List<CourseOverlayEntity> =
        overlays.filter { it.kind == CourseOverlayKind.HIDE.name }

    /** 已修改 + 已新增的课（用于「恢复原样 / 删除」）。 */
    fun changedOverlays(overlays: List<CourseOverlayEntity>): List<CourseOverlayEntity> =
        overlays.filter {
            it.kind == CourseOverlayKind.EDIT.name || it.kind == CourseOverlayKind.ADD.name
        }

    /** 覆盖层类型的中文标签。 */
    fun overlayLabel(kind: String): String = when (CourseOverlayKind.parseOrNull(kind)) {
        CourseOverlayKind.EDIT -> "已修改"
        CourseOverlayKind.HIDE -> "已隐藏"
        CourseOverlayKind.ADD -> "已新增"
        null -> "未知"
    }

    // ── 校验 ──────────────────────────────────────────────────────────────

    private fun validateWeeks(weeks: String): String? {
        if (weeks.isBlank()) return "周次不能为空"
        if (parseWeeks(weeks) == null) return "周次格式不对（示例：1-16 或 1,3,5）"
        return null
    }

    /**
     * 编辑**教务原课**：时间 / 课程号现在也能改（改的是显示值，锚点不动），所以要一并校验。
     */
    fun validateEditForm(form: CourseForm): String? {
        if (form.name.isBlank()) return "课程名不能为空"
        if (form.weekday !in 1..7) return "星期需为 1~7"
        if (form.startSection < 1) return "开始节次需 ≥ 1"
        if (form.endSection < form.startSection) return "结束节次不能早于开始节次"
        validateWeeks(form.weeks)?.let { return it }
        return null
    }

    /** 新增（或编辑自己新增的课）：所有字段都要填全。 */
    fun validateAddForm(form: CourseForm): String? {
        if (form.name.isBlank()) return "课程名不能为空"
        if (form.number.isBlank()) return "课程号不能为空（新增的课需要一个课程号）"
        if (form.weekday !in 1..7) return "星期需为 1~7"
        if (form.startSection < 1) return "开始节次需 ≥ 1"
        if (form.endSection < form.startSection) return "结束节次不能早于开始节次"
        validateWeeks(form.weeks)?.let { return it }
        return null
    }

    // ── 构造覆盖层 ─────────────────────────────────────────────────────────

    /**
     * 编辑**教务原课**生成 EDIT 覆盖。
     *
     * 锚点（课程号 / 星期 / 开始节次）取自**原课**、原样不动；表单里的值只写进**显示值**
     * （连课程号 / 星期 / 开始节次也是）。所以「改上课时间」能生效 ——
     * 合并是按锚点找原课的（`CourseOverlayLogic.merge`），显示值怎么改都不影响匹配。
     */
    fun buildOverlayForExistingCourse(
        course: CourseScheduleEntity,
        form: CourseForm,
        overlayId: Int,
        nowMillis: Long,
    ): CourseOverlayEntity = CourseOverlayLogic.toOverlay(
        kind = CourseOverlayKind.EDIT,
        source = course,
        id = overlayId,
        nowMillis = nowMillis,
    ).withDisplay(
        number = form.number.trim(),
        name = form.name.trim(),
        teacher = form.teacher.trim(),
        classroom = form.classroom.trim(),
        description = form.description.trim(),
        weeks = parseWeeks(form.weeks) ?: course.weeks,
        weekday = form.weekday,
        startSection = form.startSection,
        endSection = form.endSection,
        campus = form.campus.trim(),
        credit = form.credit,
        hour = form.hour,
        type = form.type.trim(),
        category = form.category.trim(),
        department = form.department.trim(),
        nowMillis = nowMillis,
    )

    /**
     * 新增一门课（FAB）生成 ADD 覆盖。
     *
     * ADD 没有"原课"，**锚点就是它自己** —— 锚点与显示值都取表单。
     *
     * @param overlayId 新增传 0。
     */
    fun buildOverlayForAddedCourse(
        term: String,
        form: CourseForm,
        overlayId: Int,
        nowMillis: Long,
    ): CourseOverlayEntity = CourseOverlayEntity(
        id = overlayId,
        kind = CourseOverlayKind.ADD.name,
        term = term,
        // 新增的课没有原课，锚点就是它自己
        anchorNumber = form.number.trim(),
        anchorWeekday = form.weekday,
        anchorStartSection = form.startSection,
        number = form.number.trim(),
        weekday = form.weekday,
        startSection = form.startSection,
        name = form.name.trim(),
        teacher = form.teacher.trim(),
        classroom = form.classroom.trim(),
        description = form.description.trim(),
        weeks = parseWeeks(form.weeks) ?: "",
        endSection = form.endSection,
        campus = form.campus.trim(),
        credit = form.credit,
        hour = form.hour,
        type = form.type.trim(),
        category = form.category.trim(),
        department = form.department.trim(),
        updatedAtMillis = nowMillis,
    )

    /**
     * 编辑一节课**自己新增的课**（合并结果 `id < 0`）生成 ADD 覆盖。
     *
     * 锚点取自 [source]（就是那节课自己），显示值全按表单来 —— 所以手动加的课也能随便改时间 / 课程号。
     *
     * @param overlayId 传原覆盖层 id（= `-course.id`）。
     */
    fun buildOverlayForAddedCourse(
        source: CourseScheduleEntity,
        form: CourseForm,
        overlayId: Int,
        nowMillis: Long,
    ): CourseOverlayEntity = CourseOverlayLogic.toOverlay(
        kind = CourseOverlayKind.ADD,
        source = source,
        id = overlayId,
        nowMillis = nowMillis,
    ).withDisplay(
        number = form.number.trim(),
        name = form.name.trim(),
        teacher = form.teacher.trim(),
        classroom = form.classroom.trim(),
        description = form.description.trim(),
        weeks = parseWeeks(form.weeks) ?: source.weeks,
        weekday = form.weekday,
        startSection = form.startSection,
        endSection = form.endSection,
        campus = form.campus.trim(),
        credit = form.credit,
        hour = form.hour,
        type = form.type.trim(),
        category = form.category.trim(),
        department = form.department.trim(),
        nowMillis = nowMillis,
    )

    /** 隐藏一节教务原课。 */
    fun buildHideOverlay(
        course: CourseScheduleEntity,
        nowMillis: Long,
    ): CourseOverlayEntity = CourseOverlayLogic.toOverlay(
        kind = CourseOverlayKind.HIDE,
        source = course,
        nowMillis = nowMillis,
    )
}
