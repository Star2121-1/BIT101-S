package cn.bit101.android.data.school

/**
 * 校园网自助接口（深澜 `cgi-bin/rad_user_info`）的请求结果。
 *
 * ⚠️ **为什么要分三种而不是「有数据 / null」**：这个接口只有校园网内可达，于是
 * 「不在校内」「在校内但没在认证页登录过（没有在线会话）」「请求失败」三件事
 * 原来会塌成同一个 null → UI 一律显示「需连接校园网」。用户明明在校内也会被这么
 * 告知，完全无从下手（2026-09-25 实测踩到）。
 */
sealed interface CampusNetResult {

    /** 取到本机所在 NAT 的在线会话。 */
    data class Online(val info: CampusNetInfo) : CampusNetResult

    /**
     * 接口通了，但请求方**没有在线会话** —— 响应是 `not_online`。
     *
     * 对应「人在校内但没在认证页登录过」，是**可以直接处置**的状态（去认证页登录）。
     */
    data object NotOnline : CampusNetResult

    /** 连不上 / 超时 / 响应无法识别（多半不在校园网内）。[reason] 仅用于排查。 */
    data class Failed(val reason: String) : CampusNetResult

    /** 有数据就取、没有就 null（给不关心原因的调用方）。 */
    val infoOrNull: CampusNetInfo? get() = (this as? Online)?.info
}
