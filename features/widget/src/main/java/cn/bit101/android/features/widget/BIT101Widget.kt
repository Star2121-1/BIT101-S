package cn.bit101.android.features.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * BIT101 桌面小组件。
 *
 * 三页轮播：[PageKind.COURSE]（默认）→ [PageKind.DDL] → [PageKind.SEAT]。
 *
 * ⚠️ 实现约束（动手前必读）：
 * 1. **Glance 没有 Pager / HorizontalPager**。横向滑动会被桌面的手势吃掉，
 *    所以翻页用**右上角 `‹ ›` 按钮 + 页面指示点**。页号存在
 *    `AppWidgetState`（每个组件实例独立，跨重绘持久）。
 * 2. **Glance 组件不能跑任意 Compose UI** —— 只能用 `androidx.glance.*` 的
 *    Modifier 和 `Text` / `Row` / `Column`。没有 `Icon`，
 *    所以指示点用 `● ○` 字符、翻页用 `‹ ›` 字符，省一份矢量资源。
 * 3. **点击事件**只有 `actionRunCallback`（跑 [ActionCallback]）与
 *    `actionStartActivity`（拉起页面）两种。
 * 4. ⚠️ **`getAppWidgetState` 必须在 `provideGlance` 之外调用**才能改页号 ——
 *    在 `provideContent` 里读到的状态是渲染那一刻的快照。
 */
class BIT101Widget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 80.dp),
            DpSize(180.dp, 110.dp),
            DpSize(260.dp, 110.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 先取页号与数据，再进 provideContent —— 两者都是 suspend，不能写在 composable 里。
        //
        // ⚠️ Glance 1.1.0 的 state API 有两组同名重载，很容易调错：
        //   1. 顶层 fun <T> getAppWidgetState(context, definition, glanceId): T   ← 用这个
        //   2. GlanceAppWidget 的扩展 getAppWidgetState(context, glanceId): T
        //      —— 少了 definition，返回类型是**未约束的泛型 T**，编译器只会报
        //      「Cannot infer type for type parameter T」，看不出根因。
        //    所以这里显式写出 definition + 显式声明返回类型，两条都能少踩坑。
        val prefs: Preferences = getAppWidgetState(
            context,
            PreferencesGlanceStateDefinition,
            id,
        )
        val currentPage = prefs[PAGE_KEY] ?: 0

        val data = WidgetRepositoryHolder.load(context)
        val pageCount = data.pages.size.coerceAtLeast(1)

        // 页数变了（理论上不会，但防御一下）：把页号夹回合法范围，
        // 否则用户看到的是空白页且翻页按钮行为反常
        val safePage = if (currentPage in 0 until pageCount) currentPage else 0

        provideContent {
            GlanceTheme {
                WidgetContent(data = data, pageIndex = safePage)
            }
        }
    }

    companion object {
        /** 当前显示第几页。每个组件实例各存一份。 */
        val PAGE_KEY = intPreferencesKey("widget_current_page")
    }
}

/** 系统的组件广播接收器。 */
class BIT101WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BIT101Widget()
}

/**
 * 让 `provideGlance` 能拿到 [WidgetRepository]。
 *
 * `GlanceAppWidget` 由系统实例化，**不经过 Hilt 注入**，
 * 所以由 [WidgetAppStartup] 在 App 启动时把实例塞进来。
 */
internal object WidgetRepositoryHolder {

    @Volatile
    private var repository: WidgetRepository? = null

    fun install(repo: WidgetRepository) {
        repository = repo
    }

    /** 仓库未就绪时返回 null（[WidgetUpdater] 据此跳过刷新而不是崩掉）。 */
    fun repositoryOrNull(): WidgetRepository? = repository

    /**
     * 取数据。仓库未就绪（组件先于 App 被拉起、Hilt 尚未初始化）时返回空数据 ——
     * 组件显示「加载中…」，等下次重绘即可，**绝不抛异常**（抛了系统会显示一片空白）。
     */
    suspend fun load(context: Context): WidgetData =
        runCatching { repository?.load() }.getOrNull() ?: loadingWidgetData()

    private fun loadingWidgetData() = WidgetData(
        pages = PageKind.entries.map { kind ->
            WidgetPage(kind = kind, title = kind.label, primary = null, emptyText = "加载中…")
        }
    )
}

/** 翻到下一页（循环）。 */
class NextPageAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        shiftPage(context, glanceId, +1)
    }
}

/** 翻到上一页（循环）。 */
class PrevPageAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        shiftPage(context, glanceId, -1)
    }
}

/**
 * 翻页并触发重绘。
 *
 * ⚠️ 页数从**当前数据**推算**而不是从 Glance state 读** ——
 * `ActionCallback.onAction` 只拿到 `Context` 与 `GlanceId`，没有 `GlanceAppWidget`
 * 实例，用不了 receiver 版的 `getAppWidgetState`。从数据反推更直接，
 * 取不到时按 [PageKind] 数量兜底。
 */
private suspend fun shiftPage(context: Context, glanceId: GlanceId, delta: Int) {
    val pageCount = runCatching {
        WidgetRepositoryHolder.load(context).pages.size
    }.getOrDefault(PageKind.entries.size).coerceAtLeast(1)

    // 写 state 用**顶层**三参数重载：
    //   updateAppWidgetState(context, glanceId) { MutablePreferences -> Unit }
    // 不要用带 GlanceStateDefinition 的那组 —— 它要求整读整写返回新对象，
    // 容易把别人的 key 覆盖掉。
    updateAppWidgetState(context, glanceId) { prefs ->
        val current = prefs[BIT101Widget.PAGE_KEY] ?: 0
        // Kotlin 的 % 对负数返回负数，必须先 +pageCount 再取模才能循环
        prefs[BIT101Widget.PAGE_KEY] = ((current + delta) % pageCount + pageCount) % pageCount
    }
    BIT101Widget().update(context, glanceId)
}

// ---------------------------------------------------------------------------
// 渲染层
// ---------------------------------------------------------------------------

@Composable
private fun WidgetContent(data: WidgetData, pageIndex: Int) {
    val pages = data.pages
    if (pages.isEmpty()) {
        CenteredText("暂无内容")
        return
    }

    val index = pageIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp),
    ) {
        PageHeader(page = page, pageIndex = index, pageCount = pages.size)
        Spacer(GlanceModifier.height(6.dp))
        PageBody(page)
    }
}

@Composable
private fun CenteredText(text: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface))
    }
}

/** 顶栏：`● ○ ○  课程   ‹ ›` —— 指示点 + 当前页名 + 翻页按钮。 */
@Composable
private fun PageHeader(page: WidgetPage, pageIndex: Int, pageCount: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                repeat(pageCount) { i -> append(if (i == pageIndex) "●" else "○") }
            },
            style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.primary),
        )
        Spacer(GlanceModifier.width(6.dp))

        Text(
            text = page.title,
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = GlanceTheme.colors.onSurface,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )

        // 只有一页时不给按钮 —— 点了没变化，徒增困惑
        if (pageCount > 1) {
            Text(
                text = "‹",
                style = TextStyle(fontSize = 16.sp, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<PrevPageAction>())
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = "›",
                style = TextStyle(fontSize = 16.sp, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<NextPageAction>())
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PageBody(page: WidgetPage) {
    if (page.isEmpty) {
        Text(
            text = page.emptyText,
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
        return
    }

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        page.primary?.let { LineRow(it, emphasize = true) }
        page.secondary.forEach { line ->
            Spacer(GlanceModifier.height(3.dp))
            LineRow(line, emphasize = false)
        }
    }
}

/** 一行内容：`[lead] main          trail`。`emphasize` 时主体加粗放大。 */
@Composable
private fun LineRow(line: WidgetLine, emphasize: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (line.lead.isNotBlank()) {
            Text(
                text = line.lead,
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier.padding(end = 6.dp),
            )
        }

        Text(
            text = line.main,
            maxLines = 1,
            style = TextStyle(
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
                fontSize = if (emphasize) 14.sp else 12.sp,
                color = if (line.urgent) {
                    ColorProvider(URGENT_COLOR)
                } else {
                    GlanceTheme.colors.onSurface
                },
            ),
            modifier = GlanceModifier.defaultWeight(),
        )

        if (line.trail.isNotBlank()) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = line.trail,
                maxLines = 1,
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/** 紧急色（DDL 已过期 / 24 小时内到期）。 */
private val URGENT_COLOR = Color(0xFFD3302F)
