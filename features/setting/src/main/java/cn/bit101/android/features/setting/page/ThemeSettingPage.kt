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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.toAppPaletteData
import cn.bit101.android.config.setting.base.toDarkThemeData
import cn.bit101.android.features.setting.component.SettingItemData
import cn.bit101.android.features.setting.component.SettingsColumn
import cn.bit101.android.features.setting.component.SettingsGroup
import cn.bit101.android.features.setting.viewmodel.ThemeViewModel

@Composable
private fun ThemeSettingPageContent(
    dynamic: Boolean,
    darkMode: String,
    rotate: Boolean,
    palette: String,

    onChangeDynamic: (Boolean) -> Unit,
    onChangeRotate: (Boolean) -> Unit,

    onOpenDarkModeDialog: () -> Unit,
    onOpenPaletteDialog: () -> Unit,
) {
    val settings = listOf(
        SettingItemData.Switch(
            title = "动态适配系统主题",
            subTitle = "需要Android 12及以上",
            onClick = onChangeDynamic,
            checked = dynamic
        ),
        SettingItemData.Button(
            title = "暗黑模式",
            subTitle = "跟随系统，亮色，暗色",
            onClick = onOpenDarkModeDialog,
            text = darkMode,
        ),
        SettingItemData.Button(
            title = "配色方案",
            subTitle = "BIT101经典配色，暖棕配色，清新青绿配色",
            onClick = onOpenPaletteDialog,
            text = palette,
        ),
        SettingItemData.Switch(
            title = "自动旋转",
            subTitle = "设置是否自动旋转",
            onClick = onChangeRotate,
            checked = rotate
        )
    )

    SettingsColumn {
        SettingsGroup(
            items = settings,
        )
    }
}

@Composable
private fun DarkModeDialog(
    darkMode: DarkThemeMode,

    onChangeDarkMode: (DarkThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(text = "暗黑模式") },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(scrollState)
            ) {
                DarkThemeMode.allModes.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = (item == darkMode),
                                onClick = {
                                    onChangeDarkMode(item)
                                    onDismiss()
                                },
                                role = Role.RadioButton
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (item == darkMode),
                            onClick = null
                        )
                        Text(
                            text = item.toDarkThemeData().name,
                            modifier = Modifier.padding(start = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    )
}


@Composable
private fun PaletteDialog(
    palette: AppPalette,

    onChangePalette: (AppPalette) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(text = "配色方案") },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(scrollState)
            ) {
                AppPalette.allPalettes.forEach { item ->
                    // available=false ⇒ 置灰不可选（P5-A 数据契约：枚举的唯一置灰来源）；
                    // experimental=true ⇒ 展示名后跟「（实验性）」次级色标注（占位值待官方 VI）
                    val enabled = item.available
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = (item == palette),
                                enabled = enabled,
                                onClick = {
                                    onChangePalette(item)
                                    onDismiss()
                                },
                                role = Role.RadioButton
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (item == palette),
                            onClick = null,
                            enabled = enabled,
                        )
                        Text(
                            text = paletteDisplayName(item),
                            modifier = Modifier.padding(start = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (enabled) {
                                Color.Unspecified
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
            }
        }
    )
}

/** 展示名 + 「（实验性）」次级色后缀（[AppPalette.experimental] 的唯一 UI 出口）。 */
@Composable
private fun paletteDisplayName(palette: AppPalette): AnnotatedString {
    val data = palette.toAppPaletteData()
    if (!palette.experimental) return AnnotatedString(data.name)
    val suffixColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(data.name)
        withStyle(SpanStyle(color = suffixColor, fontSize = 13.sp)) {
            append("（实验性）")
        }
    }
}


@Composable
internal fun ThemeSettingPage() {
    val vm: ThemeViewModel = hiltViewModel()

    val dynamic by vm.dynamicTheme.flow.collectAsState(initial = false)
    val darkMode by vm.darkThemeMode.flow.collectAsState(initial = DarkThemeMode.System)
    val rotate by vm.autoRotate.flow.collectAsState(initial = false)
    val palette by vm.appPalette.flow.collectAsState(initial = AppPalette.Brand)

    var showDarkModeDialog by rememberSaveable { mutableStateOf(false) }
    var showPaletteDialog by rememberSaveable { mutableStateOf(false) }

    ThemeSettingPageContent(
        dynamic = dynamic,
        darkMode = darkMode.toDarkThemeData().name,
        rotate = rotate,
        palette = palette.toAppPaletteData().name,

        onChangeDynamic = vm::setDynamicTheme,
        onChangeRotate = vm::setAutoRotate,
        onOpenDarkModeDialog = { showDarkModeDialog = true },
        onOpenPaletteDialog = { showPaletteDialog = true },
    )

    if (showDarkModeDialog) {
        DarkModeDialog(
            darkMode = darkMode,
            onChangeDarkMode = vm::setDarkThemeMode,
            onDismiss = { showDarkModeDialog = false }
        )
    }

    if (showPaletteDialog) {
        PaletteDialog(
            palette = palette,
            onChangePalette = vm::setAppPalette,
            onDismiss = { showPaletteDialog = false }
        )
    }
}