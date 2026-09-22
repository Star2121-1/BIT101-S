package cn.bit101.android.features.setting.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.helper.rememberNotificationPermissionState
import cn.bit101.android.features.setting.component.SettingItemData
import cn.bit101.android.features.setting.component.SettingsColumn
import cn.bit101.android.features.setting.component.SettingsGroup
import cn.bit101.android.features.setting.viewmodel.NotifySettingViewModel

/**
 * 提醒设置页。
 *
 * 三组：总开关 / 上课提醒 / 作业截止提醒，外加一条**系统通知权限状态** ——
 * 设置里全开着但系统权限被拒的话，什么都不会弹，必须在这里说清楚。
 */
@Composable
private fun NotifySettingPageContent(
    enabled: Boolean,
    classEnabled: Boolean,
    classLead: Long,
    ddlEnabled: Boolean,
    ddlDayEnabled: Boolean,
    ddlHourEnabled: Boolean,
    permissionGranted: Boolean,

    onToggleEnabled: (Boolean) -> Unit,
    onToggleClass: (Boolean) -> Unit,
    onToggleDdl: (Boolean) -> Unit,
    onToggleDdlDay: (Boolean) -> Unit,
    onToggleDdlHour: (Boolean) -> Unit,
    onOpenLeadDialog: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    SettingsColumn {
        SettingsGroup(
            title = "提醒",
            subTitle = "到点主动通知你，不用自己去翻",
            items = listOf(
                SettingItemData.Switch(
                    title = "启用提醒",
                    subTitle = "关闭后不再发送任何提醒",
                    checked = enabled,
                    onClick = onToggleEnabled,
                ),
            ),
        )

        SettingsGroup(
            title = "上课提醒",
            visible = enabled,
            items = listOf(
                SettingItemData.Switch(
                    title = "课前提醒",
                    subTitle = "显示节次、课程名与教室",
                    checked = classEnabled,
                    onClick = onToggleClass,
                ),
                SettingItemData.Button(
                    title = "提前时间",
                    subTitle = "上课前多久提醒",
                    text = "提前 $classLead 分钟",
                    onClick = onOpenLeadDialog,
                ),
            ),
        )

        SettingsGroup(
            title = "作业截止提醒",
            visible = enabled,
            items = listOf(
                SettingItemData.Switch(
                    title = "启用作业提醒",
                    subTitle = "DDL（乐学/自定义）截止前提醒",
                    checked = ddlEnabled,
                    onClick = onToggleDdl,
                ),
                SettingItemData.Switch(
                    title = "提前一天",
                    subTitle = "截止前一天提醒一次",
                    checked = ddlDayEnabled,
                    onClick = onToggleDdlDay,
                ),
                SettingItemData.Switch(
                    title = "提前一小时",
                    subTitle = "截止前一小时提醒一次",
                    checked = ddlHourEnabled,
                    onClick = onToggleDdlHour,
                ),
            ),
        )

        if (!permissionGranted) {
            SettingsGroup(
                title = "系统通知权限",
                subTitle = "没有权限时，上面的开关全开着也不会弹出任何提醒",
                items = listOf(
                    SettingItemData.Button(
                        title = "通知权限未开启",
                        subTitle = "点此去开启",
                        text = "去开启",
                        onClick = onRequestPermission,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun LeadMinutesDialog(
    current: Long,
    options: List<Long>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(text = "上课前多久提醒") },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = minutes == current,
                                onClick = { onPick(minutes) },
                                role = Role.RadioButton,
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = minutes == current, onClick = null)
                        Text(
                            text = "提前 $minutes 分钟",
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun NotifySettingPage() {
    val vm: NotifySettingViewModel = hiltViewModel()
    val permission = rememberNotificationPermissionState()

    val enabled by vm.enabled.flow.collectAsState(initial = true)
    val classEnabled by vm.classEnabled.flow.collectAsState(initial = true)
    val classLead by vm.classLeadMinutes.flow.collectAsState(initial = 10L)
    val ddlEnabled by vm.ddlEnabled.flow.collectAsState(initial = true)
    val ddlDayEnabled by vm.ddlDayEnabled.flow.collectAsState(initial = true)
    val ddlHourEnabled by vm.ddlHourEnabled.flow.collectAsState(initial = true)

    var showLeadDialog by rememberSaveable { mutableStateOf(false) }

    NotifySettingPageContent(
        enabled = enabled,
        classEnabled = classEnabled,
        classLead = classLead,
        ddlEnabled = ddlEnabled,
        ddlDayEnabled = ddlDayEnabled,
        ddlHourEnabled = ddlHourEnabled,
        permissionGranted = permission.granted,

        onToggleEnabled = vm::setEnabled,
        onToggleClass = vm::setClassEnabled,
        onToggleDdl = vm::setDdlEnabled,
        onToggleDdlDay = vm::setDdlDayEnabled,
        onToggleDdlHour = vm::setDdlHourEnabled,
        onOpenLeadDialog = { showLeadDialog = true },
        onRequestPermission = permission::request,
    )

    if (showLeadDialog) {
        LeadMinutesDialog(
            current = classLead,
            options = vm.leadOptions,
            onPick = {
                vm.setClassLeadMinutes(it)
                showLeadDialog = false
            },
            onDismiss = { showLeadDialog = false },
        )
    }
}
