package cn.bit101.android.features.seat.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
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
import cn.bit101.android.features.seat.ui.component.SeatGrid
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val dates by viewModel.seatDates.collectAsState()
    var selectedSeat by remember { mutableStateOf<Seat?>(null) }
    var isReserving by remember { mutableStateOf(false) }
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val currentSegment = if (dates.isEmpty()) null else dates.firstOrNull { it.day == day }
    fun String.isValidParam() = this.isNotBlank() && this != "null"
    val segId = currentSegment?.segmentId?.takeIf { it.isValidParam() } ?: "1"
    val startTime = currentSegment?.start?.takeIf { it.isValidParam() } ?: "08:00"
    val endTime = currentSegment?.end?.takeIf { it.isValidParam() } ?: "22:30"

    LaunchedEffect(areaId, day) {
        viewModel.loadSeatsForMap(areaId = areaId, day = day, segmentId = segId, startTime = startTime, endTime = endTime)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { mainController.navigate(NavDest.Login) }) {
                        Text("登录")
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Card(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = seatState.areaName.ifBlank { areaId }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, modifier = Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$day  ${startTime}-${endTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LegendItem(color = Color(0xFF4CAF50), label = "空闲")
                            LegendItem(color = Color(0xFFF44336), label = "占用")
                            LegendItem(color = Color(0xFFFF9800), label = "已预约")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (selectedSeat != null) "已选: ${selectedSeat!!.no}" else "未选择座位",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                color = if (selectedSeat != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("剩余: $availableCount / $totalCount", style = MaterialTheme.typography.bodyMedium,
                                color = if (availableCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
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
                        seatState.error != null -> ErrorCard(title = "加载失败", errorText = seatState.error!!, modifier = Modifier.fillMaxWidth().padding(16.dp))
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
                                    if (!viewModel.isLoggedIn.value) {
                                        viewModel.openCasLoginScreen()
                                        return@launch
                                    }
                                    viewModel.cancelReservation(seat.id); snackbarHostState.showSnackbar("已尝试取消座位 ${seat.no}"); selectedSeat = null
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
                                    if (!viewModel.isLoggedIn.value) {
                                        isReserving = false
                                        viewModel.openCasLoginScreen()
                                        return@launch
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
