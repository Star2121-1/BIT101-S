package cn.bit101.android.data.repo

import android.content.Context
import cn.bit101.android.data.repo.base.ScoreRepo
import cn.bit101.android.data.score.ScoreCheckStore
import cn.bit101.android.data.score.ScoreLogic
import cn.bit101.android.data.score.ScoreLogic.ScoreEntry
import cn.bit101.android.data.score.ScoreQueryLogic
import cn.bit101.api.model.http.bit101.ScoreQueryModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * [ScoreRepo] 的实现。
 *
 * ## 数据怎么拿（2026-09-26 起）
 *
 * 后端已迁移：旧的 `GET /scores` 恒 404，教务成绩改由 BIT101 **认证主机**的
 * **异步挑战流程**转发，见 [ScoreQueryApiService]。三步：起挑战 → 轮询到 `jwb` 就绪 →
 * 带 Bearer 取数。返回的 `data` 是二维数组（第 0 行表头），正好喂给 [ScoreLogic]。
 *
 * ## 限频（必须）
 *
 * 新流程每次都要用学号密码走一遍学校统一身份认证 —— 而检查触发点是「App 启动 + 每日任务」，
 * 不限频就是每次开 App 登录一次，直接撞学校风控。所以 [syncAndDiff] 里有
 * [ScoreQueryLogic.CHECK_INTERVAL_MS]（12h）闸门。
 *
 * ## 快照存哪
 *
 * `filesDir/score_snapshot.json`，内容是 `{去重键: 分数原文}`。
 * 用普通文件而不是 DataStore/Room：它**只有一个写者**（差分检查）且总量小
 * （一门课一条），读写整个文件最简单也最不容易出并发问题。
 *
 * ## 失败语义（重要）
 *
 * 出分提醒是「尽力而为」的：解析失败、接口失败、未登录、要短信验证 → 返回空列表，
 * **快照保持原样** —— 绝不因为一次失败就把基线清掉（否则下次会把全部历史成绩
 * 当成「新出分」轰炸用户）。
 */
internal class DefaultScoreRepo @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiManager: cn.bit101.android.data.net.base.APIManager,
    private val loginStatus: cn.bit101.android.config.user.base.LoginStatus,
) : ScoreRepo {

    override suspend fun fetchTable(): JsonElement? = withContext(Dispatchers.IO) {
        (query() as? Query.Ok)?.table
    }

    override suspend fun lastCheck(): ScoreCheckStore.Status? = withContext(Dispatchers.IO) {
        ScoreCheckStore.read(appContext)
    }

    /** 一次查询的结果 —— 比裸 `null` 多带一个失败原因，好写用户可见的状态。 */
    private sealed interface Query {
        data class Ok(val table: JsonElement) : Query
        data class Err(val code: ScoreCheckStore.Code) : Query
    }

    /**
     * 走完「起挑战 → 轮询 → 取数」。
     *
     * ⚠️ 每一步失败都归类而不是简单返回 null：设置页要能告诉用户
     * 「是学校要短信」还是「网络失败」。
     */
    private suspend fun query(): Query {
        val sid = loginStatus.sid.get()
        val password = loginStatus.password.get()
        if (sid.isEmpty() || password.isEmpty()) return Query.Err(ScoreCheckStore.Code.NOT_LOGGED_IN)

        return runCatching {
            val api = apiManager.api.scoreQuery
            val start = api.start(ScoreQueryModel.Body(username = sid, password = password))
                .body()?.detail ?: return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)

            val challengeId = start.challengeId ?: return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)
            val token = start.accessToken ?: return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)
            if (ScoreQueryLogic.needsSms(start.status)) {
                return@runCatching Query.Err(ScoreCheckStore.Code.NEED_SMS)
            }

            var ready = ScoreQueryLogic.isReady(start.readyServices)
            var attempts = 0
            while (!ready && attempts < ScoreQueryLogic.POLL_MAX_ATTEMPTS) {
                delay(ScoreQueryLogic.POLL_INTERVAL_MS)
                attempts++
                val status = api.poll(challengeId, token).body()
                    ?: return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)
                ready = ScoreQueryLogic.isReady(status.readyServices)
                if (ready) break
                // 要短信验证（后台没交互通道）→ 明确记下来；挑战终结 → 也是失败
                if (ScoreQueryLogic.needsSms(status.status)) {
                    return@runCatching Query.Err(ScoreCheckStore.Code.NEED_SMS)
                }
                if (ScoreQueryLogic.isTerminal(status.status)) {
                    return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)
                }
            }
            if (!ready) return@runCatching Query.Err(ScoreCheckStore.Code.TIMEOUT)

            val table = api.fetch(
                body = ScoreQueryModel.Body(username = sid, password = password, challengeId = challengeId),
                authorization = "Bearer $token",
            ).body()?.data?.takeIf { it.isJsonArray }
                ?: return@runCatching Query.Err(ScoreCheckStore.Code.FAILED)

            Query.Ok(table)
        }.getOrElse { Query.Err(ScoreCheckStore.Code.FAILED) }
    }

    override suspend fun syncAndDiff(force: Boolean): List<ScoreEntry> = withContext(Dispatchers.IO) {
        // 限频：新流程每次都要走一遍学校登录，不能每次开 App 都来一发（撞风控）
        // ⚠️ 跳过时**不写状态** —— 否则会把上一次的真实结果覆盖成「刚检查过」，
        // 到底查成没查成谁都看不出来（启动时本来就会被调用两次）
        if (!force && !ScoreQueryLogic.shouldCheck(readLastCheck(), System.currentTimeMillis())) {
            return@withContext emptyList()
        }
        writeLastCheck()

        val queryResult = query()
        if (queryResult is Query.Err) {
            record(queryResult.code, 0)
            return@withContext emptyList()
        }
        val raw = (queryResult as Query.Ok).table

        val entries = runCatching { ScoreLogic.parseTable(raw) }.getOrDefault(emptyList())
        record(ScoreCheckStore.Code.OK, entries.size)
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

    // ------------------------------------------------------------ 检查时间戳 / 状态

    /** 记录本次检查结果（设置页可见 —— 这个功能受学校风控影响，状态必须可见）。 */
    private fun record(code: ScoreCheckStore.Code, count: Int) {
        ScoreCheckStore.write(appContext, ScoreCheckStore.Status(System.currentTimeMillis(), code, count))
    }

    /** 上次检查时刻（毫秒）；读不到返回 -1（视为该查）。 */
    private fun readLastCheck(): Long =
        runCatching { File(appContext.filesDir, LAST_CHECK_NAME).readText().trim().toLong() }
            .getOrDefault(-1L)

    private fun writeLastCheck(at: Long = System.currentTimeMillis()) {
        runCatching { File(appContext.filesDir, LAST_CHECK_NAME).writeText(at.toString()) }
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
        const val LAST_CHECK_NAME = "score_last_check"
        const val TAG = "ScoreRepo"
    }
}
