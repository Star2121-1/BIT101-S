package cn.bit101.android.data.repo

import android.content.Context
import cn.bit101.android.data.repo.base.ScoreRepo
import cn.bit101.android.data.score.ScoreLogic
import cn.bit101.android.data.score.ScoreLogic.ScoreEntry
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * [ScoreRepo] 的实现。
 *
 * ## 快照存哪
 *
 * `filesDir/score_snapshot.json`，内容是 `{去重键: 分数原文}`。
 * 用普通文件而不是 DataStore/Room：它**只有一个写者**（差分检查）且总量小
 * （一门课一条），读写整个文件最简单也最不容易出并发问题。
 *
 * ## 失败语义（重要）
 *
 * 出分提醒是「尽力而为」的：解析失败、接口失败、未登录 → 返回空列表，
 * **快照保持原样** —— 绝不因为一次失败就把基线清掉（否则下次会把全部历史成绩
 * 当成「新出分」轰炸用户）。
 */
internal class DefaultScoreRepo @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiManager: cn.bit101.android.data.net.base.APIManager,
) : ScoreRepo {

    override suspend fun fetchTable(): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            val response = apiManager.api.score.getScores(detail = null)
            response.body()?.data?.takeIf { it.isJsonArray }
        }.getOrNull()
    }

    override suspend fun syncAndDiff(): List<ScoreEntry> = withContext(Dispatchers.IO) {
        val raw = fetchTable() ?: return@withContext emptyList()

        val entries = runCatching { ScoreLogic.parseTable(raw) }.getOrDefault(emptyList())
        if (entries.isEmpty()) return@withContext emptyList()

        val existed = snapshotFile.exists()
        val snapshot = runCatching { readSnapshot() }.getOrNull()
        val newOnes = ScoreLogic.diff(snapshot, entries)

        // 无论有没有新课都要落盘：新成绩从此进入基线，下次不再报
        runCatching { writeSnapshot(ScoreLogic.snapshotOf(entries)) }
            .onFailure {
                if (!existed) {
                    // 首次建基线失败 → 下次仍是「首次」，只会静默重建，无害
                    android.util.Log.w(TAG, "write score snapshot failed: ${it.message}")
                }
            }
        newOnes
    }

    // ------------------------------------------------------------ 快照文件

    private val snapshotFile: File
        get() = File(appContext.filesDir, SNAPSHOT_NAME)

    private fun readSnapshot(): Map<String, String>? {
        if (!snapshotFile.exists()) return null
        val obj = JsonParser.parseString(snapshotFile.readText()).asJsonObjectOrNull()
            ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.entrySet().forEach { (k, v) ->
            (v as? com.google.gson.JsonPrimitive)?.asString?.let { out[k] = it }
        }
        return out
    }

    private fun writeSnapshot(map: Map<String, String>) {
        val obj = JsonObject()
        map.forEach { (k, v) -> obj.addProperty(k, v) }
        val tmp = File(snapshotFile.parentFile, "$SNAPSHOT_NAME.tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(snapshotFile)) {
            // rename 失败（极少见）就直写 —— 单写者场景没有并发问题
            snapshotFile.writeText(obj.toString())
            tmp.delete()
        }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()

    private companion object {
        const val SNAPSHOT_NAME = "score_snapshot.json"
        const val TAG = "ScoreRepo"
    }
}
