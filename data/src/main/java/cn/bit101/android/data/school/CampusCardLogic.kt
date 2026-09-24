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
     * ⚠️ **「未登录」用最终 URL 判定，绝不用 HTML 关键词** —— 首页正文里
     * 完全可能含「统一身份认证」之类的文案（菜单/页脚），按关键词判会把
     * 已登录页误判成登录页（v1.8.0 的实测 bug：登录后卡片永远显示
     * 「点开登录后显示」）。未登录时服务端会 302 到 CAS，OkHttp 跟随重定向后
     * `finalUrl` 的主机就是 `sso.bit.edu.cn` —— 这是唯一可靠的信号。
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
}
