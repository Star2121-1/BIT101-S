package cn.bit101.android.features.user.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.bit101.android.data.score.ScoreTable
import cn.bit101.android.features.user.ScoreViewModel
import java.util.Locale

/**
 * 原生成绩页。
 *
 * ## 数据来源与口径（别自己发明）
 * - 表格来自 BIT101 认证主机的异步挑战流程（[cn.bit101.android.data.repo.base.ScoreRepo]），
 *   二维数组第 0 行表头；**列序会变**，一律按列名取值（见 [ScoreTable]）；
 * - 加权 GPA / 加权均分 / 重修取最高分，口径与官方 Web（`Score.vue`）、
 *   iOS（`ScoreModels.swift`）**逐条对齐**，公式写在 [ScoreTable] 的类注释里。
 *
 * ## 隐私
 * 出分**通知**里不出现分数（用户定的边界）。这张页面是用户主动打开的，
 * 显示分数是他自己要看的，不违反那条边界 —— 两者的区别是「主动查看」与「被动推送」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScorePage(
    state: ScoreViewModel.UiState,
    onRefresh: () -> Unit,
    onKeyword: (String) -> Unit,
    onToggleTerm: (String) -> Unit,
    onToggleType: (String) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val table = state.table

    // 筛选后的行 + 统计。几十门课，重算一次是几微秒，没必要缓存进 ViewModel
    val rows = remember(table, state.selectedTerms, state.selectedTypes, state.keyword) {
        table?.rows.orEmpty().filter { row ->
            (state.selectedTerms.isEmpty() || row.term in state.selectedTerms) &&
                (state.selectedTypes.isEmpty() || row.courseType in state.selectedTypes) &&
                (
                    state.keyword.isBlank() ||
                        row.courseName.contains(state.keyword, ignoreCase = true) ||
                        row.courseNumber.contains(state.keyword, ignoreCase = true)
                    )
        }
    }
    val summary = remember(rows) { ScoreTable.summarize(rows) }

    var detailRow by remember { mutableStateOf<ScoreTable.Row?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWeb) {
                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = "在网页里打开教务成绩")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新获取")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.staleNote?.let { note ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            when {
                state.loading && table == null -> LoadingBlock(Modifier.fillMaxSize())

                state.errorTitle != null && table == null -> ErrorBlock(
                    title = state.errorTitle,
                    detail = state.errorDetail,
                    action = state.errorAction,
                    onAction = {
                        if (state.errorAction == "去账号设置") onOpenAccount() else onRefresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                else -> {
                    // 一门都没有时不画统计卡和筛选条 —— 一屏「— / 0 / 0」加一个空搜索框，
                    // 比什么都不显示更像「界面坏了」
                    if (summary.courseCount > 0) {
                        SummaryCard(summary)

                        FilterBar(
                            terms = table?.terms.orEmpty(),
                            types = table?.courseTypes.orEmpty(),
                            selectedTerms = state.selectedTerms,
                            selectedTypes = state.selectedTypes,
                            keyword = state.keyword,
                            onToggleTerm = onToggleTerm,
                            onToggleType = onToggleType,
                            onKeyword = onKeyword,
                            onClear = onClearFilters,
                        )
                    }

                    if (rows.isEmpty()) {
                        // 两种「空」要分开说：一是教务那边真的还没出分（含没评教被卡），
                        // 二是筛选把结果筛空了。前者的下一步是去网页版确认，后者是清筛选。
                        if (table == null || table.rows.isEmpty()) {
                            // ⚠️ 这里必须给「去网页版」这个出口而不是只写一句话：
                            // 认证主机在「没完成评教」时回的是空数组，跟「还没出分」
                            // 长得一模一样，App 这边**分辨不出来**。能分辨的是教务处
                            // 的网页（它会直接写「请先完成评教」），所以把用户送过去。
                            ErrorBlock(
                                title = "还没有成绩",
                                detail = "教务这次一门成绩都没返回。可能是本学期还没出分，" +
                                    "也可能是评教没做完被卡住了 —— 这两种在 App 这边长得一样，" +
                                    "去网页版看一眼它怎么说的就知道了。",
                                action = "去网页版看看",
                                onAction = onOpenWeb,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ErrorBlock(
                                title = "没有符合条件的成绩",
                                detail = "筛选条件太窄了，清一下试试。",
                                action = "清空筛选",
                                onAction = onClearFilters,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rows, key = { it.index }) { row ->
                                ScoreRowCard(row) { detailRow = row }
                            }
                        }
                    }
                }
            }
        }
    }

    detailRow?.let { row ->
        ScoreDetailDialog(row = row, onDismiss = { detailRow = null })
    }
}

/** 顶部统计：加权 GPA / 加权均分 / 总学分 / 门数。 */
@Composable
private fun SummaryCard(summary: ScoreTable.Summary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = summary.weightedGpa?.let { format2(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "加权 GPA",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MiniStat("加权均分", summary.weightedScore?.let { format2(it) } ?: "—")
                MiniStat("总学分", formatCredit(summary.totalCredit))
                MiniStat("门数", summary.courseCount.toString())
            }
            if (summary.skipped > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "另有 ${summary.skipped} 门没计入（缓考 / 缺考 / 无学分）",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // 0 分/不及格是按官方口径**主动排除**的，不是算不出来 —— 必须说出来，
            // 否则用户会疑惑「我那门挂科的怎么没影响绩点」，或者反过来怀疑我们美化数据。
            if (summary.zeroSkipped > 0) {
                Spacer(Modifier.height(if (summary.skipped > 0) 2.dp else 8.dp))
                Text(
                    text = "另有 ${summary.zeroSkipped} 门 0 分 / 不及格，按官方口径不计入绩点",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 学期 / 性质筛选 + 搜索。 */
@Composable
private fun FilterBar(
    terms: List<String>,
    types: List<String>,
    selectedTerms: Set<String>,
    selectedTypes: Set<String>,
    keyword: String,
    onToggleTerm: (String) -> Unit,
    onToggleType: (String) -> Unit,
    onKeyword: (String) -> Unit,
    onClear: () -> Unit,
) {
    val hasFilter = selectedTerms.isNotEmpty() || selectedTypes.isNotEmpty() || keyword.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeyword,
            singleLine = true,
            label = { Text("搜课程名 / 课程号") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (terms.size > 1 || types.size > 1 || hasFilter) {
            // ⚠️ 学期和性质**必须分成两行并带标签**。
            // 早先是一条横向滚动带把两类 chip 混在一起，找「其他学期」时看到的是
            // 一长条分不出是什么的标签 —— 用户会直接判定「没有按学期查询这功能」。
            // 而按学期查恰恰是这里最常用的操作（成绩一学期才出一次）。
            if (terms.size > 1 || selectedTerms.isNotEmpty()) {
                FilterGroup(label = "学期") {
                    terms.forEach { term ->
                        FilterChip(
                            selected = selectedTerms.isEmpty() || term in selectedTerms,
                            onClick = { onToggleTerm(term) },
                            label = { Text(term) },
                        )
                    }
                }
            }
            if (types.size > 1 || selectedTypes.isNotEmpty()) {
                FilterGroup(label = "性质") {
                    types.forEach { type ->
                        FilterChip(
                            selected = selectedTypes.isEmpty() || type in selectedTypes,
                            onClick = { onToggleType(type) },
                            label = { Text(type) },
                        )
                    }
                }
            }
            if (hasFilter) {
                TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                    Text("清空筛选")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 一组带标签的横向可滚动筛选 chip。 */
@Composable
private fun FilterGroup(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 一行成绩。 */
@Composable
private fun ScoreRowCard(row: ScoreTable.Row, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.courseName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        row.term.takeIf { it.isNotBlank() },
                        row.courseType.takeIf { it.isNotBlank() },
                        row.creditText.takeIf { it.isNotBlank() }?.let { "$it 学分" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = row.scoreText.ifBlank { "—" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 详情：把这一行的**所有**列原样列出来（教务给的列就是这些，不挑）。 */
@Composable
private fun ScoreDetailDialog(row: ScoreTable.Row, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.courseName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.fields.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(132.dp),
                        )
                        Text(text = value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            Text("正在取成绩…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorBlock(
    title: String,
    detail: String?,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

// ------------------------------------------------------------ 小工具

private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)

/** 学分：整数就不带小数点（7 而不是 7.0）。 */
private fun formatCredit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else format2(value)
