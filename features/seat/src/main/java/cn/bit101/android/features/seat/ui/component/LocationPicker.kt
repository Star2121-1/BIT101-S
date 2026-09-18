package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.bit101.android.features.seat.model.SeatTreeNode

/**
 * 校区 / 楼层 / 区域选择。
 *
 * 与早期 `CascadingDropdown` 的差别：
 * - 区域改为**芯片**而不是下拉：区域一共 5-6 个且名字长，芯片一屏能看全，
 *   也避开 Compose 下拉弹窗在手机上的定位问题（空间不足时会渲染到字段上方，
 *   容易让人以为菜单是空的）
 * - 选中楼层后显示**楼层平面图**（服务端 `image_url`，各阅览室用绿块标注），
 *   点图可放大，用来认路
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocationPicker(
    nodes: List<SeatTreeNode>,
    selectedCampus: SeatTreeNode?,
    selectedFloor: SeatTreeNode?,
    selectedArea: SeatTreeNode?,
    onCampusSelected: (SeatTreeNode) -> Unit,
    onFloorSelected: (SeatTreeNode) -> Unit,
    onAreaSelected: (SeatTreeNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val campuses = nodes.filter { it.type == 0 && it.parentId == null }
    val floors = if (selectedCampus != null) {
        nodes.filter { it.type == 0 && it.parentId == selectedCampus.id }
    } else emptyList()
    val areas = if (selectedFloor != null) {
        nodes.filter { it.type == 1 && it.parentId == selectedFloor.id }
    } else emptyList()

    var showPlan by remember { mutableStateOf(false) }
    val planUrl = selectedFloor?.imageUrl

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                LabelText("校区")
                SingleDropdown(
                    items = campuses, selectedItem = selectedCampus, placeholder = "请选择校区",
                    onItemSelected = onCampusSelected
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                LabelText("楼层")
                SingleDropdown(
                    items = floors, selectedItem = selectedFloor,
                    placeholder = if (selectedCampus != null) "请选择楼层" else "请先选择校区",
                    enabled = selectedCampus != null,
                    onItemSelected = onFloorSelected
                )
            }
        }

        // 楼层平面图：官方图（各阅览室绿块标注），用来判断区域位置
        if (planUrl != null) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelText("楼层平面图")
                    TextButton(onClick = { showPlan = true }) {
                        Text("放大", style = MaterialTheme.typography.labelSmall)
                    }
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(planUrl).crossfade(true).build(),
                    contentDescription = "${selectedFloor.name}平面图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                )
            }
        }

        LabelText("区域")
        if (areas.isEmpty()) {
            Text(
                if (selectedFloor == null) "请先选择楼层" else "该楼层当前没有可预约区域",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                areas.forEach { area ->
                    FilterChip(
                        selected = selectedArea?.id == area.id,
                        onClick = { onAreaSelected(area) },
                        label = { Text(area.name, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }

    if (showPlan && planUrl != null) {
        AlertDialog(
            onDismissRequest = { showPlan = false },
            confirmButton = { TextButton(onClick = { showPlan = false }) { Text("关闭") } },
            icon = { Icon(Icons.Rounded.Close, null) },
            title = { Text(selectedFloor?.name ?: "楼层平面图") },
            text = {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(planUrl).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

@Composable
private fun LabelText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SingleDropdown(
    items: List<SeatTreeNode>, selectedItem: SeatTreeNode?, placeholder: String,
    enabled: Boolean = true, onItemSelected: (SeatTreeNode) -> Unit, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedItem?.name ?: "", onValueChange = {}, readOnly = true,
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = enabled, singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.name) },
                    onClick = { onItemSelected(item); expanded = false }
                )
            }
        }
    }
}

/** 面向「选择座位」这类操作的小标题 + 已选内容展示。 */
@Composable
fun PickedSeatRow(
    title: String,
    pickedLabels: List<String>,
    hint: String,
    buttonText: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelText(title)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPick) { Text(buttonText, fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.weight(1f))
            if (pickedLabels.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("清空", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (pickedLabels.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                pickedLabels.joinToString("  "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
