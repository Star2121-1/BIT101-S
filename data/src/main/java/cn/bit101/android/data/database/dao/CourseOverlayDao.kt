package cn.bit101.android.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「手动修改课程表」覆盖层的读写。
 *
 * ⚠️ 这张表只被 [`CourseOverlayRepo`] 与 `CoursesRepo` 的读路径使用 ——
 * 前者写、后者读时合并。别在别处直接动它。
 */
@Dao
internal interface CourseOverlayDao {

    /** 全部覆盖层（最近写入的在前）。 */
    @Query("SELECT * FROM course_overlay ORDER BY updatedAtMillis DESC")
    fun getAll(): Flow<List<CourseOverlayEntity>>

    /** 某学期的覆盖层（最近写入的在前）。 */
    @Query("SELECT * FROM course_overlay WHERE term = :term ORDER BY updatedAtMillis DESC")
    fun getByTerm(term: String): Flow<List<CourseOverlayEntity>>

    @Query("SELECT * FROM course_overlay WHERE id = :id")
    suspend fun findById(id: Int): CourseOverlayEntity?

    /**
     * 按「类型 + **锚点**」找已有覆盖。
     *
     * ⚠️ 锚点（`anchor*`）= 改的是哪一节**原课**；显示值（`number/weekday/startSection`）
     * 是"改完之后长什么样"，两者故意分开 —— 这样用户改上课时间时，
     * 锚点不动、匹配照样成立。自然键定义见实体注释。
     */
    @Query(
        """
        SELECT * FROM course_overlay
        WHERE term = :term AND anchorNumber = :anchorNumber AND anchorWeekday = :anchorWeekday
          AND anchorStartSection = :anchorStartSection AND kind = :kind
        LIMIT 1
        """
    )
    suspend fun find(
        term: String,
        anchorNumber: String,
        anchorWeekday: Int,
        anchorStartSection: Int,
        kind: String,
    ): CourseOverlayEntity?

    @Insert
    suspend fun insert(overlay: CourseOverlayEntity): Long

    @Update
    suspend fun update(overlay: CourseOverlayEntity)

    @Query("DELETE FROM course_overlay WHERE id = :id")
    suspend fun deleteById(id: Int)
}
