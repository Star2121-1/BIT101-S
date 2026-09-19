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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatPickMode
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.ui.component.ErrorCard
import cn.bit101.android.features.seat.ui.component.LocationPicker
import cn.bit101.android.features.seat.ui.component.ModeSelector
import cn.bit101.android.features.seat.ui.component.PickedSeatRow
import cn.bit101.android.features.seat.ui.component.SeatColors
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
    // ⚠️ 表单状态必须用 rememberSaveable：跳去座位图选座再返回时，本页会离开组合，
    // remember 的状态会被整体丢弃 —— 表现为「选完座位回来，预约模式和已选地点全被重置」，
    // 于是刚选的座位无处安放、任务也建不出来。
    // 树节点本身不可序列化，所以只存 id，回来后再从树里查回节点。
    var selectedModeName by rememberSaveable { mutableStateOf(TaskMode.SINGLE.name) }
    var reserveDate by rememberSaveable {
        mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
    }
    var selectedCampusId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFloorId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAreaId by rememberSaveable { mutableStateOf<String?>(null) }

    /** 最近一次真正发起加载的日期，用来区分「日期变了要重载」与「只是重新进入页面」。 */
    var loadedTreeDate by rememberSaveable { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val pickedSeats by viewModel.pickedSeats.collectAsState()

    val selectedMode = TaskMode.values().firstOrNull { it.name == selectedModeName } ?: TaskMode.SINGLE
    val selectedCampus = treeNodes.find { it.id == selectedCampusId }
    val selectedFloor = treeNodes.find { it.id == selectedFloorId }
    val selectedArea = treeNodes.find { it.id == selectedAreaId }

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

    // 初始选中日期是**设备本地今天**，而可约日期来自服务端（按北京时间算）。
    // 设备时区与北京不一致时（模拟器用 UTC 就会落到「昨天」），这个日期既不在选项里、
    // 服务端也已经不接受，会用无效日期去查座位树 —— 表现为下拉里只剩个别常年开放的区域。
    // 服务端日期一到就把它拉回第一个可约日期。
    LaunchedEffect(availableDays) {
        if (availableDays.isNotEmpty() && reserveDate !in availableDays) {
            reserveDate = availableDays.first()
        }
    }

    // isLoggedIn 必须进 key：登录态可能在页面已组合之后才就绪，
    // 否则 key 不变、副作用不会重跑，座位树就永远不会加载。
    //
    // ⚠️ 但**只在日期真的变了时才加载并清空已选地点**：从座位图选完座位返回时
    // 本页会重新进入组合、这个副作用会再跑一次，若无条件清空，用户刚选好的
    // 校区/楼层/区域（连同选座的前提）就又没了。
    LaunchedEffect(reserveDate, seatlibReady, isLoggedIn) {
        if (seatlibReady && isLoggedIn && loadedTreeDate != reserveDate) {
            viewModel.loadSeatTree(reserveDate)
            selectedCampusId = null; selectedFloorId = null; selectedAreaId = null
            loadedTreeDate = reserveDate
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
        ModeSelector(selectedMode = selectedMode, onModeSelected = { selectedModeName = it.name })
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
            LocationPicker(
                nodes = treeNodes,
                selectedCampus = selectedCampus,
                selectedFloor = selectedFloor,
                selectedArea = selectedArea,
                onCampusSelected = { campus ->
                    selectedCampusId = campus.id; selectedFloorId = null; selectedAreaId = null
                },
                onFloorSelected = { floor ->
                    selectedFloorId = floor.id; selectedAreaId = null
                },
                onAreaSelected = { area -> selectedAreaId = area.id },
                modifier = Modifier.fillMaxWidth()
            )
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

        // 监控 / 优先：座位从**图上点选**，不再要求手打座位号（易错且不知道位置好坏）
        if (selectedMode != TaskMode.SINGLE) {
            Spacer(modifier = Modifier.height(4.dp))
            PickedSeatRow(
                title = if (selectedMode == TaskMode.MONITOR) "目标座位（必选）" else "偏好座位（可选，按顺序抢）",
                pickedLabels = pickedSeats.mapIndexed { i, seat ->
                    if (selectedMode == TaskMode.PREFER) "${i + 1}.${seat.no}" else "${seat.no}（${SeatColors.shortLabel(seat.status)}）"
                },
                hint = if (selectedMode == TaskMode.MONITOR) {
                    "在座位图上点选一个座位 —— 已被占的座位也能选，那正是要等它空出来的目标"
                } else {
                    "不选 = 不限座位（区域内谁先空就抢谁）；也可点选多个座位按优先级依次抢"
                },
                buttonText = if (selectedMode == TaskMode.MONITOR) "选择目标座位" else "选择偏好座位",
                onPick = {
                    val area = selectedArea
                    if (area == null) {
                        errorMessage = "请先选择校区、楼层和区域"
                    } else {
                        errorMessage = null
                        viewModel.preparePick(
                            if (selectedMode == TaskMode.MONITOR) SeatPickMode.MONITOR else SeatPickMode.PREFER
                        )
                        viewModel.navigateToSeatMap(areaId = area.id, day = reserveDate)
                    }
                },
                onClear = { viewModel.clearPickedSeats() }
            )
        }
        if (errorMessage != null) { ErrorCard(title = "错误", errorText = errorMessage!!, onDismiss = { errorMessage = null }, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedMode == TaskMode.SINGLE) {
            Button(onClick = {
                if (selectedArea == null) { errorMessage = "请完整选择校区、楼层和区域"; return@Button }
                errorMessage = null
                viewModel.resetPick()
                viewModel.navigateToSeatMap(areaId = selectedArea!!.id, day = reserveDate)
            }, enabled = selectedArea != null && !treeLoading, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("选择座位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(onClick = {
                val area = selectedArea
                if (area == null) { errorMessage = "请完整选择校区、楼层和区域"; return@Button }
                if (selectedMode == TaskMode.MONITOR && pickedSeats.isEmpty()) {
                    errorMessage = "监控预约需要先在座位图上点选目标座位"; return@Button
                }
                errorMessage = null
                viewModel.addTask(
                    mode = selectedMode,
                    campusName = selectedCampus?.name ?: "",
                    floorName = selectedFloor?.name ?: "",
                    areaName = area.name,
                    areaId = area.id,
                    seatNo = pickedSeats.firstOrNull()?.no ?: "",
                    preferredSeats = if (selectedMode == TaskMode.PREFER) pickedSeats.map { it.no } else emptyList(),
                    reserveDate = reserveDate
                )
                viewModel.clearPickedSeats()
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
