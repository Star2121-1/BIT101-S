package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.Seat
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.ui.component.ErrorCard
import cn.bit101.android.features.seat.ui.component.SeatColors
import cn.bit101.android.features.seat.ui.component.SeatGrid
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeatMapScreen(
    mainController: MainController,
    areaId: String,
    day: String,
    viewModel: SeatViewModel,
    onBack: () -> Unit,
    onNavigateToTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seatState by viewModel.seatMapState.collectAsState()
    var selectedSeat by remember { mutableStateOf<Seat?>(null) }
    var isReserving by remember { mutableStateOf(false) }
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val bit101LoggedIn by viewModel.bit101LoggedIn.collectAsState(initial = false)
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

    val availableCount = seatState.seats.count { it.status == SeatStatus.AVAILABLE }
    val totalCount = seatState.seats.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择座位") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        if (!isLoggedIn) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp)) {
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
                            // 学校账号已登录 → 直接尝试换取座位会话，必要时弹出 CAS WebView
                            scope.launch { viewModel.ensureSeatlibSession() }
                        }
                    }) {
                        Text(if (bit101LoggedIn) "授权座位系统" else "登录")
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Card(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = seatState.areaName.ifBlank { areaId }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            // 换区域需要回到上一步重选，这里给个直达入口，省得先猜返回键的用途
                            TextButton(onClick = onBack) { Text("换区域", style = MaterialTheme.typography.labelMedium) }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, modifier = Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$day  ${startTime}-${endTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // 配色与文案来自 SeatColors，与座位格共用同一来源，避免图例和格子对不上
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LegendItem(color = SeatColors.available, label = SeatColors.legendLabel(SeatStatus.AVAILABLE))
                            LegendItem(color = SeatColors.occupied, label = SeatColors.legendLabel(SeatStatus.OCCUPIED))
                            LegendItem(color = SeatColors.reserved, label = SeatColors.legendLabel(SeatStatus.RESERVED))
                            LegendItem(color = SeatColors.selected, label = "已选中")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (selectedSeat != null) "已选: ${selectedSeat!!.no}" else "未选择座位",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                color = if (selectedSeat != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("剩余: $availableCount / $totalCount", style = MaterialTheme.typography.bodyMedium,
                                color = if (availableCount > 0) SeatColors.available else MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        seatState.isLoading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(modifier = Modifier.height(8.dp))
                                    Text("加载座位图…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        seatState.error != null -> Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            ErrorCard(title = "加载失败", errorText = seatState.error!!, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(onClick = { viewModel.openSeatMap(areaId = areaId, day = day) },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                                Text("重试")
                            }
                        }
                        seatState.seats.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("该区域暂无座位数据", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> SeatGrid(seats = seatState.seats, selectedSeatId = selectedSeat?.id, onSeatClick = { selectedSeat = it })
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onNavigateToTasks, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                            Icon(Icons.Default.List, null, modifier = Modifier.padding(end = 4.dp))
                            Text("任务列表")
                        }
                        if (selectedSeat?.status == SeatStatus.RESERVED) {
                            Button(onClick = {
                                val seat = selectedSeat
                                if (seat != null) { scope.launch {
                                    if (!viewModel.ensureSeatlibSession()) {
                                        snackbarHostState.showSnackbar("请在弹出的 WebView 中完成 seatlib 登录，然后重试"); return@launch
                                    }
                                    val err = viewModel.cancelReservation(seat.id)
                                    if (err == null) {
                                        snackbarHostState.showSnackbar("已取消座位 ${seat.no}")
                                        selectedSeat = null
                                    } else {
                                        snackbarHostState.showSnackbar("取消失败: $err")
                                    }
                                } }
                            }, enabled = selectedSeat != null && !isReserving, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                Text("取消预约", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(onClick = {
                            val seat = selectedSeat
                            if (seat != null) {
                                isReserving = true
                                scope.launch {
                                    if (!viewModel.ensureSeatlibSession()) {
                                        isReserving = false
                                        snackbarHostState.showSnackbar("请在弹出的 WebView 中完成 seatlib 登录，然后重试"); return@launch
                                    }
                                    val result = viewModel.reserveSeat(seat.id, segId)
                                    isReserving = false
                                    if (result == null) { snackbarHostState.showSnackbar("预约座位 ${seat.no} 成功！"); selectedSeat = null; onNavigateToTasks() }
                                    else snackbarHostState.showSnackbar("预约失败: $result")
                                }
                            }
                        }, enabled = selectedSeat != null && !isReserving, modifier = Modifier.weight(1.5f), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            if (isReserving) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(end = 8.dp))
                            Text("立即预约", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
