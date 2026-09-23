package cn.bit101.android.data.repo.base

import cn.bit101.android.data.database.entity.CourseOverlayEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「手动修改课程表」的写入入口。
 *
 * ## 与 [CoursesRepo] 的分工
 *
 * | 用途 | 用哪个 |
 * |---|---|
 * | **显示**课表（App 表格、桌面组件） | [CoursesRepo.getCoursesFromLocal] —— 它已经把覆盖层合并进去了 |
 * | **改**课表（编辑 / 隐藏 / 新增 / 撤销） | 这里 —— 只动覆盖层，**绝不碰 `course_schedule`** |
 *
 * ⚠️ 为什么显示不用单独提供"带覆盖"的接口：合并发生在 `CoursesRepo` 的读路径里，
 * 调用方（含桌面组件）不用知道覆盖层的存在，也就不会有人漏掉合并。
 *
 * ⚠️ 写路径**不要**去改 `course_schedule`：那儿的行下次同步会被全量覆盖冲掉
 * （见 [CourseOverlayEntity] 的说明）。
 */
interface CourseOverlayRepo {

    /** 全部覆盖层（最近写入的在前）。 */
    fun getOverlays(): Flow<List<CourseOverlayEntity>>

    /** 某学期的覆盖层（最近写入的在前）。 */
    fun getOverlays(term: String): Flow<List<CourseOverlayEntity>>

    /**
     * 写入一条覆盖（"不存在则插入、存在则更新"）。
     *
     * 同一个「类型 + **锚点**（学期 / anchorNumber / anchorWeekday / anchorStartSection）」
     * 只有一条：例如用户把教室从 A 改到 B 再改到 C，最终只应有一条 EDIT，而不是三条叠加。
     *
     * ⚠️ 锚点 = "改的是哪一节**原课**"，与显示值（`number/weekday/startSection` 等）分开 ——
     * 这样改上课时间时锚点不动、匹配照样成立（见 `CourseOverlayEntity` 的说明）。
     *
     * ⚠️ 特殊规则：原本是 `ADD` 的课被再编辑时，实现里会**保持 ADD**
     * （它没有对应的教务原课，变成 EDIT 会在合并时找不到基准而从课表上消失）。
     */
    suspend fun upsert(overlay: CourseOverlayEntity)

    /** 删掉一条覆盖（= 恢复原样 / 撤销新增）。 */
    suspend fun delete(id: Int)
}
