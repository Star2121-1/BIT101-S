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
import androidx.core.content.ContextCompat

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

    /** 每行的 (main, trail) 控件 id */
    private val ROW_IDS = arrayOf(
        R.id.row_0 to (R.id.row_0_main to R.id.row_0_trail),
        R.id.row_1 to (R.id.row_1_main to R.id.row_1_trail),
        R.id.row_2 to (R.id.row_2_main to R.id.row_2_trail),
    )

    /** 组件一页最多显示几行（1 主 + 2 次），与布局里的行数一致 */
    private const val MAX_ROWS = 3

    fun build(context: Context, appWidgetId: Int, data: WidgetData): RemoteViews {
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
        renderBody(context, rv, page)

        return rv
    }

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

    private fun renderBody(context: Context, rv: RemoteViews, page: WidgetPage?) {
        val lines = buildList {
            page?.primary?.let { add(it to true) }
            page?.secondary.orEmpty().forEach { add(it to false) }
            // 主行 + 次行一起，最多 MAX_ROWS 行
        }.take(MAX_ROWS)

        if (lines.isEmpty()) {
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            rv.setTextViewText(
                R.id.widget_empty,
                page?.emptyText ?: "暂无内容",
            )
            rv.setViewVisibility(R.id.widget_rows, View.GONE)
            return
        }

        rv.setViewVisibility(R.id.widget_empty, View.GONE)
        rv.setViewVisibility(R.id.widget_rows, View.VISIBLE)

        ROW_IDS.forEachIndexed { i, (rowId, ids) ->
            val (mainId, trailId) = ids
            val line = lines.getOrNull(i)
            if (line == null) {
                rv.setViewVisibility(rowId, View.GONE)
                return@forEachIndexed
            }
            val (widgetLine, emphasize) = line
            rv.setViewVisibility(rowId, View.VISIBLE)
            rv.setTextViewText(mainId, lineText(context, widgetLine, emphasize))
            rv.setTextViewTextSize(
                mainId,
                TypedValue.COMPLEX_UNIT_SP,
                if (emphasize) 16f else 14f,
            )
            rv.setTextViewText(trailId, widgetLine.trail)
        }
    }

    /**
     * 行文本：`标记  正文`。
     *
     * 标记（节次 / DDL 分组）用强调色、正文用正文色（紧急时转红），
     * `emphasize` 时正文加粗 —— 层次靠颜色与字重拉开，组件里没法用字号以外的排版手段。
     */
    private fun lineText(context: Context, line: WidgetLine, emphasize: Boolean): CharSequence {
        val bodyColor = if (line.urgent) {
            R.color.widget_urgent
        } else {
            R.color.widget_text_primary
        }

        if (line.lead.isBlank()) {
            val s = SpannableString(line.main)
            if (emphasize) {
                s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            s.setSpan(
                ForegroundColorSpan(color(context, bodyColor)),
                0,
                s.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return s
        }

        val text = "${line.lead}  ${line.main}"
        val s = SpannableString(text)
        val leadEnd = line.lead.length
        s.setSpan(
            ForegroundColorSpan(color(context, R.color.widget_accent)),
            0,
            leadEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        s.setSpan(
            ForegroundColorSpan(color(context, bodyColor)),
            leadEnd,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (emphasize) {
            s.setSpan(
                StyleSpan(Typeface.BOLD),
                leadEnd,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return s
    }

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

    private fun color(context: Context, resId: Int) = ContextCompat.getColor(context, resId)
}
