package cn.bit101.android.features.seat.ui.screen

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.seat.SeatViewModel
import cn.bit101.android.features.seat.model.SeatTreeNode
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.ui.component.CascadingDropdown
import cn.bit101.android.features.seat.ui.component.ErrorCard
import cn.bit101.android.features.seat.ui.component.ModeSelector
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun NewTaskScreen(mainController: MainController, viewModel: SeatViewModel, modifier: Modifier = Modifier) {
    val treeNodes by viewModel.seatTree.collectAsState()
    val seatTreeError by viewModel.seatTreeError.collectAsState()
    var selectedMode by remember { mutableStateOf(TaskMode.SINGLE) }
    var selectedCampus by remember { mutableStateOf<SeatTreeNode?>(null) }
    var selectedFloor by remember { mutableStateOf<SeatTreeNode?>(null) }
    var selectedArea by remember { mutableStateOf<SeatTreeNode?>(null) }
    var reserveDate by remember { mutableStateOf(LocalDate.now()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val seatlibReady by viewModel.seatlibReady.collectAsState()
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val treeLoading = treeNodes.isEmpty()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState(initial = false)

    LaunchedEffect(reserveDate, seatlibReady) {
        if (seatlibReady) { viewModel.loadSeatTree(reserveDate.format(dateFormatter)); selectedCampus = null; selectedFloor = null; selectedArea = null }
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Button(onClick = { mainController.navigate(NavDest.Login) }) {
                    Text("登录")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = reserveDate == LocalDate.now(), onClick = { reserveDate = LocalDate.now() },
                label = { Text("今天") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
            FilterChip(selected = reserveDate == LocalDate.now().plusDays(1), onClick = { reserveDate = LocalDate.now().plusDays(1) },
                label = { Text("明天") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
        Text("预约日期: ${reserveDate.format(dateFormatter)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(4.dp))
        if (treeLoading && seatTreeError == null) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        else if (seatTreeError != null) { ErrorCard(title = "加载校区信息失败", errorText = seatTreeError!!, onDismiss = { viewModel.clearSeatTreeError() }, modifier = Modifier.fillMaxWidth()) }
        else { CascadingDropdown(nodes = treeNodes, selectedCampus = selectedCampus, selectedFloor = selectedFloor, selectedArea = selectedArea,
            onSelectionChanged = { campusId, floorId, areaId, areaName ->
                if (areaId.isNotEmpty()) selectedArea = treeNodes.find { it.id == areaId }
                else if (floorId.isNotEmpty()) { selectedFloor = treeNodes.find { it.id == floorId }; selectedArea = null }
                else { selectedCampus = treeNodes.find { it.id == campusId }; selectedFloor = null; selectedArea = null }
            }, modifier = Modifier.fillMaxWidth()) }

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
        if (errorMessage != null) { ErrorCard(title = "错误", errorText = errorMessage!!, onDismiss = { errorMessage = null }, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (selectedArea == null) { errorMessage = "请完整选择校区、楼层和区域"; return@Button }
            errorMessage = null
            viewModel.navigateToSeatMap(areaId = selectedArea!!.id, day = reserveDate.format(dateFormatter))
        }, enabled = selectedArea != null && !treeLoading, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Text("选择座位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
