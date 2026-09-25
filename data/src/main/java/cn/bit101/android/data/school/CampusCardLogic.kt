package cn.bit101.android.data.school

/**
 * 一卡通首页快照的**纯解析逻辑**（便于单测，网络在 Repo 层）。
 */
object CampusCardLogic {

    private const val SSO_HOST = "sso.bit.edu.cn"

    /**
     * 余额类字段启发式：`余额` / `过渡余额` 等 + 紧跟的金额。
     * 金额前的分隔符（HTML 标签、空白、冒号等）**不含数字**、长度限 16 ——
     * 太长会把「更新时间」之类的下一个数字误当成金额（金额要求带小数点，
     * 日期型如 `09-24` 不满足，所以实践中较稳）。
     * 标签按出现顺序去重。
     */
    private val ENTRY_REGEX = Regex(
        "(过渡余额|账户余额|余额|芯片余额)[^0-9]{0,16}([\\d,]+\\.\\d{1,2})"
    )

    /**
     * 解析首页响应。
     *
     * ⚠️ **「未登录」只看最终 URL**：未登录时服务端 302 到 CAS（`finalUrl` 主机是
     * `sso.bit.edu.cn`）。**不能按 HTML 关键词判** —— 首页正文本身含「统一身份认证」
     * 文案，v1.8.0 因此把已登录页误判成未登录（卡片永远显示「点开登录」）。
     *
     * @param html 首页响应体（可能为空 = 网络失败）
     * @param finalUrl 跟随重定向之后的最终地址
     */
    fun parse(html: String, finalUrl: String): CampusCardSnapshot {
        val redirectedToSso = runCatching { java.net.URI(finalUrl).host }
            .getOrNull()
            ?.endsWith(SSO_HOST) == true

        val loggedIn = !redirectedToSso && html.isNotEmpty()

        // 同一金额可能被模板渲染两遍（桌面端 + 移动端两段），按标签去重
        val entries = ENTRY_REGEX.findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .distinctBy { it.first }

        return CampusCardSnapshot(
            entries = entries,
            htmlSnippet = html.take(1200),
            loggedIn = loggedIn,
        )
    }

    /**
     * 余额摘要文案（「我」页卡片与详情页共用；纯函数，可单测）。
     *
     * [notLoggedIn] 要按场景给：「我」页卡片说「进详情页登录」，详情页自己说「点下方按钮」——
     * 同一句话在详情页里会变成「进详情页登录」（人已经在了）。
     */
    fun balanceText(
        snapshot: CampusCardSnapshot?,
        loading: Boolean,
        fetched: Boolean,
        notLoggedIn: String = "未登录，进详情页登录",
    ): String = when {
        loading && !fetched -> "获取中…"
        snapshot == null || snapshot.failed -> "获取失败，下拉/点刷新重试"
        !snapshot.loggedIn -> notLoggedIn
        else -> snapshot.entries.firstOrNull()?.let { "¥${it.second}" } ?: "未识别到余额"
    }
}
