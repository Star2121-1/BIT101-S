package cn.bit101.android.data.schedule

import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseOverlayKind
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.schedule.CourseOverlayLogic.withDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手动修改课程表」合并逻辑的单测。
 *
 * 锁的是四件事：**改 / 藏 / 加** 三种意图各自的语义、它们**叠加时的优先级**、
 * 「匹配不上时不许乱动课表」，以及**锚点与显示值分离**（改时间还能匹配上原课）。
 * 这些都是用户会直接看到的东西 —— 比如藏掉一节退了的课，下次同步后它又冒出来，
 * 用户只会觉得「这功能没用」。
 */
class CourseOverlayLogicTest {

    private val term = "2026-2027-1"

    private fun course(
        id: Int = 1,
        name: String = "计算机网络",
        number: String = "C001",
        classroom: String = "文萃楼404",
        weekday: Int = 3,
        startSection: Int = 3,
        endSection: Int = 5,
        weeks: String = "[1][2][3][4][5]",
    ) = CourseScheduleEntity(
        id = id,
        term = term,
        name = name,
        teacher = "张三",
        classroom = classroom,
        description = "",
        weeks = weeks,
        weekday = weekday,
        start_section = startSection,
        end_section = endSection,
        campus = "中关村",
        number = number,
        credit = 3,
        hour = 48,
        type = "必修",
        category = "文化课",
        department = "计算机学院",
    )

    /**
     * 覆盖层：锚点默认指向 [course] 默认那节（C001 / 周三 / 3 节）；
     * 显示值默认与锚点相同 —— 要测「改时间」就只改显示值参数。
     */
    private fun overlay(
        id: Int = 1,
        kind: CourseOverlayKind = CourseOverlayKind.EDIT,
        anchorNumber: String = "C001",
        anchorWeekday: Int = 3,
        anchorStartSection: Int = 3,
        number: String = anchorNumber,
        name: String = "计算机网络",
        classroom: String = "文萃楼404",
        weekday: Int = anchorWeekday,
        startSection: Int = anchorStartSection,
        weeks: String = "[1][2][3][4][5]",
    ) = CourseOverlayEntity(
        id = id,
        kind = kind.name,
        term = term,
        anchorNumber = anchorNumber,
        anchorWeekday = anchorWeekday,
        anchorStartSection = anchorStartSection,
        number = number,
        name = name,
        teacher = "张三",
        classroom = classroom,
        description = "",
        weeks = weeks,
        weekday = weekday,
        startSection = startSection,
        endSection = 5,
        campus = "中关村",
        credit = 3,
        hour = 48,
        type = "必修",
        category = "文化课",
        department = "计算机学院",
        updatedAtMillis = 1_000L,
    )

    // ------------------------------------------------------------ 没有覆盖层

    @Test
    fun `没有覆盖层时原样返回`() {
        val courses = listOf(course(id = 1), course(id = 2, number = "C002", weekday = 4))

        assertEquals(courses, CourseOverlayLogic.merge(courses, emptyList()))
    }

    // ------------------------------------------------------------ 改

    @Test
    fun `EDIT 替换内容但保留原始行 id`() {
        val courses = listOf(course(id = 7, classroom = "文萃楼404"))
        val overlay = overlay(classroom = "文萃楼501")

        val merged = CourseOverlayLogic.merge(courses, listOf(overlay))

        assertEquals(1, merged.size)
        assertEquals("文萃楼501", merged[0].classroom)
        // id 保留：UI 的列表 key 才稳定，不会每次重组都当新行
        assertEquals(7, merged[0].id)
    }

    /** 用户改了周次（教务给错了）也要生效 —— 合并后的 weeks 才是用户看到的。 */
    @Test
    fun `EDIT 可以改周次`() {
        val courses = listOf(course(weeks = "[1][2]"))
        val overlay = overlay(weeks = "[1][2][3][4][5][6]")

        val merged = CourseOverlayLogic.merge(courses, listOf(overlay))

        assertEquals("[1][2][3][4][5][6]", merged[0].weeks)
    }

    /**
     * ⚠️ **锚点与显示值分离的核心用例**：改上课时间（3-5 节 → 1-2 节）。
     *
     * 如果拿"改完之后的样子"去匹配原课，这里就会静默失配、修改完全不生效 ——
     * 这正是同事写 UI 时发现的模型缺陷。
     */
    @Test
    fun `EDIT 可以改上课时间且仍然匹配原课`() {
        val courses = listOf(course(id = 7, weekday = 3, startSection = 3, endSection = 5))
        // 锚点仍是周三 3 节，显示值改成周四 1-2 节
        val overlay = overlay(weekday = 4, startSection = 1).copy(endSection = 2)

        // 键按锚点算 → 与 / 3 / 3 的原课匹配
        assertEquals(CourseOverlayLogic.keyOf(courses[0]), CourseOverlayLogic.keyOf(overlay))

        val merged = CourseOverlayLogic.merge(courses, listOf(overlay))

        assertEquals(1, merged.size)
        assertEquals(4, merged[0].weekday)
        assertEquals(1, merged[0].start_section)
        assertEquals(2, merged[0].end_section)
        // id 还是原课那行的
        assertEquals(7, merged[0].id)
    }

    // ------------------------------------------------------------ 藏

    @Test
    fun `HIDE 让那节课消失`() {
        val courses = listOf(course(id = 1), course(id = 2, number = "C002", weekday = 4))
        val hide = overlay(kind = CourseOverlayKind.HIDE)

        val merged = CourseOverlayLogic.merge(courses, listOf(hide))

        assertEquals(listOf(2), merged.map { it.id })
    }

    /** 既要改又要藏（先改后藏）：**藏优先** —— 用户最后的意图是"这节课别出现"。 */
    @Test
    fun `EDIT 与 HIDE 同时命中时藏优先`() {
        val courses = listOf(course(id = 1))
        val overlays = listOf(
            overlay(id = 1, kind = CourseOverlayKind.EDIT, classroom = "改过的教室"),
            overlay(id = 2, kind = CourseOverlayKind.HIDE),
        )

        assertTrue(CourseOverlayLogic.merge(courses, overlays).isEmpty())
    }

    // ------------------------------------------------------------ 加

    @Test
    fun `ADD 追加到末尾且 id 为负数`() {
        val courses = listOf(course(id = 1))
        val added = overlay(
            id = 9,
            kind = CourseOverlayKind.ADD,
            anchorNumber = "X9",
            name = "旁听的课",
        )

        val merged = CourseOverlayLogic.merge(courses, listOf(added))

        assertEquals(2, merged.size)
        assertEquals(1, merged[0].id)
        assertEquals("旁听的课", merged[1].name)
        // 负数 id = 「本地新增」，UI 据此区分（可以删、不能"恢复原样"）
        assertEquals(-9, merged[1].id)
    }

    /** 正常路径上 UI 让用户"删除"新增的课；真出现 ADD+HIDE 时也别让它幽灵般浮现。 */
    @Test
    fun `新增的课被藏起来时不出现`() {
        val added = overlay(id = 9, kind = CourseOverlayKind.ADD, anchorNumber = "X9", name = "旁听的课")
        val hide = overlay(id = 10, kind = CourseOverlayKind.HIDE, anchorNumber = "X9", name = "旁听的课")

        assertTrue(CourseOverlayLogic.merge(emptyList(), listOf(added, hide)).isEmpty())
    }

    // ------------------------------------------------------------ 匹配不上 / 脏数据

    /** 教务把课删了或改了时间 → 覆盖层匹配不上：**忽略，但别把它当成新课加进去**。 */
    @Test
    fun `匹配不上的 EDIT 不产生任何行`() {
        val courses = listOf(course(id = 1, number = "C001"))
        val stale = overlay(kind = CourseOverlayKind.EDIT, anchorNumber = "C999")

        val merged = CourseOverlayLogic.merge(courses, listOf(stale))

        assertEquals(listOf(1), merged.map { it.id })
    }

    @Test
    fun `匹配不上的 HIDE 不影响其它课`() {
        val courses = listOf(course(id = 1), course(id = 2, number = "C002", weekday = 4))
        val stale = overlay(kind = CourseOverlayKind.HIDE, anchorNumber = "C999")

        assertEquals(2, CourseOverlayLogic.merge(courses, listOf(stale)).size)
    }

    /** 数据格式变更 / 手写脏数据：认不出的 kind 跳过，不能连带把别人的修改也弄丢。 */
    @Test
    fun `认不出的 kind 被跳过且不影响其它覆盖`() {
        val courses = listOf(course(id = 1), course(id = 2, number = "C002", weekday = 4))
        val overlays = listOf(
            overlay(id = 1).copy(kind = "SOMETHING_ELSE"),
            overlay(id = 2, kind = CourseOverlayKind.HIDE, anchorNumber = "C002", anchorWeekday = 4),
        )

        val merged = CourseOverlayLogic.merge(courses, overlays)

        assertEquals(listOf(1), merged.map { it.id })
    }

    // ------------------------------------------------------------ 键与锚点

    /** ⚠️ 课程侧的自然键**不能包含行 id**：同步是"删光再插"，id 每次都变。 */
    @Test
    fun `课程自然键不含行 id`() {
        val a = CourseOverlayLogic.keyOf(course(id = 1))
        val b = CourseOverlayLogic.keyOf(course(id = 999))

        assertEquals(a, b)
    }

    @Test
    fun `课程自然键区分星期与节次`() {
        val base = CourseOverlayLogic.keyOf(course(weekday = 3, startSection = 3))

        assertTrue(base != CourseOverlayLogic.keyOf(course(weekday = 4, startSection = 3)))
        assertTrue(base != CourseOverlayLogic.keyOf(course(weekday = 3, startSection = 4)))
    }

    @Test
    fun `toOverlay 的锚点与初值都取来源行`() {
        val source = course(id = 5, number = "C007", weekday = 2, startSection = 9)

        val overlay = CourseOverlayLogic.toOverlay(CourseOverlayKind.EDIT, source, id = 3, nowMillis = 42L)

        assertEquals(3, overlay.id)
        assertEquals("EDIT", overlay.kind)
        // 锚点
        assertEquals("C007", overlay.anchorNumber)
        assertEquals(2, overlay.anchorWeekday)
        assertEquals(9, overlay.anchorStartSection)
        // 显示值初值与锚点一致
        assertEquals("C007", overlay.number)
        assertEquals(2, overlay.weekday)
        assertEquals(9, overlay.startSection)
        assertEquals("文萃楼404", overlay.classroom)
        assertEquals(42L, overlay.updatedAtMillis)
    }

    /** [CourseOverlayLogic.withDisplay] 只改显示值 —— 编辑表单走它就不可能手滑改掉锚点。 */
    @Test
    fun `withDisplay 不动锚点`() {
        val base = CourseOverlayLogic.toOverlay(CourseOverlayKind.EDIT, course(number = "C007", weekday = 2, startSection = 9))

        val edited = base.withDisplay(name = "改过的课名", classroom = "新教室", weekday = 5, startSection = 1)

        assertEquals("改过的课名", edited.name)
        assertEquals("新教室", edited.classroom)
        assertEquals(5, edited.weekday)
        assertEquals(1, edited.startSection)
        // 锚点一个都没动
        assertEquals(base.anchorNumber, edited.anchorNumber)
        assertEquals(base.anchorWeekday, edited.anchorWeekday)
        assertEquals(base.anchorStartSection, edited.anchorStartSection)
    }

    /** 更新已有 EDIT 覆盖时：锚点**沿用 existing 的**（incoming 的锚点是 UI 从合并行取的，会漂移）。 */
    @Test
    fun `更新已有覆盖时锚点沿用 existing`() {
        val existing = CourseOverlayLogic.toOverlay(
            CourseOverlayKind.EDIT,
            course(number = "C008", weekday = 3, startSection = 2),
        )
        // UI 二次编辑时传来的 incoming：显示值已是"改过之后"的新时间，锚点跟着漂了
        val incoming = existing.copy(weekday = 5, startSection = 1, anchorWeekday = 5, anchorStartSection = 1)

        val normalized = CourseOverlayLogic.normalizeAnchors(incoming, existing = existing)

        assertEquals(existing.anchorNumber, normalized.anchorNumber)
        assertEquals(existing.anchorWeekday, normalized.anchorWeekday)
        assertEquals(existing.anchorStartSection, normalized.anchorStartSection)
        // 显示值保持 incoming 的（用户改了时间就该是新时间）
        assertEquals(5, normalized.weekday)
        assertEquals(1, normalized.startSection)
    }

    /** ADD 的锚点永远等于自己的显示值（它没有教务原课可指）。 */
    @Test
    fun `ADD 的锚点跟随自己的显示值`() {
        val add = CourseOverlayLogic.toOverlay(CourseOverlayKind.ADD, course(number = "C009"))
            .withDisplay(weekday = 6, startSection = 3)

        val normalized = CourseOverlayLogic.normalizeAnchors(add, existing = null)

        assertEquals(add.number, normalized.anchorNumber)
        assertEquals(6, normalized.anchorWeekday)
        assertEquals(3, normalized.anchorStartSection)
    }
}
