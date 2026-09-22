package cn.bit101.android.features.notify

import android.content.Context
import androidx.core.content.edit

/**
 * 已发提醒的记录（防重复打扰）。
 *
 * 为什么不用 Room：这是**纯粹的短期幂等状态**，只需要「这个键发过没有」，
 * 用 SharedPreferences 存一行 key=时间戳 最省事；重启后依然有效。
 *
 * ⚠️ 两个约束：
 * 1. **必须同步读**（排期时要用），所以不能用 DataStore；
 * 2. 记录要**定期清理**（[prune]）—— 否则课表跑一年会攒下几千条。
 *    清理策略：只保留最近 [KEEP_DAYS] 天的记录（提醒去重只关心未来几天）。
 */
internal object NotifySentStore {

    private const val PREF_NAME = "notify_sent"
    private const val KEEP_DAYS = 14L

    @Volatile
    private var appContext: Context? = null

    /** App 启动时调一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context? = appContext) =
        context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 已发过的键集合。取不到时返回空集（宁可重复发一次，也不要整块不发）。 */
    fun read(): Set<String> = runCatching {
        prefs()?.all?.keys.orEmpty()
    }.getOrDefault(emptySet())

    fun contains(key: String): Boolean = runCatching {
        prefs()?.contains(key) == true
    }.getOrDefault(false)

    /** 记录「已发」。 */
    fun mark(key: String, atMillis: Long = System.currentTimeMillis()) {
        runCatching { prefs()?.edit { putLong(key, atMillis) } }
    }

    /** 清空（调试 / 关闭提醒时用）。 */
    fun clear() {
        runCatching { prefs()?.edit { clear() } }
    }

    /**
     * 清掉过期记录。只在排期时偶尔调用，避免每次开 App 都写盘。
     */
    fun prune(nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            val p = prefs() ?: return
            val deadline = nowMillis - KEEP_DAYS * 24 * 3600 * 1000
            val stale = p.all.filterValues { (it as? Long ?: 0L) < deadline }.keys
            if (stale.isEmpty()) return
            p.edit { stale.forEach { remove(it) } }
        }
    }
}
