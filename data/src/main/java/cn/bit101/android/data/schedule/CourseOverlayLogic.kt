package cn.bit101.android.data.schedule

import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseOverlayKind
import cn.bit101.android.data.database.entity.CourseScheduleEntity

/**
 * 「手动修改课程表」的**合并逻辑**（纯函数，单测锁）。
 *
 * ## 数据流
 *
 * ```
 * course_schedule（教务，同步时全量覆盖）──┐
 *                                          ├─► merge() ─► 用户看到的课表（App 表格 / 桌面组件）
 * course_overlay（用户的手动修改）────────┘
 * ```
 *
 * 合并发生在**读路径**（`DefaultCoursesRepo`），所以桌面组件不用知道覆盖层的存在
 * 也能显示正确内容。
 *
 * ## 自然键
 *
 * `term + number + weekday + startSection` —— **刻意不用行 id**：同步是"删光再插"，
 * id 每次都会变，用 id 当键会让所有修改在第一次同步后失联。
 */
object CourseOverlayLogic {

    /**
     * 本地新增课程的 id 约定：**负数**（= `-overlayId`）。
     *
     * 合并结果里所有行都是 [CourseScheduleEntity]，UI 需要区分"教务的课"和
     * "我手动加的课"（后者可删除、不能"恢复原样"）—— 负数 id 就是那个标记，
     * 且与真实自增 id（正数）不会撞。
     */
    fun addedCourseId(overlayId: Int): Int = -overlayId

    /** 自然键（学期 + 课程号 + 星期 + 开始节次）。 */
    fun key(term: String, number: String, weekday: Int, startSection: Int): String =
        "$term|$number|$weekday|$startSection"

    fun keyOf(course: CourseScheduleEntity): String =
        key(course.term, course.number, course.weekday, course.start_section)

    /**
     * 覆盖层的键 = 它的**锚点**（指向"改的是哪一节原课"），
     * **不是**它改完之后的样子。
     *
     * ⚠️ 这就是「改上课时间」能生效的关键：用户把 3-5 节改到 1-2 节时，
     * 锚点仍是 3-5 节 → 合并时照样匹配得上原课。
     */
    fun keyOf(overlay: CourseOverlayEntity): String =
        key(overlay.term, overlay.anchorNumber, overlay.anchorWeekday, overlay.anchorStartSection)

    /**
     * 把覆盖层套到原始课表上。
     *
     * 规则（按顺序）：
     * 1. `HIDE` 命中自然键 → 这条**不出现**（优先级最高：先改后藏也算藏）
     * 2. `EDIT` 命中 → **用覆盖层的值替换**，但保留原始行的 `id`
     *    （保留 id 是为了让 UI 的列表 key 稳定、不至于每次重组都换 key 闪一下）
     * 3. `ADD` → **追加**到结果末尾，`id` 取负数（见 [addedCourseId]）
     * 4. 匹配不上的覆盖层（原课已被教务删掉/改时间）→ 当次合并**忽略**，但**不删数据**：
     *    教务可能只是临时改一下，留着下次还能接上
     */
    fun merge(
        originals: List<CourseScheduleEntity>,
        overlays: List<CourseOverlayEntity>,
    ): List<CourseScheduleEntity> {
        if (overlays.isEmpty()) return originals

        val hiddenKeys = HashSet<String>()
        val edits = HashMap<String, CourseOverlayEntity>()
        val adds = ArrayList<CourseOverlayEntity>()

        overlays.forEach { overlay ->
            when (CourseOverlayKind.parseOrNull(overlay.kind)) {
                CourseOverlayKind.HIDE -> hiddenKeys += keyOf(overlay)
                CourseOverlayKind.EDIT -> edits[keyOf(overlay)] = overlay
                CourseOverlayKind.ADD -> adds += overlay
                // 认不出的 kind：跳过（数据格式变更时不至于崩，也不会把课表搞乱）
                null -> Unit
            }
        }

        val merged = ArrayList<CourseScheduleEntity>(originals.size + adds.size)

        originals.forEach { course ->
            val key = keyOf(course)
            if (key in hiddenKeys) return@forEach
            val edit = edits[key]
            merged += if (edit == null) course else toCourse(edit, id = course.id)
        }

        adds.forEach { overlay ->
            // 新增的课也可能被 HIDE（正常不该出现，UI 让我们用"删除"），
            // 真出现时按"藏起来"处理，别让它在课表上幽灵般浮现
            if (keyOf(overlay) !in hiddenKeys) {
                merged += toCourse(overlay, id = addedCourseId(overlay.id))
            }
        }

        return merged
    }

    /** 覆盖层 → 课程行。 */
    fun toCourse(overlay: CourseOverlayEntity, id: Int): CourseScheduleEntity = CourseScheduleEntity(
        id = id,
        term = overlay.term,
        name = overlay.name,
        teacher = overlay.teacher,
        classroom = overlay.classroom,
        description = overlay.description,
        weeks = overlay.weeks,
        weekday = overlay.weekday,
        start_section = overlay.startSection,
        end_section = overlay.endSection,
        campus = overlay.campus,
        number = overlay.number,
        credit = overlay.credit,
        hour = overlay.hour,
        type = overlay.type,
        category = overlay.category,
        department = overlay.department,
    )

    /**
     * 课程行 → 覆盖层（编辑/隐藏某节课时用）。
     *
     * @param kind 想写哪种覆盖
     * @param id 覆盖层自己的 id：**新增**传 0（让 Room 自增），改已有的传原 id
     * @param source 来源行：`EDIT / HIDE` 传**教务原课**（拿它的自然键），
     *   `ADD` 传那节新加的课（自然键就是它自己的）
     */
    fun toOverlay(
        kind: CourseOverlayKind,
        source: CourseScheduleEntity,
        id: Int = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ): CourseOverlayEntity = CourseOverlayEntity(
        id = id,
        kind = kind.name,
        term = source.term,
        // 锚点与显示值一开始都取自 source；编辑时只改**显示值**（用 withDisplay）
        anchorNumber = source.number,
        anchorWeekday = source.weekday,
        anchorStartSection = source.start_section,
        number = source.number,
        name = source.name,
        teacher = source.teacher,
        classroom = source.classroom,
        description = source.description,
        weeks = source.weeks,
        weekday = source.weekday,
        startSection = source.start_section,
        endSection = source.end_section,
        campus = source.campus,
        credit = source.credit,
        hour = source.hour,
        type = source.type,
        category = source.category,
        department = source.department,
        updatedAtMillis = nowMillis,
    )

    /**
     * 写入前的**锚点规整**（纯函数，单测锁）。
     *
     * 规则来自一个真实 bug：用户**第二次**编辑同一节课时，UI 手上只有"合并后的行"
     * （它的时间是**上一次改过**的），若直接拿它取锚点，锚点就漂移了 ——
     * 于是下一次合并找不到原课，用户的修改**静默失效**、课表退回教务原样。
     *
     * 所以：
     * - `ADD` 没有原课，锚点**跟着显示值走**（用户改自己加的课的时间是正常需求）
     * - `EDIT / HIDE` 一旦建好，**锚点不可变**（更新时一律沿用 existing 的锚点）
     * - 新建（`existing == null`）用传入的锚点
     */
    fun normalizeAnchors(
        incoming: CourseOverlayEntity,
        existing: CourseOverlayEntity?,
    ): CourseOverlayEntity = when {
        incoming.kind == CourseOverlayKind.ADD.name -> incoming.copy(
            anchorNumber = incoming.number,
            anchorWeekday = incoming.weekday,
            anchorStartSection = incoming.startSection,
        )

        existing != null -> incoming.copy(
            anchorNumber = existing.anchorNumber,
            anchorWeekday = existing.anchorWeekday,
            anchorStartSection = existing.anchorStartSection,
        )

        else -> incoming
    }

    /**
     * 只改**显示值**（编辑表单用），锚点原样保留。
     *
     * 刻意做成这样一个入口：UI 不可能"手滑"把锚点一起改了 ——
     * 锚点一动就等于换了覆盖对象，用户的修改会莫名其妙失效。
     */
    fun CourseOverlayEntity.withDisplay(
        number: String = this.number,
        name: String = this.name,
        teacher: String = this.teacher,
        classroom: String = this.classroom,
        description: String = this.description,
        weeks: String = this.weeks,
        weekday: Int = this.weekday,
        startSection: Int = this.startSection,
        endSection: Int = this.endSection,
        campus: String = this.campus,
        credit: Int = this.credit,
        hour: Int = this.hour,
        type: String = this.type,
        category: String = this.category,
        department: String = this.department,
        nowMillis: Long = System.currentTimeMillis(),
    ): CourseOverlayEntity = copy(
        number = number,
        name = name,
        teacher = teacher,
        classroom = classroom,
        description = description,
        weeks = weeks,
        weekday = weekday,
        startSection = startSection,
        endSection = endSection,
        campus = campus,
        credit = credit,
        hour = hour,
        type = type,
        category = category,
        department = department,
        updatedAtMillis = nowMillis,
    )
}
