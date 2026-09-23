package cn.bit101.android.features.setting.utils

import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseOverlayKind
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.schedule.CourseOverlayLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手动修改课程表」页纯逻辑的单测。
 *
 * 重点锁三件容易出错的事：
 * 1. **逐行定状态**（教务原课 / 已修改 / 本地新增）—— 判错了用户就看不到自己的修改
 * 2. **编辑教务原课时自然键必须取自原课** —— 取错（跟着表单走）修改会被合并逻辑静默忽略，
 *    而且界面上完全没有报错，只能靠单测挡
 * 3. **周次解析/格式化** —— 用户填的是 `1-16`，库里存的是 `[1][2]…`，转换错了整节课的周次就废了
 */
class CourseEditLogicTest {

    private val term = "2026-2027-1"

    private fun course(
        id: Int,
        number: String = "N1",
        weekday: Int = 1,
        startSection: Int = 1,
        endSection: Int = 2,
        name: String = "高等数学",
        weeks: String = "[1][2]",
        classroom: String = "教101",
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
        campus = "良乡校区",
        number = number,
        credit = 4,
        hour = 64,
        type = "必修",
        category = "文化课",
        department = "数学系",
    )

    private fun overlay(
        id: Int,
        kind: CourseOverlayKind,
        number: String = "N1",
        weekday: Int = 1,
        startSection: Int = 1,
        name: String = "高等数学",
        classroom: String = "教101",
        weeks: String = "[1][2]",
        endSection: Int = 2,
        // 默认锚点 = 显示值（覆盖层一开始就是照着原课建的）
        anchorNumber: String = number,
        anchorWeekday: Int = weekday,
        anchorStartSection: Int = startSection,
    ) = CourseOverlayEntity(
        id = id,
        kind = kind.name,
        term = term,
        anchorNumber = anchorNumber,
        anchorWeekday = anchorWeekday,
        anchorStartSection = anchorStartSection,
        number = number,
        weekday = weekday,
        startSection = startSection,
        name = name,
        teacher = "张三",
        classroom = classroom,
        description = "",
        weeks = weeks,
        endSection = endSection,
        campus = "良乡校区",
        credit = 4,
        hour = 64,
        type = "必修",
        category = "文化课",
        department = "数学系",
        updatedAtMillis = 0L,
    )

    // ── 周次 ─────────────────────────────────────────────────────────────

    @Test
    fun `周次 - 存储格式可解析`() {
        assertEquals(setOf(1, 2, 3), CourseEditLogic.parseWeeksToSet("[1][2][3]"))
    }

    @Test
    fun `周次 - 区间写法展开`() {
        assertEquals((1..16).toSet(), CourseEditLogic.parseWeeksToSet("1-16"))
    }

    @Test
    fun `周次 - 逗号加区间混合`() {
        assertEquals(setOf(1, 2, 3, 5, 7, 8), CourseEditLogic.parseWeeksToSet("1-3, 5, 7-8"))
    }

    @Test
    fun `周次 - 全角逗号与中文顿号也认`() {
        assertEquals(setOf(1, 3, 5), CourseEditLogic.parseWeeksToSet("1，3、5"))
    }

    @Test
    fun `周次 - 非法输入返回 null`() {
        assertNull(CourseEditLogic.parseWeeksToSet("abc"))
        assertNull(CourseEditLogic.parseWeeksToSet(""))
        assertNull(CourseEditLogic.parseWeeksToSet("1-"))
        assertNull(CourseEditLogic.parseWeeksToSet("8-2"))
    }

    @Test
    fun `周次 - 越界返回 null`() {
        assertNull(CourseEditLogic.parseWeeksToSet("0"))
        assertNull(CourseEditLogic.parseWeeksToSet("1-40"))
    }

    @Test
    fun `周次 - 解析为存储格式`() {
        assertEquals("[1][2][3]", CourseEditLogic.parseWeeks("1-3"))
    }

    @Test
    fun `周次 - 显示时压缩连续区间`() {
        assertEquals("1-3, 5", CourseEditLogic.displayWeeks("[1][2][3][5]"))
        assertEquals("8", CourseEditLogic.displayWeeks("[8]"))
        assertEquals("—不适用", CourseEditLogic.displayWeeks("—不适用"))
    }

    @Test
    fun `周次 - 显示与解析可来回`() {
        val stored = "[1][2][3][4][5][6][7][8][10]"
        assertEquals(stored, CourseEditLogic.parseWeeks(CourseEditLogic.displayWeeks(stored)))
    }

    @Test
    fun `星期文案`() {
        assertEquals("周一", CourseEditLogic.weekdayText(1))
        assertEquals("周日", CourseEditLogic.weekdayText(7))
    }

    // ── 逐行定状态 ────────────────────────────────────────────────────────

    @Test
    fun `没有覆盖层时全是教务原课`() {
        val rows = CourseEditLogic.buildRows(listOf(course(id = 3)), emptyList())
        assertEquals(CourseRowKind.ORIGINAL, rows.single().kind)
        assertNull(rows.single().overlayId)
    }

    @Test
    fun `自然键命中 EDIT 就是已修改并带上覆盖层 id`() {
        val rows = CourseEditLogic.buildRows(
            courses = listOf(course(id = 3, number = "N1", weekday = 1, startSection = 1)),
            overlays = listOf(overlay(id = 42, kind = CourseOverlayKind.EDIT)),
        )
        assertEquals(CourseRowKind.EDITED, rows.single().kind)
        assertEquals(42, rows.single().overlayId)
    }

    @Test
    fun `自然键不一致的 EDIT 不算命中`() {
        val rows = CourseEditLogic.buildRows(
            courses = listOf(course(id = 3, number = "N1", weekday = 1, startSection = 1)),
            overlays = listOf(overlay(id = 42, kind = CourseOverlayKind.EDIT, weekday = 3)),
        )
        assertEquals(CourseRowKind.ORIGINAL, rows.single().kind)
        assertNull(rows.single().overlayId)
    }

    @Test
    fun `HIDE 覆盖层不影响列表状态（被藏的课本就不在 courses 里）`() {
        val rows = CourseEditLogic.buildRows(
            courses = listOf(course(id = 3)),
            overlays = listOf(overlay(id = 9, kind = CourseOverlayKind.HIDE)),
        )
        assertEquals(CourseRowKind.ORIGINAL, rows.single().kind)
    }

    @Test
    fun `负数 id 是本地新增并还原出覆盖层 id`() {
        val added = course(id = -7, name = "旁听课")
        val rows = CourseEditLogic.buildRows(listOf(added), emptyList())
        assertEquals(CourseRowKind.LOCAL_ADD, rows.single().kind)
        assertEquals(7, rows.single().overlayId)
    }

    @Test
    fun `按星期与开始节次排序`() {
        val rows = CourseEditLogic.buildRows(
            courses = listOf(
                course(id = 1, name = "周三的课", weekday = 3, startSection = 1),
                course(id = 2, name = "周一第5节", weekday = 1, startSection = 5),
                course(id = 3, name = "周一第1节", weekday = 1, startSection = 1),
            ),
            overlays = emptyList(),
        )
        assertEquals(listOf("周一第1节", "周一第5节", "周三的课"), rows.map { it.course.name })
    }

    @Test
    fun `已隐藏与已修改新增分开筛`() {
        val overlays = listOf(
            overlay(id = 1, kind = CourseOverlayKind.HIDE),
            overlay(id = 2, kind = CourseOverlayKind.EDIT),
            overlay(id = 3, kind = CourseOverlayKind.ADD),
        )
        assertEquals(listOf(1), CourseEditLogic.hiddenOverlays(overlays).map { it.id })
        assertEquals(listOf(2, 3), CourseEditLogic.changedOverlays(overlays).map { it.id })
    }

    @Test
    fun `覆盖层类型标签`() {
        assertEquals("已修改", CourseEditLogic.overlayLabel(CourseOverlayKind.EDIT.name))
        assertEquals("已隐藏", CourseEditLogic.overlayLabel(CourseOverlayKind.HIDE.name))
        assertEquals("已新增", CourseEditLogic.overlayLabel(CourseOverlayKind.ADD.name))
        assertEquals("未知", CourseEditLogic.overlayLabel("WHAT"))
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    @Test
    fun `编辑校验 - 通过`() {
        val form = CourseEditLogic.courseToForm(course(id = 1))
        assertNull(CourseEditLogic.validateEditForm(form))
    }

    @Test
    fun `编辑校验 - 课程名不能空`() {
        val form = CourseEditLogic.courseToForm(course(id = 1)).copy(name = " ")
        assertEquals("课程名不能为空", CourseEditLogic.validateEditForm(form))
    }

    @Test
    fun `编辑校验 - 周次格式`() {
        val form = CourseEditLogic.courseToForm(course(id = 1)).copy(weeks = "乱填")
        assertEquals("周次格式不对（示例：1-16 或 1,3,5）", CourseEditLogic.validateEditForm(form))
    }

    @Test
    fun `编辑校验 - 结束节次不能早于开始`() {
        val form = CourseEditLogic.courseToForm(course(id = 1)).copy(startSection = 5, endSection = 2)
        assertEquals("结束节次不能早于开始节次", CourseEditLogic.validateEditForm(form))
    }

    @Test
    fun `编辑校验 - 星期与开始节次现在可改，也要合法`() {
        val badWeekday = CourseEditLogic.courseToForm(course(id = 1)).copy(weekday = 0)
        assertEquals("星期需为 1~7", CourseEditLogic.validateEditForm(badWeekday))
        val badStart = CourseEditLogic.courseToForm(course(id = 1)).copy(startSection = 0, endSection = 0)
        assertEquals("开始节次需 ≥ 1", CourseEditLogic.validateEditForm(badStart))
    }

    @Test
    fun `新增校验 - 课程号必填`() {
        val form = CourseForm(name = "旁听课", weeks = "1-16", weekday = 2, startSection = 3, endSection = 4, number = "")
        assertEquals("课程号不能为空（新增的课需要一个课程号）", CourseEditLogic.validateAddForm(form))
    }

    @Test
    fun `新增校验 - 星期必须在一到七`() {
        val form = CourseForm(name = "旁听课", weeks = "1-16", weekday = 9, startSection = 3, endSection = 4, number = "X1")
        assertEquals("星期需为 1~7", CourseEditLogic.validateAddForm(form))
    }

    @Test
    fun `新增校验 - 通过`() {
        val form = CourseForm(name = "旁听课", weeks = "1-16, 18", weekday = 2, startSection = 3, endSection = 4, number = "X1")
        assertNull(CourseEditLogic.validateAddForm(form))
    }

    // ── 构造覆盖层 ────────────────────────────────────────────────────────

    @Test
    fun `编辑原课 - 锚点取自原课，显示值随表单（可改时间）`() {
        val original = course(id = 3, number = "N1", weekday = 1, startSection = 1)
        // 用户把课程号 / 星期 / 节次 / 教室 / 课名都改了（现在这些都是可改的显示值）
        val form = CourseEditLogic.courseToForm(original).copy(
            number = "HACK",
            weekday = 5,
            startSection = 6,
            classroom = "教202",
            name = "高等数学（改）",
            endSection = 4,
        )

        val result = CourseEditLogic.buildOverlayForExistingCourse(original, form, overlayId = 42, nowMillis = 100L)

        assertEquals(CourseOverlayKind.EDIT.name, result.kind)
        assertEquals(42, result.id)
        assertEquals(100L, result.updatedAtMillis)
        // 锚点 = 原课的（不随表单变）
        assertEquals("N1", result.anchorNumber)
        assertEquals(1, result.anchorWeekday)
        assertEquals(1, result.anchorStartSection)
        // 显示值 = 表单的
        assertEquals("HACK", result.number)
        assertEquals(5, result.weekday)
        assertEquals(6, result.startSection)
        assertEquals("教202", result.classroom)
        assertEquals("高等数学（改）", result.name)
        assertEquals(4, result.endSection)
        // 覆盖层的锚点键必须与原课对得上，否则合并会忽略这条修改
        assertEquals(CourseOverlayLogic.keyOf(original), CourseOverlayLogic.keyOf(result))
    }

    @Test
    fun `编辑原课 - 只改时间，锚点键仍等于原课`() {
        val original = course(id = 3, number = "N1", weekday = 1, startSection = 1)
        // 只把上课时间从「周一 1-2 节」改到「周四 7-8 节」
        val form = CourseEditLogic.courseToForm(original).copy(weekday = 4, startSection = 7, endSection = 8)

        val result = CourseEditLogic.buildOverlayForExistingCourse(original, form, overlayId = 42, nowMillis = 100L)

        // 显示值是新的时间
        assertEquals(4, result.weekday)
        assertEquals(7, result.startSection)
        assertEquals(8, result.endSection)
        // 锚点还是原课的时间 —— 键对得上，合并不会丢这条修改
        assertEquals(1, result.anchorWeekday)
        assertEquals(1, result.anchorStartSection)
        assertEquals(CourseOverlayLogic.keyOf(original), CourseOverlayLogic.keyOf(result))
    }

    @Test
    fun `编辑原课 - 改完时间后列表里仍标为已修改`() {
        val original = course(id = 3, number = "N1", weekday = 1, startSection = 1)
        // 直接照着 CourseOverlayLogic 的模型造一条"改时间"的 EDIT（模拟落库后的样子）
        val edit = CourseOverlayLogic.toOverlay(CourseOverlayKind.EDIT, original, id = 42, nowMillis = 100L)
            .copy(weekday = 4, startSection = 7, endSection = 8)
        // 页面拿到的是合并后的列表（改过时间的课显示在新时间）
        val merged = CourseOverlayLogic.merge(listOf(original), listOf(edit))

        val rows = CourseEditLogic.buildRows(merged, listOf(edit))

        assertEquals(CourseRowKind.EDITED, rows.single().kind)
        assertEquals(42, rows.single().overlayId)
    }

    @Test
    fun `编辑原课 - 无覆盖层时 id 传 0 交给 Room 自增`() {
        val original = course(id = 3)
        val form = CourseEditLogic.courseToForm(original).copy(classroom = "教202")
        val result = CourseEditLogic.buildOverlayForExistingCourse(original, form, overlayId = 0, nowMillis = 1L)
        assertEquals(0, result.id)
    }

    @Test
    fun `新增课 - 锚点就是它自己且可改`() {
        val form = CourseForm(
            name = "旁听课",
            weeks = "1-16",
            weekday = 2,
            startSection = 3,
            endSection = 4,
            number = "X1",
            classroom = "教303",
        )
        val result = CourseEditLogic.buildOverlayForAddedCourse(term, form, overlayId = 0, nowMillis = 5L)

        assertEquals(CourseOverlayKind.ADD.name, result.kind)
        assertEquals(0, result.id)
        assertEquals(term, result.term)
        // 新增的课没有原课，锚点 = 显示值
        assertEquals("X1", result.anchorNumber)
        assertEquals(2, result.anchorWeekday)
        assertEquals(3, result.anchorStartSection)
        assertEquals("X1", result.number)
        assertEquals(2, result.weekday)
        assertEquals(3, result.startSection)
        assertEquals("[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16]", result.weeks)
    }

    @Test
    fun `编辑新增课 - 用那节课当锚点，时间全按表单`() {
        // 合并结果里自己新增的课：id 为负（= -overlayId）
        val added = course(id = -8, number = "X1", weekday = 2, startSection = 3, endSection = 4)
        val form = CourseEditLogic.courseToForm(added).copy(weekday = 5, startSection = 1, endSection = 2)

        val result = CourseEditLogic.buildOverlayForAddedCourse(added, form, overlayId = 8, nowMillis = 9L)

        assertEquals(CourseOverlayKind.ADD.name, result.kind)
        assertEquals(8, result.id)
        // 显示值按表单（改后的时间）
        assertEquals(5, result.weekday)
        assertEquals(1, result.startSection)
        assertEquals(2, result.endSection)
        // 锚点取自那节课（它就是自己）
        assertEquals("X1", result.anchorNumber)
        assertEquals(2, result.anchorWeekday)
        assertEquals(3, result.anchorStartSection)
    }

    @Test
    fun `新增课 - 再编辑时沿用原覆盖层 id`() {
        val form = CourseForm(name = "旁听课", weeks = "1-16", number = "X1", classroom = "教303")
        val result = CourseEditLogic.buildOverlayForAddedCourse(term, form, overlayId = 8, nowMillis = 5L)
        assertEquals(8, result.id)
    }

    @Test
    fun `隐藏原课 - kind 为 HIDE 且键与原课一致`() {
        val original = course(id = 3, number = "N1", weekday = 1, startSection = 1)
        val result = CourseEditLogic.buildHideOverlay(original, nowMillis = 7L)
        assertEquals(CourseOverlayKind.HIDE.name, result.kind)
        assertEquals(0, result.id)
        assertEquals(CourseOverlayLogic.keyOf(original), CourseOverlayLogic.keyOf(result))
    }

    @Test
    fun `表单初值 - 周次给可读写法`() {
        val form = CourseEditLogic.courseToForm(course(id = 1, weeks = "[1][2][3][4][5][6][7][8]"))
        assertEquals("1-8", form.weeks)
        assertTrue(CourseEditLogic.parseWeeks(form.weeks) != null)
    }
}
