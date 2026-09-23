package cn.bit101.android.features.setting.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.setting.utils.CourseEditLogic
import cn.bit101.android.features.setting.utils.CourseForm
import cn.bit101.android.features.setting.utils.CourseRow
import cn.bit101.android.features.setting.utils.CourseRowKind
import cn.bit101.android.features.setting.viewmodel.CourseEditViewModel

/**
 * 「手动修改课程表」页。
 *
 * ## 为什么单独一页、而不是直接长按课表改
 *
 * 教务拉下来的课表不能直接改（同步会"删光再插"，改必被冲掉），用户的修改走**覆盖层**。
 * 这一页就是覆盖层的人机界面：
 * - 上半：当前学期的课表（已合并），每行可**编辑 / 隐藏**，并标出「已修改 / 本地新增」
 * - 下半：**已隐藏的课** 与 **已修改 / 新增的课**，每条可恢复原样
 * - 右下 FAB：补一门教务漏掉的课
 *
 * 写入一律走 `CourseOverlayRepo`，这一页不碰 `course_schedule`。
 */
@Composable
internal fun CourseEditPage(mainController: MainController) {
    val vm: CourseEditViewModel = hiltViewModel()
    val state by vm.uiState.collectAsState()

    // 正在编辑的那一行；null = 没开编辑框
    var editing by remember { mutableStateOf<CourseRow?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader(
                    title = "当前学期课表",
                    subTitle = if (state.term.isBlank()) "还没拿到当前学期" else
                        "学期 ${state.term} · 共 ${state.rows.size} 节，可编辑 / 隐藏",
                )
            }

            if (state.rows.isEmpty()) {
                item { HintCard("这里还没有课。下拉课表页同步一次，或用右下角 + 补一门。") }
            }

            items(state.rows) { row ->
                CourseRowCard(
                    row = row,
                    onEdit = { editing = row },
                    onHide = { vm.hideCourse(row.course) },
                    onDelete = { row.overlayId?.let(vm::restore) },
                )
            }

            if (state.hidden.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "已隐藏的课",
                        subTitle = "这些课不在上面的课表里（比如已经退了），点「恢复」让它回来",
                    )
                }
                items(state.hidden) { overlay ->
                    OverlayRowCard(
                        overlay = overlay,
                        label = CourseEditLogic.overlayLabel(overlay.kind),
                        actionText = "恢复",
                        onAction = { vm.restore(overlay.id) },
                    )
                }
            }

            if (state.changed.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "已修改 / 新增的课",
                        subTitle = "「恢复原样」会撤销这处手动修改，回到教务给的样子",
                    )
                }
                items(state.changed) { overlay ->
                    OverlayRowCard(
                        overlay = overlay,
                        label = CourseEditLogic.overlayLabel(overlay.kind),
                        actionText = if (overlay.kind == "ADD") "删除" else "恢复原样",
                        onAction = { vm.restore(overlay.id) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "添加课程")
        }
    }

    editing?.let { row ->
        val isAdded = row.kind == CourseRowKind.LOCAL_ADD
        CourseFormDialog(
            title = if (isAdded) "编辑新增课程" else "编辑课程",
            initial = CourseEditLogic.courseToForm(row.course),
            isAdd = isAdded,
            onConfirm = { form ->
                vm.saveEdit(row.course, form, row.overlayId ?: 0)
                editing = null
            },
            onDismiss = { editing = null },
            onError = mainController::snackbar,
        )
    }

    if (adding) {
        CourseFormDialog(
            title = "添加课程",
            initial = CourseForm(),
            isAdd = true,
            onConfirm = { form ->
                vm.addCourse(form)
                adding = false
            },
            onDismiss = { adding = false },
            onError = mainController::snackbar,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subTitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = subTitle,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            ),
        )
    }
}

@Composable
private fun HintCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            ),
        )
    }
}

/** 课表里的一行：课程内容 + 状态 + 「编辑 / 隐藏（或删除）」。 */
@Composable
private fun CourseRowCard(
    row: CourseRow,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.course.name.ifBlank { "（未命名）" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                RowStatusTag(row.kind)
            }

            CourseMetaLines(
                weekday = row.course.weekday,
                startSection = row.course.start_section,
                endSection = row.course.end_section,
                weeks = row.course.weeks,
                classroom = row.course.classroom,
                teacher = row.course.teacher,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (row.kind == CourseRowKind.LOCAL_ADD) {
                    TextButton(onClick = onDelete) { Text("删除") }
                } else {
                    TextButton(onClick = onHide) { Text("隐藏") }
                }
            }
        }
    }
}

/** 覆盖层的一行（已隐藏 / 已修改 / 已新增），只有一个「恢复 / 删除」动作。 */
@Composable
private fun OverlayRowCard(
    overlay: CourseOverlayEntity,
    label: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = overlay.name.ifBlank { "（未命名）" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            CourseMetaLines(
                weekday = overlay.weekday,
                startSection = overlay.startSection,
                endSection = overlay.endSection,
                weeks = overlay.weeks,
                classroom = overlay.classroom,
                teacher = overlay.teacher,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

@Composable
private fun CourseMetaLines(
    weekday: Int,
    startSection: Int,
    endSection: Int,
    weeks: String,
    classroom: String,
    teacher: String,
) {
    Text(
        text = "${classroom.ifBlank { "教室未知" }} · ${teacher.ifBlank { "教师未知" }}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "${CourseEditLogic.weekdayText(weekday)} " +
                "第 $startSection-${endSection} 节 · 周次 ${CourseEditLogic.displayWeeks(weeks).ifBlank { "—" }}",
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        ),
    )
}

@Composable
private fun RowStatusTag(kind: CourseRowKind) {
    val text = when (kind) {
        CourseRowKind.EDITED -> "已修改"
        CourseRowKind.LOCAL_ADD -> "本地新增"
        CourseRowKind.ORIGINAL -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
    )
}

/**
 * 编辑 / 新增表单。
 *
 * 所有字段都**可改** ——「改上课时间 / 课程号」改的是覆盖层的**显示值**，
 * 锚点（指向哪一节原课）在 `CourseEditLogic` 里另行取值、不受表单影响，所以合并照样能匹配上。
 */
@Composable
private fun CourseFormDialog(
    title: String,
    initial: CourseForm,
    isAdd: Boolean,
    onConfirm: (CourseForm) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var teacher by remember(initial) { mutableStateOf(initial.teacher) }
    var classroom by remember(initial) { mutableStateOf(initial.classroom) }
    var weeks by remember(initial) { mutableStateOf(initial.weeks) }
    var weekday by remember(initial) { mutableStateOf(initial.weekday.toString()) }
    var startSection by remember(initial) { mutableStateOf(initial.startSection.toString()) }
    var endSection by remember(initial) { mutableStateOf(initial.endSection.toString()) }
    var number by remember(initial) { mutableStateOf(initial.number) }
    var campus by remember(initial) { mutableStateOf(initial.campus) }
    var credit by remember(initial) { mutableStateOf(initial.credit.toString()) }
    var hour by remember(initial) { mutableStateOf(initial.hour.toString()) }
    var type by remember(initial) { mutableStateOf(initial.type) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var department by remember(initial) { mutableStateOf(initial.department) }
    var description by remember(initial) { mutableStateOf(initial.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = classroom,
                    onValueChange = { classroom = it },
                    label = { Text("教室") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = weeks,
                    onValueChange = { weeks = it },
                    label = { Text("周次 *") },
                    supportingText = { Text("示例：1-16 或 1,3,5-8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(if (isAdd) "课程号 *" else "课程号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = weekday,
                        onValueChange = { weekday = it },
                        label = { Text("星期") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = startSection,
                        onValueChange = { startSection = it },
                        label = { Text("开始节次") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endSection,
                        onValueChange = { endSection = it },
                        label = { Text("结束节次") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = campus,
                    onValueChange = { campus = it },
                    label = { Text("校区") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = credit,
                        onValueChange = { credit = it },
                        label = { Text("学分") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it },
                        label = { Text("学时") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text("课程性质") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("课程类别") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = department,
                    onValueChange = { department = it },
                    label = { Text("开课单位") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("时空描述") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val form = CourseForm(
                        name = name,
                        teacher = teacher,
                        classroom = classroom,
                        weeks = weeks,
                        weekday = weekday.toIntOrNull() ?: 0,
                        startSection = startSection.toIntOrNull() ?: 0,
                        endSection = endSection.toIntOrNull() ?: 0,
                        campus = campus,
                        credit = credit.toIntOrNull() ?: 0,
                        hour = hour.toIntOrNull() ?: 0,
                        type = type,
                        category = category,
                        department = department,
                        description = description,
                        number = number,
                    )
                    val error = if (isAdd) {
                        CourseEditLogic.validateAddForm(form)
                    } else {
                        CourseEditLogic.validateEditForm(form)
                    }
                    if (error != null) onError(error) else onConfirm(form)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
