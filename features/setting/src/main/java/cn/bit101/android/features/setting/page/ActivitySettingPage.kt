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
import cn.bit101.android.data.eclass.EclassDdlLogic
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.setting.component.SettingItemData
import cn.bit101.android.features.setting.component.SettingsColumn
import cn.bit101.android.features.setting.component.SettingsGroup
import cn.bit101.android.features.setting.viewmodel.ActivitySettingViewModel

/**
 * 「动态设置」页。
 *
 * 两组：
 * 1. **显示设置** —— 只看作业 / 条数上限 / 显示已过期（都持久化，改完动态页实时生效）
 * 2. **快速入口** —— 延河课堂（带会话状态）与乐学（**没有会话检查接口**，只给入口）
 *
 * ⚠️ 两个平台都必须用 **App 内 WebView** 打开：只有它和 App 共用 CookieManager，
 * 用系统浏览器登录的话 App 这边拿不到会话（见 `WebViewCookieSync`）。
 */
@Composable
private fun ActivitySettingPageContent(
    onlyHomework: Boolean,
    limit: Int,
    showExpired: Boolean,
    eclassSessionAlive: Boolean?,
    lexueHome: String,

    onToggleOnlyHomework: (Boolean) -> Unit,
    onToggleShowExpired: (Boolean) -> Unit,
    onOpenLimitDialog: () -> Unit,
    onOpenEclass: () -> Unit,
    onOpenLexue: () -> Unit,
) {
    val displayItems = listOf(
        SettingItemData.Switch(
            title = "只看作业",
            subTitle = "关闭时显示全部动态（资料、公告也显示）",
            checked = onlyHomework,
            onClick = onToggleOnlyHomework,
        ),
        SettingItemData.Button(
            title = "条数上限",
            subTitle = "最多加载多少条动态",
            text = "$limit 条",
            onClick = onOpenLimitDialog,
        ),
        SettingItemData.Switch(
            title = "显示已过期",
            subTitle = "关闭后不再显示截止时间已过的作业",
            checked = showExpired,
            onClick = onToggleShowExpired,
        ),
    )

    val entryItems = listOf(
        SettingItemData.Button(
            title = "延河课堂",
            subTitle = eclassSessionText(eclassSessionAlive),
            text = "打开登录页",
            onClick = onOpenEclass,
        ),
        SettingItemData.Button(
            title = "乐学",
            subTitle = "乐学没有会话检查接口，打开主页可能要求重新登录",
            text = if (lexueHome.isBlank()) "加载中…" else "打开乐学主页",
            onClick = onOpenLexue,
            enable = lexueHome.isNotBlank(),
        ),
    )

    SettingsColumn {
        SettingsGroup(
            title = "显示设置",
            subTitle = "控制「动态」页显示哪些内容",
            items = displayItems,
        )

        SettingsGroup(
            title = "快速入口",
            subTitle = "在 App 内打开，登录状态与 App 共用",
            items = entryItems,
        )
    }
}

/** 会话状态文案 —— `null`（还没检查完）也要能显示。 */
private fun eclassSessionText(alive: Boolean?): String = when (alive) {
    null -> "会话状态：检查中…"
    true -> "会话状态：已登录"
    false -> "会话状态：未登录，点右侧去登录"
}

@Composable
private fun ActivityLimitDialog(
    current: Int,
    options: List<Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(text = "条数上限") },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = option == current,
                                onClick = { onPick(option) },
                                role = Role.RadioButton,
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(
                            text = "$option 条",
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun ActivitySettingPage(mainController: MainController) {
    val vm: ActivitySettingViewModel = hiltViewModel()

    val onlyHomework by vm.onlyHomework.flow.collectAsState(initial = false)
    val limit by vm.limit.flow.collectAsState(initial = 60)
    val showExpired by vm.showExpired.flow.collectAsState(initial = true)
    val eclassSessionAlive by vm.eclassSessionAlive.collectAsState()
    val lexueHome by vm.lexueHome.collectAsState()

    var showLimitDialog by rememberSaveable { mutableStateOf(false) }

    ActivitySettingPageContent(
        onlyHomework = onlyHomework,
        limit = limit,
        showExpired = showExpired,
        eclassSessionAlive = eclassSessionAlive,
        lexueHome = lexueHome,

        onToggleOnlyHomework = vm::setOnlyHomework,
        onToggleShowExpired = vm::setShowExpired,
        onOpenLimitDialog = { showLimitDialog = true },
        onOpenEclass = { mainController.openWebPage(EclassDdlLogic.LOGIN_URL) },
        onOpenLexue = { mainController.openWebPage(lexueHome) },
    )

    if (showLimitDialog) {
        ActivityLimitDialog(
            current = limit,
            options = vm.limitOptions,
            onPick = {
                vm.setLimit(it)
                showLimitDialog = false
            },
            onDismiss = { showLimitDialog = false },
        )
    }
}
