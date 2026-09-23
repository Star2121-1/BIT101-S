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

    /** 页签 id，顺序与 [PageKind.entries] 一致（课程 / DDL / 座位 / 动态） */
    private val TAB_IDS = intArrayOf(R.id.tab_0, R.id.tab_1, R.id.tab_2, R.id.tab_3)

    fun build(
        context: Context,
        appWidgetId: Int,
        data: WidgetData,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_root)

        val pages = data.pages
        // ⚠️ 按**页名**找当前页，不是按索引：页序调整后老用户的记录不会串页
        //（历史 int 页号由 WidgetPageStore 迁移成页名）。
        val currentKind = WidgetPageStore.read(context, appWidgetId)
        val pageIndex = pages.indexOfFirst { it.kind == currentKind }.takeIf { it >= 0 } ?: 0
        val page = pages.getOrNull(pageIndex)

        renderTabs(context, rv, appWidgetId, pages, pageIndex)
        rv.setOnClickPendingIntent(
            R.id.tab_refresh,
            refreshPendingIntent(context, appWidgetId),
        )
        renderFooter(rv, page)
        renderBody(context, rv, appWidgetId, page)

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
            val kind = pages.getOrNull(i)?.kind ?: PageKind.entries[i]
            val title = pages.getOrNull(i)?.title ?: kind.label
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
            rv.setOnClickPendingIntent(id, pagePendingIntent(context, appWidgetId, kind))
        }
    }

    // ------------------------------------------------------------------ 内容

    private fun renderFooter(rv: RemoteViews, page: WidgetPage?) {
        val text = page?.footer
        rv.setViewVisibility(R.id.widget_footer, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
        if (!text.isNullOrBlank()) rv.setTextViewText(R.id.widget_footer, text)
    }

    private fun renderBody(
        context: Context,
        rv: RemoteViews,
        appWidgetId: Int,
        page: WidgetPage?,
    ) {
        // 动作键（登录 / 立即预约）由纯逻辑决定，这里只负责画
        val action = page?.let { WidgetLogic.actionOf(it) }
        bindAction(context, rv, appWidgetId, action)

        // 列表的 adapter **总是**绑上：即便此刻是空态，切页签后也要能立刻显示内容，
        // 而 adapter 只能随 RemoteViews 一起下发。
        rv.setRemoteAdapter(appWidgetId, R.id.widget_list, listAdapterIntent(context, appWidgetId))

        val prompt = page?.loginPrompt
        if (prompt != null) {
            // 未登录 → 整页换成「未登录 + 登录按钮」。
            // 本地库里的数据可能是几天前同步的，会话已失效时继续展示会让人以为数据是新的。
            showEmpty(rv, prompt)
            return
        }
        if (page == null || page.items.isEmpty()) {
            showEmpty(rv, page?.emptyText ?: "暂无内容")
            return
        }

        rv.setViewVisibility(R.id.widget_empty, View.GONE)
        rv.setViewVisibility(R.id.widget_list, View.VISIBLE)
        // 直接定位到「现在」所处的时段（课程页）；其余页从头显示
        rv.setScrollPosition(R.id.widget_list, page.scrollTo.coerceAtLeast(0))

        // 列表条目的点击模板（collection 的标准做法）：模板在这里挂一次，
        // 条目里用 setOnClickFillInIntent 补 extras —— 只有带 fillInIntent 的条目可点。
        //
        // ⚠️ 一个 collection **只能挂一个模板**，所以「整行跳转」与「整行勾选」
        // 没法按视图分流。当前统一是**打开 App**（用户 2026-09-23 要求：
        // 点 DDL / 动态条目要跳进 App 对应页）。`ddlToggleTemplate` 保留给将来的
        // 「勾选模式」（点动作键把整页切成勾选语义），暂时没有调用方。
        rv.setPendingIntentTemplate(R.id.widget_list, listTapTemplate(context, appWidgetId))
    }

    /**
     * 列表条目的点击模板：打开 App 并按条目自己的 [WidgetLine.openRoute] 跳页。
     * extras 由条目的 fillInIntent 提供，没有 fillInIntent 的条目点了没反应。
     *
     * ⚠️⚠️ **必须 `FLAG_MUTABLE`** —— collection 的 fill-in 机制是**宿主（桌面）**
     * 拿到模板 PendingIntent 后调 `send(context, code, fillInIntent)` 把 extras 合进去；
     * Android 12 起对**不可变**的 PendingIntent 这样做会直接抛
     * `IllegalArgumentException`（Attempt to invoke PendingIntent.send() with a
     * fillInIntent on an immutable PendingIntent），异常被宿主吞掉 ——
     * 表现就是**点条目毫无反应**（2026-09-23 模拟器实测：点 DDL 行后数据库没变）。
     * 页签 / 刷新 / 动作键那些**不带 fill-in** 的仍然保持不可变。
     *
     * ⚠️ `data` 里的 `list2` 是**刻意换过的**：旧版本用 `…/list/…` 创建过**不可变**的
     * 同 key PendingIntent，而系统不允许「同一 key 既有可变又有不可变」的记录共存
     * （会抛 "Cannot create both immutable and mutable PendingIntents with the same key"）。
     * 换 URI 让 key 不同，升级路径上不必依赖系统是否清理过旧记录。
     */
    private fun listTapTemplate(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bit101://goto/list2/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * DDL 行的点击模板：**在组件里直接勾选/取消**（广播回 [BIT101WidgetProvider] 写库）。
     *
     * ⚠️ **当前没有调用方**（2026-09-23 起条目点击改为「跳进 App 的 DDL 页」）。
     * 保留它是为了将来的**「勾选模式」**：点动作键把整页切换成勾选语义、再点回到跳转。
     * 之所以不能两者共存：一个 collection 只能挂一个点击模板（见 `renderBody` 的说明）。
     *
     * 数据（`WidgetLine.toggleDdlUid`）也一直带着，接上模板即可用。
     */
    @Suppress("unused")
    private fun ddlToggleTemplate(context: Context, appWidgetId: Int): PendingIntent =
        broadcast(
            context,
            appWidgetId,
            BIT101WidgetProvider.ACTION_ITEM_TAP,
            Uri.parse("bit101://ddl-toggle2/$appWidgetId"),
            mutable = true,
        )

    private fun showEmpty(rv: RemoteViews, text: String) {
        rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        rv.setTextViewText(R.id.widget_empty, text)
        rv.setViewVisibility(R.id.widget_list, View.GONE)
    }

    /** 绑定底部动作键；显示与否由 [action] 决定（文案与跳转目标也在里面）。 */
    private fun bindAction(
        context: Context,
        rv: RemoteViews,
        appWidgetId: Int,
        action: WidgetAction?,
    ) {
        rv.setViewVisibility(R.id.widget_action, if (action != null) View.VISIBLE else View.GONE)
        if (action == null) return
        rv.setTextViewText(R.id.widget_action, action.label)
        rv.setOnClickPendingIntent(
            R.id.widget_action,
            // 「一键预约」走广播回到 Provider（在后台直接下单，不打开 App）；
            // 其余动作打开 App 并跳到对应页面。
            if (action.isQuickReserve) {
                quickReservePendingIntent(context, appWidgetId, action.quickReserveTaskId.orEmpty())
            } else {
                gotoPendingIntent(context, appWidgetId, action.route)
            },
        )
    }

    // ------------------------------------------------------------ 列表条目

    /**
     * 构建列表里的一条（由 [WidgetListService] 的 factory 逐条调用）。
     *
     * ⚠️ 这里必须用**固定色值**而不是主题属性：条目同样是在宿主（桌面）进程
     * inflate 的，取不到我们 App 的主题。
     */
    internal fun buildItem(context: Context, line: WidgetLine): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item)
        val emphasize = line.highlight != null

        // 栏目标题行（「未完成 · 3」）：只有一行小字，次要色、无第二行、不可点。
        // 分区让「我还有什么要交」一眼可见，而不是和已完成的混在一起。
        if (line.header) {
            rv.setTextViewText(R.id.item_main, line.main)
            rv.setTextColor(R.id.item_main, color(context, R.color.widget_text_secondary))
            rv.setTextViewTextSize(R.id.item_main, TypedValue.COMPLEX_UNIT_SP, 12f)
            rv.setViewVisibility(R.id.item_meta, View.GONE)
            rv.setInt(R.id.item_root, "setBackgroundResource", 0)
            return rv
        }

        rv.setTextViewText(R.id.item_main, lineText(context, line, emphasize))
        rv.setTextViewTextSize(
            R.id.item_main,
            TypedValue.COMPLEX_UNIT_SP,
            if (emphasize) 16f else 15f,
        )
        // 当前时段给一层淡色块底 —— 光靠文字色在深色壁纸上不够醒目
        rv.setInt(
            R.id.item_root,
            "setBackgroundResource",
            if (emphasize) R.drawable.widget_item_highlight else 0,
        )

        // 第 2 行（时间 + 地点，靠右）：两者都空时整行隐藏，否则留一条空行白占高度
        val hasMeta = line.time.isNotBlank() || line.trail.isNotBlank()
        rv.setViewVisibility(R.id.item_meta, if (hasMeta) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.item_time, line.time)
        rv.setTextColor(R.id.item_time, timeColor(context, line))
        rv.setTextViewText(R.id.item_trail, line.trail)

        // 条目的点击语义（extras 经列表的 PendingIntentTemplate 合并后投递）：
        // - 打开 App 并跳到某页：openRoute（+ openTab 停在第几个 tab、focusKey 定位到哪一条）
        // - 在组件里勾选 DDL：toggleDdlUid（当前未接线，留给将来的「勾选模式」）
        //
        // ⚠️⚠️ **必须合成一个 fillInIntent**：`setOnClickFillInIntent` 对同一 view 是
        // **覆盖**语义，连着调两次只会留下最后一次 —— 早先分开写两段时，
        // DDL 行（两个字段都有）会把 openRoute 丢掉，点了变成「勾选」而不是跳转。
        val clickable = line.openRoute != null || line.toggleDdlUid != null
        if (clickable) {
            rv.setOnClickFillInIntent(
                R.id.item_root,
                Intent().apply {
                    line.openRoute?.let { putExtra(GOTO_EXTRA, it) }
                    line.openTab?.let { putExtra(TAB_EXTRA, it) }
                    line.focusKey?.let { putExtra(FOCUS_EXTRA, it) }
                    line.toggleDdlUid?.let { putExtra(DDL_UID_EXTRA, it) }
                },
            )
        }
        return rv
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

    /** 正文颜色：正在上课绿、马上要上橙、DDL 紧急红、空闲次要色、其余正文色。 */
    @ColorRes
    private fun bodyColorRes(line: WidgetLine): Int = when {
        line.highlight == ClassState.ONGOING -> R.color.widget_class_ongoing
        line.highlight == ClassState.UPCOMING -> R.color.widget_class_upcoming
        line.urgent -> R.color.widget_urgent
        line.muted -> R.color.widget_text_secondary
        else -> R.color.widget_text_primary
    }

    private fun bodyColor(context: Context, line: WidgetLine) =
        color(context, bodyColorRes(line))

    /** 时间列：跟着状态走色，普通行与空闲行用次要色。 */
    private fun timeColor(context: Context, line: WidgetLine) =
        color(
            context,
            if (line.highlight != null) bodyColorRes(line) else R.color.widget_text_secondary,
        )

    // ------------------------------------------------------------ PendingIntent

    /**
     * 列表的 adapter Intent。
     *
     * ⚠️ 必须把 appWidgetId 放进 extras **并且**用 `toUri()` 生成唯一的 `data`：
     * - extras 里带 id，factory 才知道该按哪个组件的页签取数据
     * - `data` 不同，`filterEquals` 才判定为不同 Intent —— 否则多个组件实例会
     *   共用同一个 RemoteViewsFactory，出现「两个组件内容一模一样」的怪象
     *   （`toUri(URI_INTENT_SCHEME)` 会把 int extras 编码进 URI，正好满足这一点）
     */
    private fun listAdapterIntent(context: Context, appWidgetId: Int): Intent =
        Intent(context, WidgetListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    /**
     * 页签点击。
     *
     * ⚠️⚠️ **`data` 必须唯一**（appWidgetId + 页名组合）。
     * `PendingIntent` 的唯一性只看 `requestCode` + `Intent.filterEquals`，
     * 而 **filterEquals 不比较 extras** —— 只靠 extras 区分的话，
     * 几个页签会判定为同一个 PendingIntent，被 `FLAG_UPDATE_CURRENT` 互相覆盖，
     * 表现就是「只有最后一个页签能用」。
     *
     * ⚠️ extra 里带的是**页名**（`PageKind.name`）而不是第几页 —— 页序调整后
     * 老 PendingIntent 也不会把用户带到错的页上（见 `WidgetPageStore`）。
     */
    private fun pagePendingIntent(
        context: Context,
        appWidgetId: Int,
        kind: PageKind,
    ): PendingIntent = broadcast(
        context,
        appWidgetId,
        BIT101WidgetProvider.ACTION_SET_PAGE,
        Uri.parse("bit101://page/$appWidgetId/${kind.name}"),
    ) { putExtra(BIT101WidgetProvider.EXTRA_PAGE, kind.name) }

    private fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
        broadcast(
            context,
            appWidgetId,
            BIT101WidgetProvider.ACTION_REFRESH,
            Uri.parse("bit101://refresh/$appWidgetId"),
        )

    /**
     * 「一键预约」：广播回 Provider，由座位模块的 [SeatWidgetBridge] 在后台直接下单。
     *
     * ⚠️ `data` 必须含 taskId：PendingIntent 唯一性不比 extras（见 [pagePendingIntent]），
     * 否则不同任务的一键预约会互相覆盖成同一个。
     */
    private fun quickReservePendingIntent(
        context: Context,
        appWidgetId: Int,
        taskId: String,
    ): PendingIntent = broadcast(
        context,
        appWidgetId,
        BIT101WidgetProvider.ACTION_QUICK_RESERVE,
        Uri.parse("bit101://quick-reserve/$appWidgetId/$taskId"),
    ) { putExtra(BIT101WidgetProvider.EXTRA_TASK_ID, taskId) }

    /**
     * 打开 App 并直接落在 [route] 指定的页面（`"seat"` / `"login"` 等）。
     *
     * ⚠️ 用**类名字符串**而不是 `MainActivity::class.java`：
     * `MainActivity` 在 `:features` 聚合模块，而 `:features` 又依赖 `:features:widget`，
     * widget 直接引用它会形成循环依赖。`setClassName` 用运行时包名，
     * debug 变体（`…android.debug`）也能落到同一个类上。
     *
     * ⚠️ `data` 里必须带 [route]：PendingIntent 的唯一性只看 `requestCode` +
     * `filterEquals`，**不比 extras** —— 只靠 extras 区分目标的话，
     * 「登录」和「立即预约」会被判成同一个 PendingIntent 互相覆盖。
     *
     * extra 的 key 与 `MainActivity.EXTRA_GOTO` 相同；页面路由与 `PageShowOnNav`
     * 同源、登录路由用 `AppRoutes.LOGIN`，两边不用各写一份魔法字符串。
     */
    private fun gotoPendingIntent(
        context: Context,
        appWidgetId: Int,
        route: String,
    ): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            putExtra(GOTO_EXTRA, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bit101://goto/$route/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 组件内部广播的通用构造。
     *
     * @param mutable 只有**要接 fill-in extras 的列表模板**才需要 true（见 [listTapTemplate]）；
     *   页签 / 刷新这类固定动作保持 `FLAG_IMMUTABLE` —— 不接外部填充，越不可变越安全。
     */
    private inline fun broadcast(
        context: Context,
        appWidgetId: Int,
        action: String,
        data: Uri,
        mutable: Boolean = false,
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
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun color(context: Context, @ColorRes resId: Int) =
        ContextCompat.getColor(context, resId)

    /** 与 `MainActivity.EXTRA_GOTO` 保持一致（跨模块，注释互相指向）。 */
    private const val GOTO_EXTRA = "bit101_goto"

    /** 与 `MainActivity.EXTRA_TAB` 保持一致：打开 App 后停在第几个 tab。 */
    private const val TAB_EXTRA = "bit101_tab"

    /** 与 `MainActivity.EXTRA_FOCUS` 保持一致：定位到哪一条（DDL uid / 动态 id）。 */
    private const val FOCUS_EXTRA = "bit101_focus"

    /** DDL 行 fillInIntent 里的 uid —— Provider 据此知道要切换哪一条。 */
    const val DDL_UID_EXTRA = "bit101_ddl_uid"

    /** `cn.bit101.android.features.MainActivity` —— 见 [gotoPendingIntent] 的说明。 */
    private const val MAIN_ACTIVITY_CLASS = "cn.bit101.android.features.MainActivity"
}
