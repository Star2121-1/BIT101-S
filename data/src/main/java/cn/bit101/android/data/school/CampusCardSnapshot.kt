package cn.bit101.android.data.school

/**
 * 一卡通首页快照（首版启发式解析的产物）。
 *
 * @property entries 解析出的候选 (标签, 数值) 对 —— 可能是余额 / 过渡余额等，
 *   字段名以首页实际文案为准，解析不出为空列表
 * @property htmlSnippet 首页 HTML 的前若干字符（调试期留着，方便下个版本
 *   对照真实响应写精确解析；上线稳定后可去掉）
 * @property loggedIn 首页是否像「已登录」状态（未登录时通常是 CAS 登录页）
 * @property failed **请求本身失败**（网络 / 超时 / 异常）—— 与「未登录」是两回事：
 *   前者刷新重试即可，后者要用户去登录。混在一起会说错话（2026-09-25 踩过）
 */
data class CampusCardSnapshot(
    val entries: List<Pair<String, String>>,
    val htmlSnippet: String,
    val loggedIn: Boolean,
    val failed: Boolean = false,
)
