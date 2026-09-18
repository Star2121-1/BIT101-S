package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatPickMode
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatNumberComparator
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.ui.component.ErrorCard
import cn.bit101.android.features.seat.ui.component.SeatColors
import cn.bit101.android.features.seat.ui.component.SeatGrid
import cn.bit101.android.features.seat.ui.component.SeatMapCanvas
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatMapScreen(
    mainController: MainController,
    areaId: String,
    day: String,
    viewModel: SeatViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seatState by viewModel.seatMapState.collectAsState()
    val pickMode by viewModel.pickMode.collectAsState()
    val pickedSeats by viewModel.pickedSeats.collectAsState()
    var selectedSeat by remember { mutableStateOf<Seat?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val bit101LoggedIn by viewModel.bit101LoggedIn.collectAsState()
    val authNotice by viewModel.authNotice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 时段参数由 ViewModel 在加载时解析并记录在 query 中，UI 只读取，避免用回落值先行请求
    val segId = seatState.query?.segmentId ?: "1"
    val startTime = seatState.query?.startTime ?: "08:00"
    val endTime = seatState.query?.endTime ?: "22:30"

    LaunchedEffect(areaId, day, isLoggedIn) {
        if (isLoggedIn) viewModel.openSeatMap(areaId = areaId, day = day)
    }

    val isPicking = pickMode != SeatPickMode.RESERVE
    val freeCount = seatState.seats.count { it.status == SeatStatus.AVAILABLE }
    val mineCount = seatState.seats.count { it.status == SeatStatus.MINE }

    /** 底图模式下可点：预约=空位与自己的预约；选座模式=任意座位（监控就是等被占的座位）。 */
    val pickable: (Seat) -> Boolean = { seat ->
        if (isPicking) true
        else seat.status == SeatStatus.AVAILABLE || seat.status == SeatStatus.MINE
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (pickMode) {
                            SeatPickMode.RESERVE -> "选择座位"
                            SeatPickMode.MONITOR -> "选目标座位"
                            SeatPickMode.PREFER -> "选偏好座位（按优先级）"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetPick()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        if (!isLoggedIn) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
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
                        if (!bit101LoggedIn) mainController.navigate(NavDest.Login)
                        else scope.launch { viewModel.ensureSeatlibSession() }
                    }) { Text(if (bit101LoggedIn) "授权座位系统" else "登录") }
                }
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 信息条：从「大卡片 + 图例行 + 已选行」压缩成一行 chips。
            // 底图自带图例（服务端在图上画好了），所以不再重复一整套图例。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoChip(seatState.areaName.ifBlank { "区域 $areaId" }, emphasized = true)
                InfoChip("$day $startTime-$endTime")
                InfoChip("空闲 $freeCount/${seatState.seats.size}")
                if (mineCount > 0) InfoChip("我的 $mineCount")
                TextButton(onClick = { viewModel.resetPick(); onBack() }) {
                    Text("换区域", style = MaterialTheme.typography.labelMedium)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    seatState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("加载座位图…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    seatState.error != null -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ErrorCard(title = "加载失败", errorText = seatState.error!!, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.openSeatMap(areaId = areaId, day = day) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("重试") }
                    }
                    seatState.seats.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该区域暂无座位数据", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val imageUrl = seatState.images?.forStatus(SeatStatus.AVAILABLE)
                        val selectedIds = if (isPicking) pickedSeats.map { it.id }.toSet()
                        else selectedSeat?.let { setOf(it.id) } ?: emptySet()
                        if (imageUrl != null && seatState.seats.all { it.hasMapPosition }) {
                            SeatMapCanvas(
                                seats = seatState.seats,
                                imageUrl = imageUrl,
                                selectedIds = selectedIds,
                                pickable = pickable,
                                onSeatTap = { seat ->
                                    if (isPicking) viewModel.togglePick(seat)
                                    else if (seat.status == SeatStatus.AVAILABLE || seat.status == SeatStatus.MINE) selectedSeat = seat
                                    else scope.launch {
                                        snackbarHostState.showSnackbar("${seat.no} ${SeatColors.legendLabel(seat.status)}，不能预约")
                                    }
                                }
                            )
                        } else {
                            // 无底图兜底：密集方格（56dp），并按**座位号数值**排序 ——
                            // 服务端返回顺序是乱的（001..006,055,049…），照搬会让人找不到座位
                            Column(Modifier.fillMaxSize()) {
                                SeatGrid(
                                    seats = seatState.seats.sortedWith(
                                        compareBy(SeatNumberComparator) { it.no }
                                    ),
                                    selectedSeatId = selectedSeat?.id,
                                    onSeatClick = { seat ->
                                        if (isPicking) viewModel.togglePick(seat)
                                        else if (pickable(seat)) selectedSeat = seat
                                    }
                                )
                                FallbackLegend()
                            }
                        }
                    }
                }
            }

            if (isPicking) {
                PickerBottomBar(
                    pickedSeats = pickedSeats,
                    mode = pickMode,
                    onClear = { viewModel.clearPickedSeats() },
                    onConfirm = {
                        if (pickMode == SeatPickMode.MONITOR && pickedSeats.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("请点选一个要监控的座位") }
                        } else {
                            onBack()
                        }
                    }
                )
            } else {
                ReserveBottomBar(
                    selectedSeat = selectedSeat,
                    isBusy = isBusy,
                    onTasks = onNavigateToTasks,
                    onCancel = {
                        val seat = selectedSeat ?: return@ReserveBottomBar
                        isBusy = true
                        scope.launch {
                            if (!viewModel.ensureSeatlibSession()) {
                                isBusy = false
                                snackbarHostState.showSnackbar("请重新授权座位系统后重试")
                                return@launch
                            }
                            val err = viewModel.cancelReservation(seat.id)
                            isBusy = false
                            if (err == null) {
                                snackbarHostState.showSnackbar("已取消座位 ${seat.no}")
                                selectedSeat = null
                            } else {
                                snackbarHostState.showSnackbar("取消失败: $err")
                            }
                        }
                    },
                    onReserve = {
                        val seat = selectedSeat ?: return@ReserveBottomBar
                        isBusy = true
                        scope.launch {
                            if (!viewModel.ensureSeatlibSession()) {
                                isBusy = false
                                snackbarHostState.showSnackbar("请重新授权座位系统后重试")
                                return@launch
                            }
                            val result = viewModel.reserveSeat(seat.id, segId)
                            isBusy = false
                            if (result == null) {
                                snackbarHostState.showSnackbar(
                                    "预约座位 ${seat.no} 成功！${signInHintFor(day)}"
                                )
                                selectedSeat = null
                                viewModel.refreshMyReservations()
                                onNavigateToTasks()
                            } else {
                                snackbarHostState.showSnackbar("预约失败: $result")
                            }
                        }
                    }
                )
            }
        }
    }
}

/** 预约成功后的签到提示（规则：当日 60 分钟内、次日 9:00 前）。 */
private fun signInHintFor(day: String): String =
    if (day == java.time.LocalDate.now().toString()) "请在 60 分钟内刷卡签到"
    else "请在当日 9:00 前刷卡签到"

@Composable
private fun InfoChip(text: String, emphasized: Boolean = false) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PickerBottomBar(
    pickedSeats: List<Seat>,
    mode: SeatPickMode,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        pickedSeats.isEmpty() && mode == SeatPickMode.PREFER -> "未指定座位（不限：谁先空抢谁）"
                        pickedSeats.isEmpty() -> "未选择座位"
                        else -> pickedSeats.mapIndexed { i, s ->
                            if (mode == SeatPickMode.PREFER) "${i + 1}.${s.no} → ${SeatColors.shortLabel(s.status)}" else "${s.no}（${SeatColors.shortLabel(s.status)}）"
                        }.joinToString("  ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pickedSeats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (pickedSeats.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        mode == SeatPickMode.MONITOR -> "确定监控这个座位"
                        pickedSeats.isEmpty() -> "不限座位，返回"
                        else -> "确定（${pickedSeats.size} 个）"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ReserveBottomBar(
    selectedSeat: Seat?,
    isBusy: Boolean,
    onTasks: () -> Unit,
    onCancel: () -> Unit,
    onReserve: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onTasks,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) { Text("任务列表") }

            if (selectedSeat?.status == SeatStatus.MINE) {
                Button(
                    onClick = onCancel,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("取消预约", fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = onReserve,
                    enabled = selectedSeat != null && !isBusy,
                    modifier = Modifier.weight(1.5f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp).padding(end = 4.dp)
                        )
                    }
                    Text(
                        if (selectedSeat != null) "预约 ${selectedSeat.no}" else "立即预约",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 方格兜底模式的图例（底图模式用服务端画在图里的图例，不重复）。 */
@Composable
private fun FallbackLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOf(SeatStatus.AVAILABLE, SeatStatus.MINE, SeatStatus.RESERVED, SeatStatus.IN_USE).forEach { status ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(SeatColors.of(status), MaterialTheme.shapes.small)
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    SeatColors.legendLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
