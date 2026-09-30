package cn.bit101.android.data.school

import android.content.Context
import cn.bit101.android.data.common.TextFileCache
import java.time.LocalDate

/**
 * 教学安排调整的**本地缓存**。
 *
 * 为什么必须缓存：数据源 `jxzx.bit.edu.cn` **只确认了校内可达**（外网可达性未验证），
 * 而且学校一学期只发 2~4 条通知 —— 每次打开课表都联网既慢又容易失败。
 * 缓存住上一次的结果，取数失败时还能退回旧数据（宁可略旧，也不要课表突然「没有调休」）。
 *
 * 存成一个小文本文件（`filesDir/teaching_adjustments`），**不用 Room**：
 * 数据量极小、结构固定、只需整体读写一次。
 *
 * 格式（`|` 分隔，首行是版本 + 取数时刻）：
 * ```
 * v1|1738xxxxxxx
 * E|2026-10-10|MAKEUP|4|按10月8日（周四）课表上课|关于2026年…的通知|https://…
 * E|2026-10-01|NOCLASS||国庆假期|关于2026年…的通知|https://…
 * N|关于2026年中秋节、国庆节教学安排调整的通知|https://…
 * ```
 */
object TeachingAdjustmentStore {

    /** 读出来的缓存内容。 */
    data class Cached(
        val fetchedAtMillis: Long,
        val entries: List<TeachingAdjustmentEntry>,
        val notices: List<TeachingNotice>,
    )

    private const val FILE_NAME = "teaching_adjustments"
    private const val VERSION = "v1"

    fun read(context: Context): Cached? =
        TextFileCache.read(context, FILE_NAME)?.let(::parse)

    fun write(context: Context, cached: Cached) =
        TextFileCache.write(context, FILE_NAME, encode(cached))

    // ------------------------------------------------------------ 编解码（纯函数，可单测）

    fun encode(cached: Cached): String = buildString {
        append(VERSION).append('|').append(cached.fetchedAtMillis).append('\n')
        cached.entries.forEach { e ->
            append("E")
                .append('|').append(e.date)
                .append('|').append(if (e.plan is DayPlan.MakeUp) "MAKEUP" else "NOCLASS")
                .append('|').append((e.plan as? DayPlan.MakeUp)?.targetWeekday ?: "")
                .append('|').append(TextFileCache.sanitizeField(e.note))
                .append('|').append(TextFileCache.sanitizeField(e.sourceTitle))
                .append('|').append(TextFileCache.sanitizeField(e.sourceUrl))
                .append('\n')
        }
        cached.notices.forEach { n ->
            append("N").append('|')
                .append(TextFileCache.sanitizeField(n.title)).append('|')
                .append(TextFileCache.sanitizeField(n.url)).append('\n')
        }
    }

    /**
     * 解析存储串。**形状不对就返回 null 或跳过该行**（不猜）——
     * 一行坏掉不该让整份缓存作废，但也不能把坏行当成有效数据。
     */
    fun parse(raw: String?): Cached? {
        val lines = raw?.lines()?.filter { it.isNotBlank() } ?: return null
        val head = lines.firstOrNull()?.split('|') ?: return null
        if (head.size < 2 || head[0] != VERSION) return null
        val fetchedAt = head[1].toLongOrNull() ?: return null

        val entries = mutableListOf<TeachingAdjustmentEntry>()
        val notices = mutableListOf<TeachingNotice>()
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.getOrNull(0)) {
                "E" -> {
                    val date = p.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: return@forEach
                    val plan = when (p.getOrNull(2)) {
                        "MAKEUP" -> p.getOrNull(3)?.toIntOrNull()
                            ?.takeIf { it in 1..7 }?.let { DayPlan.MakeUp(it) } ?: return@forEach
                        "NOCLASS" -> DayPlan.NoClass
                        else -> return@forEach
                    }
                    entries += TeachingAdjustmentEntry(
                        date = date,
                        plan = plan,
                        note = p.getOrNull(4).orEmpty(),
                        sourceTitle = p.getOrNull(5).orEmpty(),
                        sourceUrl = p.getOrNull(6).orEmpty(),
                    )
                }
                "N" -> {
                    val title = p.getOrNull(1).orEmpty()
                    val url = p.getOrNull(2).orEmpty()
                    if (title.isNotBlank() && url.isNotBlank()) notices += TeachingNotice(title, url)
                }
            }
        }
        return Cached(fetchedAt, entries, notices)
    }
}
