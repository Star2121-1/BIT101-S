package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.model.SeatTreeNode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadingDropdown(
    nodes: List<SeatTreeNode>,
    selectedCampus: SeatTreeNode?,
    selectedFloor: SeatTreeNode?,
    selectedArea: SeatTreeNode?,
    onSelectionChanged: (campusId: String, floorId: String, areaId: String, areaName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val campuses = nodes.filter { it.type == 0 && it.parentId == null }
    val floors = if (selectedCampus != null) nodes.filter { it.type == 0 && it.parentId == selectedCampus.id } else emptyList()
    val areas = if (selectedFloor != null) nodes.filter { it.type == 1 && it.parentId == selectedFloor.id } else emptyList()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "校区", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp))
        SingleDropdown(items = campuses, selectedItem = selectedCampus, placeholder = "请选择校区",
            onItemSelected = { campus -> onSelectionChanged(campus.id, "", "", "") }, modifier = Modifier.fillMaxWidth())

        Text(text = "楼层", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp))
        SingleDropdown(items = floors, selectedItem = selectedFloor,
            placeholder = if (selectedCampus != null) "请选择楼层" else "请先选择校区",
            enabled = selectedCampus != null,
            onItemSelected = { floor -> onSelectionChanged(selectedCampus?.id ?: "", floor.id, "", "") },
            modifier = Modifier.fillMaxWidth())

        Text(text = "区域", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp))
        SingleDropdown(items = areas, selectedItem = selectedArea,
            placeholder = if (selectedFloor != null) "请选择区域" else "请先选择楼层",
            enabled = selectedFloor != null,
            onItemSelected = { area -> onSelectionChanged(selectedCampus?.id ?: "", selectedFloor?.id ?: "", area.id, area.name) },
            modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDropdown(
    items: List<SeatTreeNode>, selectedItem: SeatTreeNode?, placeholder: String,
    enabled: Boolean = true, onItemSelected: (SeatTreeNode) -> Unit, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selectedItem?.name ?: "", onValueChange = {}, readOnly = true,
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = enabled, singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = { onItemSelected(item); expanded = false })
            }
        }
    }
}
