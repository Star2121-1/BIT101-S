package cn.bit101.android.features.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData

/**
 * 把 [WidgetData] 渲染成 [RemoteViews]。
 *
 * **为什么用传统 RemoteViews 而不是 Glance**（2026-09-21 的重要教训）：
 *
 * 先用 Glance 1.1.0 实现过一版，点击页签时**画面不更新**。逐层排查后确认：
 * - 点击回调**执行了**、页号**写入了**（直接读存储文件可验证）
 * - 但 Glance 的 `update()` 只是 `sessionManager.runWithLock { ... session.updateGlance() }`，
 *   而 `AppWidgetSession.updateGlance()` 只做 `sendEvent(UpdateGlanceState)`，
 *   真正的重绘交给**异步的 Session 事件循环**（跑在 WorkManager 的 SessionWorker 里）。
 *   对**已经存在的 Session**，这条路径不保证重新执行 `provideGlance`。
 * - 日志证据：点击后 `update()` 返回 ok、`SessionWorker` 也 SUCCESS，
 *   但 `provideGlance` 的日志**一次都没出现** —— 渲染压根没发生。
 * - 升级到 Glance 1.1.1 同样无效。
 *
 * RemoteViews 方案没有这层异步：在广播回调里构建好 RemoteViews，
 * 直接 `AppWidgetManager.updateAppWidget()` —— **同步 API，调用即生效**。
 */
internal object WidgetViews {

    /** 页签 id，顺序与 [PageKind.entries] 一致 */
    private val TAB_IDS = intArrayOf(R.id.tab_0, R.id.tab_1, R.id.tab_2)

    /** 内容区行数上限（与 `widget_root.xml` 里的行数一致） */
    const val MAX_ROWS = 3

    /**
     * 组件当前该显示几行 —— 由它的**实际高度**决定。
     *
     * 判定规则是纯逻辑，放在 [WidgetLogic.rowsForHeight] 里以便单测
     * （这里只负责把系统给的尺寸读出来）。
     *
     * ## 为什么读 `OPTION_APPWIDGET_MIN_HEIGHT`
     *
     * 实测（2026-09-21，Pixel 6 API 34 + Pixel Launcher，日志已验证）：
     * 4×3 组件在屏上的实际高度是 **343dp**，而系统上报的是
     * `MIN_HEIGHT=193` / `MAX_HEIGHT=342` —— 即 **MAX 才等于实际高度**，
     * MIN 是「用户能拖到的最小高度」这一侧的下界。
     *
     * 这里**故意仍取 MIN**：它是保守方向 —— 报小了最多少显示一行，
     * 报大了会让 3 行内容挤进矮组件里被裁掉。而 4×3 下 193dp 与 342dp
     * 都远超 3 行所需的 140dp 阈值，结果一致；用户把组件拖矮时 MIN 会随之下调，
     * 正好触发降档。
     *
     * ⚠️ 若将来要改成读 MAX（想在矮组件里也塞 3 行），必须先在真机上
     * 把组件拖到最小尺寸量一次，确认 3 行在最小高度下放得下（3 行约需 78dp）。
     */
    fun rowsForHeight(context: Context, appWidgetId: Int): Int {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        // 拿不到（返回 0）时交给 rowsForHeight 走「完整行数」分支
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        return WidgetLogic.rowsForHeight(heightDp, MAX_ROWS)
    }

    fun build(
        context: Context,
        appWidgetId: Int,
        data: WidgetData,
        rowLimit: Int = MAX_ROWS,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_root)

        val pages = data.pages
        val pageIndex = WidgetPageStore.read(context, appWidgetId)
            .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val page = pages.getOrNull(pageIndex)

        renderTabs(context, rv, appWidgetId, pages, pageIndex)
        rv.setOnClickPendingIntent(
            R.id.tab_refresh,
            refreshPendingIntent(context, appWidgetId),
        )
        renderBody(context, rv, appWidgetId, page, rowLimit)

        return rv
    }

    /** 每行用到的控件 id */
    private data class RowViews(
        @IdRes val row: Int,
        @IdRes val main: Int,
        @IdRes val time: Int,
        @IdRes val trail: Int,
    )

    private val ROWS = listOf(
        RowViews(R.id.row_0, R.id.row_0_main, R.id.row_0_time, R.id.row_0_trail),
        RowViews(R.id.row_1, R.id.row_1_main, R.id.row_1_time, R.id.row_1_trail),
        RowViews(R.id.row_2, R.id.row_2_main, R.id.row_2_time, R.id.row_2_trail),
    )

    // ------------------------------------------------------------------ 页签

    private fun renderTabs(
        context: Context,
        rv: RemoteViews,
        appWidgetId: Int,
        pages: List<WidgetPage>,
        pageIndex: Int,
    ) {
        TAB_IDS.forEachIndexed { i, id ->
            val title = pages.getOrNull(i)?.title ?: PageKind.entries[i].label
            val selected = i == pageIndex

            rv.setTextViewText(id, title)
            rv.setTextColor(
                id,
                color(
                    context,
                    if (selected) R.color.widget_tab_selected_text else R.color.widget_tab_text,
                ),
            )
            // 选中态用圆角底色区分。RemoteViews 没有 setBackgroundResource，
            // 要借 setInt(viewId, "setBackgroundResource", resId) 走反射式调用。
            rv.setInt(
                id,
                "setBackgroundResource",
                if (selected) R.drawable.widget_tab_selected else R.drawable.widget_tab_normal,
            )
            rv.setOnClickPendingIntent(id, pagePendingIntent(context, appWidgetId, i))
        }
    }

    // ------------------------------------------------------------------ 内容

    private fun renderBody(
        context: Context,
        rv: RemoteViews,
        appWidgetId: Int,
        page: WidgetPage?,
        rowLimit: Int,
    ) {
        val lines = buildList {
            page?.primary?.let { add(it to true) }
            page?.secondary.orEmpty().forEach { add(it to false) }
        }.take(rowLimit.coerceAtLeast(1))

        // 「立即预约」只在**有余位**时出现：它排在内容行下面，
        // 行数占满时硬塞会被裁掉半截，不如不显示（用户仍可点页签进 App）。
        val showAction = page?.kind == PageKind.SEAT && lines.size < rowLimit

        if (lines.isEmpty()) {
            // 4×2 高度下「暂无预约」+ 按钮会超出可视区，此时**让位给按钮** ——
            // 能一键去抢座比多显示那四个字有用
            val compact = showAction && rowLimit < MAX_ROWS
            rv.setViewVisibility(R.id.widget_empty, if (compact) View.GONE else View.VISIBLE)
            rv.setTextViewText(R.id.widget_empty, page?.emptyText ?: "暂无内容")
            rv.setViewVisibility(R.id.widget_rows, View.GONE)
            bindAction(context, rv, appWidgetId, showAction)
            return
        }

        rv.setViewVisibility(R.id.widget_empty, View.GONE)
        rv.setViewVisibility(R.id.widget_rows, View.VISIBLE)
        bindAction(context, rv, appWidgetId, showAction)

        ROWS.forEachIndexed { i, row ->
            val line = lines.getOrNull(i)
            if (line == null) {
                rv.setViewVisibility(row.row, View.GONE)
                return@forEachIndexed
            }
            val (widgetLine, isPrimary) = line
            val emphasize = isPrimary || widgetLine.highlight != null

            rv.setViewVisibility(row.row, View.VISIBLE)
            rv.setTextViewText(row.main, lineText(context, widgetLine, emphasize))
            rv.setTextViewTextSize(
                row.main,
                TypedValue.COMPLEX_UNIT_SP,
                if (emphasize) 16f else 14f,
            )
            rv.setTextColor(row.main, bodyColor(context, widgetLine))

            rv.setTextViewText(row.time, widgetLine.time)
            rv.setTextColor(row.time, timeColor(context, widgetLine))

            rv.setTextViewText(row.trail, widgetLine.trail)
        }
    }

    /** 绑定「立即预约」键。显示与否由 [show] 决定（没有余位时隐藏）。 */
    private fun bindAction(
        context: Context,
        rv: RemoteViews,
        appWidgetId: Int,
        show: Boolean,
    ) {
        rv.setViewVisibility(R.id.widget_action, if (show) View.VISIBLE else View.GONE)
        if (!show) return
        rv.setTextViewText(R.id.widget_action, "立即预约")
        rv.setOnClickPendingIntent(
            R.id.widget_action,
            reservePendingIntent(context, appWidgetId),
        )
    }

    /**
     * 行文本：`▶ 标记  正文`。
     *
     * 高亮的行（正在上课 / 马上要上）在最前面加 `▶` —— 组件是"扫一眼"看的东西，
     * 光靠颜色在某些壁纸上不够醒目，加个符号更直接。
     */
    private fun lineText(
        context: Context,
        line: WidgetLine,
        emphasize: Boolean,
    ): CharSequence {
        val body = bodyColor(context, line)
        val mark = if (line.highlight != null) "▶ " else ""

        if (line.lead.isBlank()) {
            val s = SpannableString("$mark${line.main}")
            s.setSpan(ForegroundColorSpan(body), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (emphasize) {
                s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return s
        }

        val text = "$mark${line.lead}  ${line.main}"
        val s = SpannableString(text)
        val bodyStart = mark.length + line.lead.length

        if (mark.isNotEmpty()) {
            // `▶` 跟着状态色，让它和课程名连成一体
            s.setSpan(ForegroundColorSpan(body), 0, mark.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // 节次用强调色，课程名用状态色
        s.setSpan(
            ForegroundColorSpan(color(context, R.color.widget_accent)),
            mark.length,
            bodyStart,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        s.setSpan(
            ForegroundColorSpan(body),
            bodyStart,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (emphasize) {
            s.setSpan(StyleSpan(Typeface.BOLD), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** 正文颜色：正在上课绿、马上要上橙、DDL 紧急红、其余正文色。 */
    @ColorRes
    private fun bodyColorRes(line: WidgetLine): Int = when {
        line.highlight == ClassState.ONGOING -> R.color.widget_class_ongoing
        line.highlight == ClassState.UPCOMING -> R.color.widget_class_upcoming
        line.urgent -> R.color.widget_urgent
        else -> R.color.widget_text_primary
    }

    private fun bodyColor(context: Context, line: WidgetLine) =
        color(context, bodyColorRes(line))

    /** 时间列：跟着状态走色，普通行用次要色。 */
    private fun timeColor(context: Context, line: WidgetLine) =
        color(
            context,
            if (line.highlight != null) bodyColorRes(line) else R.color.widget_text_secondary,
        )

    // ------------------------------------------------------------ PendingIntent

    /**
     * 页签点击。
     *
     * ⚠️⚠️ **`data` 必须唯一**（appWidgetId + page 组合）。
     * `PendingIntent` 的唯一性只看 `requestCode` + `Intent.filterEquals`，
     * 而 **filterEquals 不比较 extras** —— 只靠 extras 区分的话，
     * 几个页签会判定为同一个 PendingIntent，被 `FLAG_UPDATE_CURRENT` 互相覆盖，
     * 表现就是「只有最后一个页签能用」。
     */
    private fun pagePendingIntent(
        context: Context,
        appWidgetId: Int,
        page: Int,
    ): PendingIntent = broadcast(
        context,
        appWidgetId,
        BIT101WidgetProvider.ACTION_SET_PAGE,
        Uri.parse("bit101://page/$appWidgetId/$page"),
    ) { putExtra(BIT101WidgetProvider.EXTRA_PAGE, page) }

    private fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
        broadcast(
            context,
            appWidgetId,
            BIT101WidgetProvider.ACTION_REFRESH,
            Uri.parse("bit101://refresh/$appWidgetId"),
        )

    /**
     * 座位页的「立即预约」：打开 App 并直接落在座位页。
     *
     * ⚠️ 用**类名字符串**而不是 `MainActivity::class.java`：
     * `MainActivity` 在 `:features` 聚合模块，而 `:features` 又依赖 `:features:widget`，
     * widget 直接引用它会形成循环依赖。`setClassName` 用运行时包名，
     * debug 变体（`…android.debug`）也能落到同一个类上。
     *
     * 目标页取 [toPageData] 的 route（`"seat"`），与 `PageShowOnNav` 保持一致，
     * 两边不用各写一份魔法字符串；extra 的 key 与 `MainActivity.EXTRA_GOTO` 相同。
     */
    private fun reservePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            putExtra(GOTO_EXTRA, PageShowOnNav.Seat.toPageData().value)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bit101://goto/seat/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private inline fun broadcast(
        context: Context,
        appWidgetId: Int,
        action: String,
        data: Uri,
        extra: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, BIT101WidgetProvider::class.java).apply {
            this.action = action
            this.data = data
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            extra()
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun color(context: Context, @ColorRes resId: Int) =
        ContextCompat.getColor(context, resId)

    /** 与 `MainActivity.EXTRA_GOTO` 保持一致（跨模块，注释互相指向）。 */
    private const val GOTO_EXTRA = "bit101_goto"

    /** `cn.bit101.android.features.MainActivity` —— 见 [reservePendingIntent] 的说明。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
}
