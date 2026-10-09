package cn.bit101.android.features.schedule.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.data.database.entity.CustomScheduleEntity
import cn.bit101.android.data.school.TeachingAdjustmentEntry
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.component.schedule.AddEditScheduleDialog
import cn.bit101.android.features.common.component.schedule.CustomScheduleDetailDialog
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.map.CampusPlaces
import cn.bit101.android.features.map.MapTargetHolder
import java.time.LocalDateTime

/**
 * 教室 / 考场字符串 → 跳到地图并定位到那栋楼。
 *
 * ⚠️ 匹配不到时**明说「暂未收录」**，而不是退回到「跳到校区中心」糊弄过去 ——
 * 地点表只覆盖良乡与中关村（珠海 / 嘉兴在 OSM 上没有可信的建筑坐标），
 * 指错楼比不指更糟。
 */
private fun locateOnMap(mainController: MainController, raw: String) {
    val place = CampusPlaces.match(raw)
    if (place == null) {
        val name = raw.trim().let { if (it.length > 12) it.take(12) + "…" else it }
        mainController.snackbar(if (name.isEmpty()) "这个地点没有写教室" else "暂未收录「$name」的位置")
        return
    }
    // 坐标先放进中转（底栏页跳转只带 route，传不了参数），再请求切到「图」页
    MapTargetHolder.request(MapTargetHolder.from(place))
    GotoRequest.request(CampusPlaces.route)
}

/**
 * @author flwfdd
 * @date 2023/4/12 14:29
 * @description 课程表主页面
 * _(:з」∠)_
 */

@Composable
internal fun CourseSchedule(
    mainController: MainController,
    active: Boolean,
    vm: CourseScheduleViewModel = hiltViewModel()
) {
    /**
     * 当前选择的学期
     */
    val term by vm.currentTermFlow.collectAsState(initial = null)

    /**
     * 日程数据（7 列，**已套过「教学安排调整」**）
     */
    val columns by vm.columns.collectAsState()

    /** 教学安排调整（放假 / 调休 / 补课）；`null` = 还没取到 */
    val adjustments by vm.adjustments.collectAsState()

    /**
     * 当前周
     */
    val week by vm.weekFlow.collectAsState()

    /**
     * 学期开始日期
     */
    val firstDay by vm.firstDayFlow.collectAsState(initial = null)

    /**
     * 课表相关配置
     */
    val showDivider by vm.showDividerFlow.collectAsState(initial = null)
    val showSaturday by vm.showSaturdayFlow.collectAsState(initial = null)
    val showSunday by vm.showSundayFlow.collectAsState(initial = null)
    val showHighlightToday by vm.showHighlightTodayFlow.collectAsState(initial = null)
    val showBorder by vm.showBorderFlow.collectAsState(initial = null)

    /**
     * 时间表字符串
     */
    val timeTable by vm.timeTableStringFlow.collectAsState(initial = null)

    /**
     * 显示当前时间线
     */
    val currentTime by vm.showCurrentTimeFlow.collectAsState(initial = null)

    /**
     * 显示考试信息
     */
    val showExamInfo by vm.showExamInfoFlow.collectAsState(initial = null)

    val showCourseDetailState by vm.showCourseDetail.collectAsState()

    val showExamDetailState by vm.showExamDetail.collectAsState()

    /**
     * 考试安排列表用的数据（**不受「课表里显示考试」开关影响**）与展开状态。
     */
    val allExams by vm.allExams.collectAsState()
    val showExamList by vm.showExamList.collectAsState()
    val refreshExamsState by vm.refreshExamsStateLiveData.observeAsState()

    val showCustomScheduleState by vm.showCustomScheduleDetail.collectAsState()

    var showAddScheduleDialog by remember { mutableStateOf(false) }

    var nowEditCustomSchedule by remember { mutableStateOf<CustomScheduleEntity?>(null) }

    val refreshCoursesState by vm.refreshCoursesStateLiveData.observeAsState()

    val forceRefreshCoursesState by vm.forceRefreshCoursesStateLiveData.observeAsState()

    val addCustomScheduleState by vm.addEditCustomScheduleStateLiveData.observeAsState()

    val deleteCustomScheduleState by vm.deleteCustomScheduleStateLiveData.observeAsState()

    val addScheduleToSysCalendarState by vm.addScheduleToSysCalendarStateLiveData.observeAsState()

    val context = LocalContext.current

    // 强制刷新的状态在这里管理
    DisposableEffect(forceRefreshCoursesState) {
        if(forceRefreshCoursesState == SimpleState.Success) {
            mainController.snackbar("刷新成功OvO")
        } else if(forceRefreshCoursesState == SimpleState.Fail) {
            mainController.snackbar("刷新失败Orz")
        }
        onDispose {}
    }

    LaunchedEffect(addCustomScheduleState) {
        if(addCustomScheduleState == SimpleState.Success) {
            if(nowEditCustomSchedule == null) {
                mainController.snackbar("添加成功OvO")
            } else {
                mainController.snackbar("修改成功OvO")
            }

            showAddScheduleDialog = false
            vm.clearCustomScheduleDetail()
        } else if(addCustomScheduleState == SimpleState.Fail) {
            if(nowEditCustomSchedule == null) {
                mainController.snackbar("添加失败Orz")
            } else {
                mainController.snackbar("修改失败Orz")
            }
        }

        vm.addEditCustomScheduleStateLiveData.value = null  // 不清空的话, 连续操作时很可能只有第一次有提示 (因为速度很快, 状态更新不过来)
    }

    LaunchedEffect(deleteCustomScheduleState) {
        if(deleteCustomScheduleState == SimpleState.Success) {
            mainController.snackbar("删除成功OvO")
            vm._showCustomScheduleDetail.value = null
        } else if(deleteCustomScheduleState == SimpleState.Fail) {
            mainController.snackbar("删除失败Orz")
        }
    }

    LaunchedEffect(addScheduleToSysCalendarState) {
        if(addScheduleToSysCalendarState == SimpleState.Fail) {
            mainController.snackbar("添加失败Orz")
        }
        vm.addScheduleToSysCalendarStateLiveData.value = null
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.forceRefreshCoursesStateLiveData.value = null
            vm.refreshCoursesStateLiveData.value = null
            vm.addEditCustomScheduleStateLiveData.value = null
            vm.deleteCustomScheduleStateLiveData.value = null
            vm.addScheduleToSysCalendarStateLiveData.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if(
            term == null ||
            showDivider == null ||
            showSaturday == null ||
            showSunday == null ||
            showHighlightToday == null ||
            showBorder == null ||
            currentTime == null ||
            timeTable == null
        ) { }
        else if(
            term!!.isEmpty() ||
            week == Int.MAX_VALUE ||
            firstDay == null
        ) {
            // datastore数据加载完了，但是数据库中没有数据，应该显示一个按钮，点击后获取
            // 这里如果没有学期数据、课程数据，就显示一个按钮，点击后获取
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    enabled = refreshCoursesState != SimpleState.Loading && forceRefreshCoursesState != SimpleState.Loading,
                    onClick = vm::forceRefreshCourses
                ) {
                    if (refreshCoursesState == SimpleState.Loading || forceRefreshCoursesState == SimpleState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else Text("获取课程表")
                }
            }
        }
        else {
            val settingData = SettingData(
                showDivider = showDivider!!,
                showSaturday = showSaturday!!,
                showSunday = showSunday!!,
                showHighlightToday = showHighlightToday!!,
                showBorder = showBorder!!,
                showCurrentTime = currentTime!!,
                showExamInfo = showExamInfo!!
            )

            // 数据加载完毕
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 教学安排调整（放假 / 调休）：只在**本周确实受影响**时出现
                AdjustmentBanner(
                    entries = adjustments?.entries.orEmpty(),
                    columns = columns,
                    onOpenSource = { url -> mainController.openWebPage(url) },
                )

                // 日程表
                CourseScheduleCalendar(
                    columns = columns,
                    week = week,
                    timeTable = timeTable!!,
                    settingData = settingData,

                    onConfig = { mainController.navigate(NavDest.Setting("calendar")) },
                    onChangeWeek = { vm.changeWeek(it) },
                    onAddSchedule = {
                        nowEditCustomSchedule = null
                        showAddScheduleDialog = true
                    },
                    onShowExams = vm::showExamList,
                )
                if(showCourseDetailState != null) {
                    // 课程详情对话框
                    CourseScheduleDetailDialog(
                        course = showCourseDetailState!!,
                        onDismiss = vm::clearShowCourseDetail,
                        onLocate = { locateOnMap(mainController, it) },
                    )
                }
                if(showExamList) {
                    // 考试安排列表。⚠️ 刻意声明在详情对话框**之前**：Compose 里后声明的
                    // Dialog 盖在上层，于是从列表点进详情时详情在最上面、关掉详情回到列表
                    ExamListDialog(
                        exams = allExams,
                        // 用「打开这一刻」的时间算倒计时，重组时不跳字
                        now = remember(showExamList) { LocalDateTime.now() },
                        refreshState = refreshExamsState,
                        onRefresh = vm::refreshExams,
                        onExamClick = vm::openExamDetail,
                        onDismiss = vm::clearExamList,
                    )
                }
                if(showExamDetailState != null) {
                    // 考试详情对话框
                    ExamScheduleDetailDialog(
                        exam = showExamDetailState!!,
                        onDismiss = vm::clearShowExamDetail,
                        onAddToCalendar = { vm.addScheduleToSysCalendar(context, it) },
                        onLocate = { locateOnMap(mainController, it) },
                    )
                }
                if(showCustomScheduleState != null) {
                    // 自定义日程详情对话框
                    CustomScheduleDetailDialog(
                        schedule = showCustomScheduleState!!,
                        onDismiss = vm::clearCustomScheduleDetail,
                        onEdit = {
                            nowEditCustomSchedule = showCustomScheduleState!!
                            showAddScheduleDialog = true
                        },
                        onDelete = vm::deleteCustomSchedule,
                        onAddToCalendar = { vm.addScheduleToSysCalendar(context, it) }
                    )
                }
                if(showAddScheduleDialog) {
                    // 添加 / 修改自定义日程对话框
                    AddEditScheduleDialog(
                        schedule = nowEditCustomSchedule,
                        onAddEditSchedule = {
                            if (nowEditCustomSchedule == null) {
                                vm.addCustomSchedule(it)
                            } else {
                                vm.updateCustomSchedule(nowEditCustomSchedule!!, it)
                            }
                        },
                        onDismiss = { showAddScheduleDialog = false },
                    )
                }
            }
        }
    }
}

/**
 * 「教学安排调整」提示条（放假 / 调休 / 补课）。
 *
 * ## 为什么只在「当周受影响」时出现
 * 调休是**最容易记错、记错就缺课**的事，所以那几天必须显眼；但一学期只有 2~4 次，
 * 平时常驻一行反而变成噪音。所以：本周没有覆盖 → **完全不占位置**。
 *
 * 文案里的日期与说明都来自 [CourseScheduleAdjustmentLogic]，
 * 「原文」按钮直接把学校通知原文交给调用方去打开 —— 用户能自己核对，
 * 不用只信我们的结论。
 */
@Composable
private fun AdjustmentBanner(
    entries: List<TeachingAdjustmentEntry>,
    columns: List<DayColumn>,
    onOpenSource: (String) -> Unit,
) {
    val affected = columns.mapNotNull { c -> c.plan?.let { c.date to it } }
    if (affected.isEmpty()) return

    // ⚠️ 走 summarize 而不是逐日 describe：连续放假要合并成一段
    //    （「10/5~10/8 放假，当天的课不上」，而不是同一句话重复四遍）
    val text = CourseScheduleAdjustmentLogic.summarize(affected).joinToString("；")
    val sourceUrl = affected.firstNotNullOfOrNull { (date, _) ->
        entries.firstOrNull { it.date == date }?.sourceUrl
    }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Icon(
                Icons.Default.EventNote,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (sourceUrl != null) {
                TextButton(onClick = { onOpenSource(sourceUrl) }) { Text("原文") }
            }
        }
    }
}
