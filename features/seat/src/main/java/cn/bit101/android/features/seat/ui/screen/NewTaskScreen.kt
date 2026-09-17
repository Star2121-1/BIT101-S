package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.ui.component.CascadingDropdown
import cn.bit101.android.features.seat.ui.component.ErrorCard
import cn.bit101.android.features.seat.ui.component.ModeSelector
import cn.bit101.android.features.seat.ui.component.rememberNotificationPermissionState
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun NewTaskScreen(
    mainController: MainController,
    viewModel: SeatViewModel,
    onTaskCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val treeNodes by viewModel.seatTree.collectAsState()
    val seatTreeError by viewModel.seatTreeError.collectAsState()
    var selectedMode by remember { mutableStateOf(TaskMode.SINGLE) }
    var selectedCampus by remember { mutableStateOf<SeatTreeNode?>(null) }
    var selectedFloor by remember { mutableStateOf<SeatTreeNode?>(null) }
    var selectedArea by remember { mutableStateOf<SeatTreeNode?>(null) }
    var reserveDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var seatNoInput by remember { mutableStateOf("") }

    val seatlibReady by viewModel.seatlibReady.collectAsState()
    val availableDays by viewModel.availableDays.collectAsState()
    val notificationPermission = rememberNotificationPermissionState()
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val treeLoading = treeNodes.isEmpty()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val bit101LoggedIn by viewModel.bit101LoggedIn.collectAsState(initial = false)
    val authNotice by viewModel.authNotice.collectAsState()
    val scope = rememberCoroutineScope()

    // 日期选项优先用服务端返回的可约日期；接口未返回时回落到「今天 / 明天」，避免出现空列表。
    val today = remember { LocalDate.now().format(dateFormatter) }
    val tomorrow = remember { LocalDate.now().plusDays(1).format(dateFormatter) }
    val dateOptions = availableDays.ifEmpty { listOf(today, tomorrow) }
    val dateLabel = { day: String ->
        when (day) {
            today -> "今天"
            tomorrow -> "明天"
            else -> day
        }
    }

    LaunchedEffect(reserveDate, seatlibReady) {
        if (seatlibReady && isLoggedIn) {
            viewModel.loadSeatTree(reserveDate)
            selectedCampus = null; selectedFloor = null; selectedArea = null
        }
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                if (authNotice != null) {
                    Text(authNotice!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    if (bit101LoggedIn) "学校账号已登录，但座位系统尚未授权" else "尚未登录学校账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    if (!bit101LoggedIn) {
                        mainController.navigate(NavDest.Login)
                    } else {
                        scope.launch { viewModel.ensureSeatlibSession() }
                    }
                }) {
                    Text(if (bit101LoggedIn) "授权座位系统" else "登录")
                }
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("新建预约", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        ModeSelector(selectedMode = selectedMode, onModeSelected = { selectedMode = it })
        Spacer(modifier = Modifier.height(4.dp))
        Text("选择日期", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dateOptions.forEach { day ->
                FilterChip(
                    selected = reserveDate == day,
                    onClick = { reserveDate = day },
                    label = { Text(dateLabel(day)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        Text("预约日期: $reserveDate", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(4.dp))
        if (treeLoading && seatTreeError == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else if (seatTreeError != null) {
            ErrorCard(title = "加载校区信息失败", errorText = seatTreeError!!, onDismiss = { viewModel.clearSeatTreeError() }, modifier = Modifier.fillMaxWidth())
        } else {
            CascadingDropdown(nodes = treeNodes, selectedCampus = selectedCampus, selectedFloor = selectedFloor, selectedArea = selectedArea,
                onSelectionChanged = { campusId, floorId, areaId, areaName ->
                    if (areaId.isNotEmpty()) selectedArea = treeNodes.find { it.id == areaId }
                    else if (floorId.isNotEmpty()) { selectedFloor = treeNodes.find { it.id == floorId }; selectedArea = null }
                    else { selectedCampus = treeNodes.find { it.id == campusId }; selectedFloor = null; selectedArea = null }
                }, modifier = Modifier.fillMaxWidth())
        }

        if (selectedArea != null) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("已选区域", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(buildString { selectedCampus?.let { append(it.name) }; selectedFloor?.let { if (isNotEmpty()) append(" - "); append(it.name) }; selectedArea?.let { if (isNotEmpty()) append(" - "); append(it.name) } },
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (selectedMode != TaskMode.SINGLE) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = seatNoInput,
                onValueChange = { seatNoInput = it },
                label = { Text(if (selectedMode == TaskMode.MONITOR) "目标座位号（必填）" else "目标座位号（可留空）") },
                supportingText = {
                    Text(
                        if (selectedMode == TaskMode.MONITOR) "座位空出后自动预约该座位"
                        else "留空则在所选区域内抢最早空出的座位"
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (errorMessage != null) { ErrorCard(title = "错误", errorText = errorMessage!!, onDismiss = { errorMessage = null }, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedMode == TaskMode.SINGLE) {
            Button(onClick = {
                if (selectedArea == null) { errorMessage = "请完整选择校区、楼层和区域"; return@Button }
                errorMessage = null
                viewModel.navigateToSeatMap(areaId = selectedArea!!.id, day = reserveDate)
            }, enabled = selectedArea != null && !treeLoading, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("选择座位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(onClick = {
                val area = selectedArea
                if (area == null) { errorMessage = "请完整选择校区、楼层和区域"; return@Button }
                if (selectedMode == TaskMode.MONITOR && seatNoInput.isBlank()) { errorMessage = "监控预约需要填写目标座位号"; return@Button }
                errorMessage = null
                viewModel.addTask(
                    mode = selectedMode,
                    campusName = selectedCampus?.name ?: "",
                    floorName = selectedFloor?.name ?: "",
                    areaName = area.name,
                    areaId = area.id,
                    seatNo = seatNoInput.trim(),
                    reserveDate = reserveDate
                )
                seatNoInput = ""
                // 通知权限只影响常驻通知是否可见，不影响任务本身，所以先建任务再申请
                if (!notificationPermission.granted) notificationPermission.request()
                onTaskCreated()
            }, enabled = selectedArea != null && !treeLoading, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("创建任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
