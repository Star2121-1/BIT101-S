package cn.bit101.android.features.theme.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.theme.GridTheme
import cn.bit101.android.features.theme.LocalGridTokens
import cn.bit101.android.features.theme.LocalThemeIsDark
import cn.bit101.android.features.theme.PreviewSurface

/**
 * P2 组件库的 @Preview 矩阵（实施计划 P2 验收项）。
 *
 * 四档：**浅色 / 深色 / fontScale 200% / 色盲**。
 * 前两档是真机配色（走 [PreviewSurface]），后两档专门用来查两件事：
 * 1. 字号翻倍后组件**不截断**（§8.7）
 * 2. **去掉颜色后状态还认得出吗** —— 色盲档把 ColorScheme 与 GridTokens 的主要色位
 *    过一遍 CVD 矩阵（[simulateColorBlind]），检查 §8.3 的第二编码（勾选图标 / 文案 /
 *    容器底色变化 / 无按压反馈）是否真的撑得住
 *
 * > 色盲档只变换**颜色**，不改版式，所以布局结论与真机一致。
 */

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

/** 全部组件的「厨房水槽」：一屏看完十类组件的主要状态。 */
@Composable
fun GridComponentGallery(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GallerySection("顶部提示条（§3.6 / B0 / B5 / F1）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridMakeupBanner(weekdayText = "二", schoolNote = "10 月 14 日按第 2 周周二课表上课")
                GridHolidayBanner(schoolNote = "国庆节放假调休")
                GridNotLoggedInBanner(onLogin = {})
                GridNoFirstDayBanner(onSetupFirstDay = {})
                GridOfflineBanner()
            }
        }

        GallerySection("卡片（§5.1）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridCard(
                    title = "数据结构",
                    subtitle = "综合教学楼大楼A 402",
                    meta = "3-4 节 · 李明轩",
                    courseColorIndex = 4,
                    onClick = {},
                )
                GridCard(
                    title = "大学英语(三)",
                    subtitle = "文萃楼B 201",
                    courseColorIndex = 6,
                    selected = true,
                    onClick = {},
                )
                GridCard(title = "金工实习(一)", subtitle = "工训楼 B102", courseColorIndex = 8, enabled = false)
                GridCard(title = "加载中", courseColorIndex = 0, loading = true)
                GridEmptyState(message = "今天没有课", hint = "下午 15:00 后有空教室", actionText = "去看看", onAction = {})
            }
        }

        GallerySection("按钮（§5.2）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridButton(text = "在地图上查看", onClick = {})
                GridButton(text = "手动改课", onClick = {}, variant = GridButtonVariant.Outlined)
                GridTextButton(text = "删除", onClick = {})
                GridButton(text = "禁用", onClick = {}, enabled = false)
                GridButton(text = "保存中", onClick = {}, loading = true)
                GridSegmentedButton(options = listOf("空闲最长", "离我最近"), selectedIndex = 0, onSelect = {})
            }
        }

        GallerySection("Chip 筛选（§5.3）") {
            GridFilterChipRow(
                items = listOf("全部", "理学楼A", "综合教学楼大楼A", "文萃楼B"),
                selected = setOf("理学楼A"),
                onToggle = {},
            )
        }

        GallerySection("表单输入（§5.4）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridTextField(value = "", onValueChange = {}, label = "课程名", placeholder = "如：数据结构")
                GridTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = "周次",
                    isError = true,
                    errorText = "周次格式不对（示例：1-16 或 1,3,5）",
                )
                GridTextField(value = "导入课程（不可改）", onValueChange = {}, label = "教室", enabled = false)
            }
        }

        GallerySection("底部 Tab（§5.5）") {
            GridBottomTabRow(
                items = listOf("卷", "图", "网", "话", "我", "座").mapIndexed { i, label ->
                    GridTabItem(
                        label = label,
                        icon = { selected ->
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        enabled = i != 5,
                    )
                },
                selectedIndex = 0,
                onSelect = {},
            )
        }

        GallerySection("抽屉元信息行（§5.6）") {
            Column {
                GridSheetMetaRow(label = "教师", value = "李明轩")
                GridSheetMetaRow(label = "课程性质", value = "必修")
                GridSheetMetaRow(label = "课程类别", value = "理论课")
                GridSheetMetaRow(label = "备注", value = null)
            }
        }

        GallerySection("Snackbar（§5.7）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridSnackbar(message = "已定位 理学楼A")
                GridSnackbar(message = "已复制教室名", actionLabel = "撤销", onAction = {})
                GridSnackbar(message = "同步中…", loading = true)
            }
        }

        GallerySection("骨架屏（§5.8）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridSkeleton(modifier = Modifier.fillMaxWidth().height(52.dp))
                GridSkeletonLines(count = 3)
            }
        }

        GallerySection("进度环（§5.9）") {
            val limit = 300L * 1024 * 1024 * 1024
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GridTrafficRing(
                    usedBytes = (limit * 0.62f).toLong(),
                    limitBytes = limit,
                    state = GridRingState.Normal,
                    centerText = { GridRingValue("62%") },
                )
                GridTrafficRing(
                    usedBytes = (limit * 0.95f).toLong(),
                    limitBytes = limit,
                    state = GridRingState.Normal,
                    centerText = { GridRingValue("95%") },
                )
                GridTrafficRing(usedBytes = null, limitBytes = limit, state = GridRingState.NotOnline)
                GridTrafficRing(usedBytes = null, limitBytes = limit, state = GridRingState.Failed)
            }
        }
    }
}

/** 色盲档：把 ColorScheme 与 GridTokens 的主要色位过一遍 CVD 矩阵。 */
@Composable
private fun GridColorBlindScope(
    kind: ColorBlindKind,
    content: @Composable () -> Unit,
) {
    val sim: (Color) -> Color = { simulateColorBlind(it, kind) }
    val cs = MaterialTheme.colorScheme
    val scheme = cs.copy(
        primary = sim(cs.primary),
        onPrimary = sim(cs.onPrimary),
        primaryContainer = sim(cs.primaryContainer),
        onPrimaryContainer = sim(cs.onPrimaryContainer),
        secondary = sim(cs.secondary),
        onSecondary = sim(cs.onSecondary),
        tertiary = sim(cs.tertiary),
        onTertiary = sim(cs.onTertiary),
        background = sim(cs.background),
        onBackground = sim(cs.onBackground),
        surface = sim(cs.surface),
        onSurface = sim(cs.onSurface),
        surfaceVariant = sim(cs.surfaceVariant),
        onSurfaceVariant = sim(cs.onSurfaceVariant),
        error = sim(cs.error),
        onError = sim(cs.onError),
        outline = sim(cs.outline),
        outlineVariant = sim(cs.outlineVariant),
        inverseSurface = sim(cs.inverseSurface),
        inverseOnSurface = sim(cs.inverseOnSurface),
        inversePrimary = sim(cs.inversePrimary),
    )
    val tokens = GridTheme.tokens
    val simulatedTokens = tokens.copy(
        surface2 = sim(tokens.surface2),
        surface3 = sim(tokens.surface3),
        disabledBg = sim(tokens.disabledBg),
        skeleton = sim(tokens.skeleton),
        textPlaceholder = sim(tokens.textPlaceholder),
        textDisabled = sim(tokens.textDisabled),
        success = sim(tokens.success),
        accent = sim(tokens.accent),
        accentText = sim(tokens.accentText),
        stateCalm = sim(tokens.stateCalm),
        stateAttention = sim(tokens.stateAttention),
        stateUrgent = sim(tokens.stateUrgent),
        coursePalette = tokens.coursePalette.map {
            it.copy(fill = sim(it.fill), ink = sim(it.ink), mark = sim(it.mark))
        },
    )
    CompositionLocalProvider(LocalGridTokens provides simulatedTokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Preview(name = "① 组件总览 · 浅色", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun PreviewGalleryLight() {
    PreviewSurface { GridComponentGallery() }
}

@Preview(name = "② 组件总览 · 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 411, heightDp = 900)
@Composable
private fun PreviewGalleryDark() {
    PreviewSurface(darkTheme = true) { GridComponentGallery() }
}

@Preview(
    name = "③ 组件总览 · fontScale 200%（查截断）",
    showBackground = true,
    fontScale = 2f,
    widthDp = 411,
    heightDp = 900,
)
@Composable
private fun PreviewGalleryFontScale() {
    PreviewSurface { GridComponentGallery() }
}

@Preview(name = "④ 组件总览 · 红绿色盲（查第二编码）", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun PreviewGalleryDeuteranopia() {
    PreviewSurface {
        GridColorBlindScope(kind = ColorBlindKind.Deuteranopia) { GridComponentGallery() }
    }
}

@Preview(name = "⑤ 组件总览 · 全色盲（灰度）", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun PreviewGalleryAchromatopsia() {
    PreviewSurface {
        GridColorBlindScope(kind = ColorBlindKind.Achromatopsia) { GridComponentGallery() }
    }
}

@Preview(name = "⑥ 暗色 + 色盲", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun PreviewGalleryDarkBlind() {
    PreviewSurface(darkTheme = true) {
        CompositionLocalProvider(LocalThemeIsDark provides true) {
            GridColorBlindScope(kind = ColorBlindKind.Deuteranopia) { GridComponentGallery() }
        }
    }
}
