package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.repo.base.CourseOverlayRepo
import cn.bit101.android.data.repo.base.CoursesRepo
import cn.bit101.android.features.setting.utils.CourseEditLogic
import cn.bit101.android.features.setting.utils.CourseForm
import cn.bit101.android.features.setting.utils.CourseRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「手动修改课程表」页的状态。 */
internal data class CourseEditUiState(
    val term: String = "",
    val rows: List<CourseRow> = emptyList(),
    /** 已隐藏的课（覆盖层里的 HIDE）。 */
    val hidden: List<CourseOverlayEntity> = emptyList(),
    /** 已修改 / 已新增的课（覆盖层里的 EDIT + ADD）。 */
    val changed: List<CourseOverlayEntity> = emptyList(),
)

/**
 * 「手动修改课程表」页的 ViewModel —— **写路径只走 [CourseOverlayRepo]**。
 *
 * 读路径直接用 [CoursesRepo.getCoursesFromLocal]（它已经把覆盖层合并好了），
 * 另外单独订阅 [CourseOverlayRepo.getOverlays] 拿"被隐藏的课"——那些课合并后就不在列表里了，
 * 只能从覆盖层本身渲染出来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class CourseEditViewModel @Inject constructor(
    private val coursesRepo: CoursesRepo,
    private val courseOverlayRepo: CourseOverlayRepo,
    courseScheduleSettings: CourseScheduleSettings,
) : ViewModel() {

    val uiState: StateFlow<CourseEditUiState> = courseScheduleSettings.term.flow
        .flatMapLatest { term ->
            if (term.isBlank()) {
                flowOf(CourseEditUiState(term = term))
            } else {
                combine(
                    coursesRepo.getCoursesFromLocal(term),
                    courseOverlayRepo.getOverlays(term),
                ) { courses, overlays ->
                    CourseEditUiState(
                        term = term,
                        rows = CourseEditLogic.buildRows(courses, overlays),
                        hidden = CourseEditLogic.hiddenOverlays(overlays),
                        changed = CourseEditLogic.changedOverlays(overlays),
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CourseEditUiState(),
        )

    /**
     * 保存一次编辑。
     *
     * @param course 列表里那一行（`id < 0` 说明是自己新增的课 → 走 ADD 分支）
     * @param form 对话框填好的表单
     * @param overlayId 已有覆盖层的 id（教务原课传 0，让 Room 自增）
     */
    fun saveEdit(course: CourseScheduleEntity, form: CourseForm, overlayId: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val overlay = if (course.id < 0) {
                // 自己新增的课：锚点就是它自己，显示值全按表单
                CourseEditLogic.buildOverlayForAddedCourse(
                    source = course,
                    form = form,
                    overlayId = overlayId,
                    nowMillis = now,
                )
            } else {
                // 教务原课：锚点取自原课，表单值只改显示值（所以能改上课时间）
                CourseEditLogic.buildOverlayForExistingCourse(course, form, overlayId, now)
            }
            courseOverlayRepo.upsert(overlay)
        }
    }

    /** 右下角 FAB：新增一门课（`id = 0`，交给 Room 自增）。 */
    fun addCourse(form: CourseForm) {
        viewModelScope.launch {
            courseOverlayRepo.upsert(
                CourseEditLogic.buildOverlayForAddedCourse(
                    term = uiState.value.term,
                    form = form,
                    overlayId = 0,
                    nowMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 隐藏一节教务原课。 */
    fun hideCourse(course: CourseScheduleEntity) {
        viewModelScope.launch {
            courseOverlayRepo.upsert(
                CourseEditLogic.buildHideOverlay(course, System.currentTimeMillis())
            )
        }
    }

    /** 恢复原样 / 撤销新增 / 取消隐藏 —— 都是删掉那条覆盖。 */
    fun restore(overlayId: Int) {
        viewModelScope.launch { courseOverlayRepo.delete(overlayId) }
    }
}
