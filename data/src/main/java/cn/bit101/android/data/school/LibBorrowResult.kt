package cn.bit101.android.data.school

/**
 * 图书馆借阅（超星智慧门户「我的借阅」）的请求结果。
 *
 * ⚠️ 与 [CampusNetResult] 同一条纪律：**必须把「取不到」和「没有」分开**。
 * 借阅条数直接驱动到期提醒 —— 把「网络失败」显示成「0 本」，
 * 就会在真正该提醒的时候告诉用户「你没借书」。
 */
sealed interface LibBorrowResult {

    /** 取到了（[records] 可能为空 = 确实没有在借的书）。 */
    data class Ok(val records: List<BorrowRecord>) : LibBorrowResult

    /**
     * 未登录（`/engine2/header/user-info` 的 `uid` 为空）。
     *
     * 这是**可以直接处置**的状态：在 App 内 WebView 打开一次图书馆登录页即可。
     */
    data object LoggedOut : LibBorrowResult

    /** 请求失败 / 响应形态不认识。[reason] 只用于排查。 */
    data class Failed(val reason: String) : LibBorrowResult

    /** 有数据就取、没有就 null（给不关心原因的调用方）。 */
    val recordsOrNull: List<BorrowRecord>? get() = (this as? Ok)?.records

    /** 已登录且取到了才算「确定」，否则 null —— 通知去重必须用这个。 */
    val confidentCount: Int? get() = (this as? Ok)?.records?.size
}
