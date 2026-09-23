package cn.bit101.android.data.repo

import cn.bit101.android.data.database.BIT101Database
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseOverlayKind
import cn.bit101.android.data.repo.base.CourseOverlayRepo
import cn.bit101.android.data.schedule.CourseOverlayLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CourseOverlayRepo] 的实现：只动 `course_overlay` 表。
 *
 * ⚠️ 这里**绝不碰 `course_schedule`** —— 那是教务的地盘，同步时会被全量覆盖。
 * 用户看到的效果由 `DefaultCoursesRepo` 在**读路径**上合并出来
 * （见 `CourseOverlayLogic.merge`）。
 */
@Singleton
internal class DefaultCourseOverlayRepo @Inject constructor(
    private val database: BIT101Database,
) : CourseOverlayRepo {

    private val dao get() = database.courseOverlayDao()

    override fun getOverlays(): Flow<List<CourseOverlayEntity>> = dao.getAll()

    override fun getOverlays(term: String): Flow<List<CourseOverlayEntity>> = dao.getByTerm(term)

    override suspend fun upsert(overlay: CourseOverlayEntity) = withContext(Dispatchers.IO) {
        val existing = if (overlay.id != 0) {
            // 带了 id 就是「改这条已有的」
            dao.findById(overlay.id)
        } else {
            // 没带 id：按「类型 + 锚点」找，找到就更新（同一节课只留一条修改）
            dao.find(
                term = overlay.term,
                anchorNumber = overlay.anchorNumber,
                anchorWeekday = overlay.anchorWeekday,
                anchorStartSection = overlay.anchorStartSection,
                kind = overlay.kind,
            )
        }

        if (existing == null) {
            // 新建：ADD 的锚点要跟显示值同步（见 normalizeAnchors）
            dao.insert(CourseOverlayLogic.normalizeAnchors(overlay, existing = null).copy(id = 0))
            return@withContext
        }

        // ⚠️ 原本是「本地新增」的课被**再编辑**时，保持 ADD 不变：它没有对应的教务原课，
        //    一旦变成 EDIT，合并时就找不到基准，这节新加的课会直接从课表上消失。
        val kind = if (existing.kind == CourseOverlayKind.ADD.name) {
            CourseOverlayKind.ADD.name
        } else {
            overlay.kind
        }

        // ⚠️ 更新已有覆盖层时**锚点一律沿用 existing 的**（见 normalizeAnchors）：
        //    UI 手上只有"合并后的行"，二次编辑时拿它取锚点会让锚点漂移 → 修改静默失效。
        val normalized = CourseOverlayLogic.normalizeAnchors(
            incoming = overlay.copy(kind = kind),
            existing = existing,
        )

        dao.update(normalized.copy(id = existing.id))
    }

    override suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }
}
