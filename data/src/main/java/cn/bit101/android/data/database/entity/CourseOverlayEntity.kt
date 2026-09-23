package cn.bit101.android.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 手动修改课程表的**覆盖层**。
 *
 * ## 为什么单独一张表（而不是直接改 `course_schedule`）
 *
 * ⚠️ `CoursesRepo.saveCourses()` 是**全量覆盖**（先 `deleteAllCourses()` 再插入）——
 * 手动改在原始表里的东西，下一次课表同步就会被冲得干干净净。
 * 所以原始表保持"教务说什么就是什么"，用户的修改记在这里，
 * **读取时合并**（见 `CourseOverlayLogic.merge`）。
 *
 * ## ⚠️⚠️ 「锚点」与「显示值」是两套字段
 *
 * 覆盖层要同时回答两个问题：
 * 1. 我改的是**哪一节原课**？ → **锚点** [anchorNumber] / [anchorWeekday] / [anchorStartSection]
 * 2. 改完之后**长什么样**？ → 显示值 [number] / [weekday] / [startSection] 等
 *
 * 一开始我把这两者合成了一套字段，结果**「改上课时间」根本表达不出来**：
 * 自然键里含 `weekday/startSection`，改完时间键就变了 → 合并时匹配不上原课、
 * 修改被静默忽略（这个坑是同事在写 UI 时发现的）。
 * 现在锚点与显示值分开，改时间只动显示值，匹配照样成立。
 *
 * 锚点也**不能用 `course_schedule.id`**：同步是"删光再插"，行 id 每次都变，
 * 用 id 当键的话改完第一次同步就失联。
 *
 * ## 与 [CourseScheduleEntity] 的字段对应
 *
 * `kind = EDIT / HIDE` 时显示值是**覆盖后的值**；`kind = ADD` 时是一节**完整的新课**
 * （锚点就是它自己）。
 */
@Entity(
    tableName = "course_overlay",
    indices = [Index(value = ["term", "anchorNumber", "anchorWeekday", "anchorStartSection"])],
)
data class CourseOverlayEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int,

    /** 修改类型，取值见 [CourseOverlayKind]。 */
    val kind: String,

    /** 学期（与 `course_schedule.term` 对应）。 */
    val term: String,

    // ── 锚点：指向"改的是哪一节原课"（`ADD` 时指向它自己） ──

    /** 原课的课程号。 */
    val anchorNumber: String,

    /** 原课的星期几（1..7）。 */
    val anchorWeekday: Int,

    /** 原课的开始节次。 */
    val anchorStartSection: Int,

    // ── 显示值：用户看到的课程内容（改时间/课程号就改这里） ──

    val number: String,
    val name: String,
    val teacher: String,
    val classroom: String,
    val description: String,
    /** 上课周次，形如 `[1][2][3]`。 */
    val weeks: String,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val campus: String,
    val credit: Int,
    val hour: Int,
    val type: String,
    val category: String,
    val department: String,

    /** 写入时刻，用于"最近改的排前面"与排查。 */
    val updatedAtMillis: Long,
)

/**
 * 覆盖层的三种意图。
 *
 * ⚠️ 存库时用 [name] 字符串（Room 存不了枚举），改名字等于改数据格式 —— 别改。
 */
enum class CourseOverlayKind {
    /** 改这节课（教室 / 教师 / 时间 / 周次…按显示值来）。 */
    EDIT,

    /** 隐藏这节课（比如这门课我已经退了，但教务还没消）。 */
    HIDE,

    /** 新增一节课（教务那边漏了，或我旁听）。 */
    ADD,
    ;

    companion object {
        /** 解析；认不出返回 null（数据格式变更时不至于崩）。 */
        fun parseOrNull(raw: String?): CourseOverlayKind? =
            entries.firstOrNull { it.name == raw }
    }
}
