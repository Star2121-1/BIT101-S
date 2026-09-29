package cn.bit101.android.data.repo

import android.content.Context
import cn.bit101.android.data.repo.base.TeachingAdjustmentRepo
import cn.bit101.android.data.school.TeachingAdjustmentEntry
import cn.bit101.android.data.school.TeachingAdjustmentLogic
import cn.bit101.android.data.school.TeachingAdjustmentStore
import cn.bit101.android.data.school.TeachingAdjustments
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 教学安排调整的取数（列表页 → 逐条正文 → 解析 → 缓存）。
 *
 * 端点与解析规则见 [TeachingAdjustmentLogic] 的说明。
 *
 * ⚠️ **一切失败都静默降级**：它挂在课表页上，抛出去会把整页拖垮，
 * 而「拿不到调休」只是少了个增强，不该影响看课表。
 */
@Singleton
internal class DefaultTeachingAdjustmentRepo @Inject constructor(
    @ApplicationContext private val context: Context,
) : TeachingAdjustmentRepo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun load(forceRefresh: Boolean): TeachingAdjustments? = withContext(Dispatchers.IO) {
        val cached = TeachingAdjustmentStore.read(context)
        val fresh0 = System.currentTimeMillis()
        if (!forceRefresh && cached != null && fresh0 - cached.fetchedAtMillis < TTL_MS) {
            return@withContext cached.toResult()
        }

        val fetched = runCatching { fetchAll() }.getOrNull()
        if (fetched == null) {
            // 联网失败（校外 / 学校站点维护）⇒ 退回旧缓存，哪怕已过期。
            // ⚠️ 宁可略旧，也不要让课表**突然**「没有调休」——那比一直旧着更误导。
            return@withContext cached?.toResult()
        }
        TeachingAdjustmentStore.write(context, fetched)
        fetched.toResult()
    }

    /** 抓列表页 → 取最近几条「教学安排调整」通知 → 逐条抓正文解析。 */
    private fun fetchAll(): TeachingAdjustmentStore.Cached? {
        val listHtml = get(LIST_URL) ?: return null
        val notices = TeachingAdjustmentLogic.parseNoticeList(listHtml, LIST_URL)
            .filter { TeachingAdjustmentLogic.isAdjustmentNotice(it.title) }
            .take(MAX_NOTICES)
        if (notices.isEmpty()) return null

        val entries = ArrayList<TeachingAdjustmentEntry>()
        notices.forEach { n ->
            // 单条抓失败/解析不出就跳过，不拖累其它几条
            val html = get(n.url) ?: return@forEach
            entries += TeachingAdjustmentLogic.parseNotice(html, n.title, n.url)
        }
        return TeachingAdjustmentStore.Cached(
            fetchedAtMillis = System.currentTimeMillis(),
            // 列表页是**由新到旧** ⇒ 同一天被多条覆盖时保留**较新**那条
            entries = entries.distinctBy { it.date }.sortedBy { it.date },
            notices = notices,
        )
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute()
            .use { resp -> if (resp.code == 200) resp.body?.string() else null }
    }.getOrNull()

    private fun TeachingAdjustmentStore.Cached.toResult() =
        TeachingAdjustments(entries, notices, fetchedAtMillis)

    private companion object {
        /** 教学运行 → 课程调整 栏目。 */
        const val LIST_URL = "https://jxzx.bit.edu.cn/jxyx/kctz/"

        /** 只取最近 3 条 —— 一学年就 2~4 条（中秋国庆 / 元旦 / 上半年节假日）。 */
        const val MAX_NOTICES = 3

        /** 缓存有效期：学校发通知是低频事件，一天两次足够。 */
        const val TTL_MS = 12 * 60 * 60 * 1000L

        const val UA = "Mozilla/5.0 (Linux; Android 13)"
    }
}
