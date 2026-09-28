package cn.bit101.android.data.school

import java.time.LocalDateTime

/**
 * 一条借阅记录（图书馆「我的借阅」）。
 *
 * ## 为什么字段是可空的 / 带原始串
 *
 * 门户返回的每个单元格**自带字段名**（形如 `{"value": "...", "key": "应还日"}`），
 * 所以解析是**按字段名取值**、不依赖列顺序 —— 门户改列序不会把我们弄坏。
 * 但日期是 `yyyy-MM-dd HH:mm:ss` 字符串，**解析失败不能丢整条记录**
 * （书名等信息仍然有用），所以日期字段可空，并保留 [dueRaw] 原文兜底展示。
 *
 * ⚠️ 接口**不给我们 id**，[key] 是用书名 + ISBN + 应还日合成的稳定键，
 * 仅用于「新出现的记录」判定（发通知），不要当主键持久化。
 */
data class BorrowRecord(
    /** 书名。 */
    val title: String = "",
    /** 作者。 */
    val author: String = "",
    /** ISBN（也用于去重）。 */
    val isbn: String = "",
    /** 馆藏地，如「良乡自然科学图书第二阅览室」。 */
    val location: String = "",
    /** 借阅时间；解析不出为 null。 */
    val borrowedAt: LocalDateTime? = null,
    /** 应还时间；**到期提醒的核心字段**，解析不出为 null。 */
    val dueAt: LocalDateTime? = null,
    /** 应还时间的原始文本；[dueAt] 为 null 时仍可展示。 */
    val dueRaw: String = "",
) {
    /** 稳定键：书名 + ISBN + 应还日。接口不给 id，只能用这个做「新记录」判定。 */
    val key: String get() = "$title|$isbn|$dueRaw"
}
