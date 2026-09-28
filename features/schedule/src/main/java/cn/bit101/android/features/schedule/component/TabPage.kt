package cn.bit101.android.features.schedule.component

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.OverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * @author flwfdd
 * @date 2023/3/16 20:09
 * @description 可以左右滑动切换的TabPager
 */

internal data class TabPagerItem(
    val title: String,
    val content: @Composable (active: Boolean) -> Unit
)

/**
 * @param requestedPage 外部请求「切到第几个 tab」（组件点条目跳进来时用）；null = 不干预
 * @param onRequestHandled 处理完请求后的回调 —— 调用方**必须**借此清掉请求，
 *   否则下次进入本页会莫名再跳一次
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabPager(
    items: List<TabPagerItem>,
    requestedPage: Int? = null,
    onRequestHandled: () -> Unit = {},
) {
    val pagerSate = rememberPagerState(
        // 冷启动（App 没在跑）直接停在对的 tab，不会闪一下再跳
        initialPage = requestedPage ?: 0,
        initialPageOffsetFraction = 0f,
        pageCount = { items.size },
    )

    // ⚠️ 热路径：App 已经在前台时（用户点了组件的第二条条目），NavHost 与这个 Pager
    // 都还活着，`initialPage` 不会再生效 —— 必须用副作用响应**变化**。
    LaunchedEffect(requestedPage) {
        if (requestedPage == null) return@LaunchedEffect
        if (requestedPage in items.indices && pagerSate.currentPage != requestedPage) {
            pagerSate.animateScrollToPage(requestedPage)
        }
        onRequestHandled()
    }

    val scope = rememberCoroutineScope() //供动画调用协程

    Column {
        ScheduleTabRow(
            titles = items.map { it.title },
            selectedIndex = pagerSate.currentPage,
            onSelect = { index ->
                scope.launch { pagerSate.animateScrollToPage(index) }
            },
        )

        //禁用overscroll阴影效果
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            HorizontalPager(
                state = pagerSate,
            ) { index ->
                CompositionLocalProvider(LocalOverscrollConfiguration provides OverscrollConfiguration()) {
                    items[index].content(pagerSate.currentPage == index)
                }
            }
        }
    }
}


/**
 * 课表页自己的页签栏。
 *
 * ## 为什么不用 Material3 的 `TabRow` + `Tab`
 *
 * `Tab` 会在标签左右各留约 **16dp** 内边距（社区多次反馈无法去掉），再叠加我们自己的
 * 5dp，五个页签分下来每个只剩不到 50dp；**系统字体放大一点就整个挤成省略号** ——
 * 实测用户机器（fontScale ≈ 1.3）上「课表」「空教室」等**五个标签全部显示成 `…`**。
 *
 * 自己排一行等宽格子：宽度完全可控；再加一层「放不下就降一档字号」的兜底，
 * 字体放得再大也不会退化成 `…`。
 *
 * ⚠️ 若要改回 `TabRow`，先想清楚上面这条 —— 这不是审美问题，是**字根本显示不出来**。
 */
@Composable
private fun ScheduleTabRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    var rowWidthPx by remember { mutableStateOf(0) }
    val tabWidthPx = if (titles.isEmpty()) 0f else rowWidthPx.toFloat() / titles.size

    // 指示器自己算位置：等宽格子 × 下标（不再依赖 TabRow 的 tabPositions）
    val indicatorOffset by animateDpAsState(
        targetValue = with(density) { (tabWidthPx * selectedIndex).toDp() },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 350f),
        label = "scheduleTabIndicator",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { rowWidthPx = it.width },
    ) {
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .padding(horizontal = 3.dp, vertical = 5.dp)
                    .width(with(density) { tabWidthPx.toDp() } - 6.dp)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            titles.forEachIndexed { index, title ->
                ScheduleTab(
                    title = title,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 单个页签。两处细节都是为了「字显示全」：
 *
 * 1. 只留 2dp 横向内边距（Material3 的 `Tab` 留的是 16dp × 2）；
 * 2. 一旦放不下就**自动降一档字号**（Compose 1.6 没有 autoSize，用 `onTextLayout`
 *    的 `hasVisualOverflow` 自己兜）。降档是**单向**的，不会来回抖。
 */
@Composable
private fun ScheduleTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shrunk by remember(title) { mutableStateOf(false) }

    Box(
        modifier = modifier.clickable(
            // 沿用原来的意图：不要水波纹
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = if (shrunk) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // ⚠️ 只单向降档：若写成 `shrunk = it.hasVisualOverflow`，小字号放得下时
            //    会把它置回 false → 又变大 → 又溢出，**无限重组**。
            onTextLayout = { result -> if (result.hasVisualOverflow) shrunk = true },
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TestTabPager() {
    val items = listOf(TabPagerItem("Tab1") {
        Text("Tab1", modifier = Modifier.fillMaxSize())
    }, TabPagerItem("Tab2") {
        Text("Tab2", modifier = Modifier.fillMaxSize())
    })
    TabPager(items)
}