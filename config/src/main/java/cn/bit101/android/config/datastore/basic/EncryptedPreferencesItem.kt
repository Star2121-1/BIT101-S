package cn.bit101.android.config.datastore.basic

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow

/**
 * 加密存储里的一个偏好项。
 *
 * ## 为什么不能用 `flowOf(读一次)`
 *
 * 那样拿到的是**创建那一刻的快照**，之后再也不会变：
 * 用户登录后学号变了，界面（登录页 / 设置页的「学号」）还显示旧值。
 * ⇒ 这里给的是**真流**：订阅时先发当前值，之后每次该项变更再发。
 *
 * ⚠️ 加密存储的变更通知按**明文 key** 送达（`EncryptedSharedPreferences`
 * 自己维护监听列表，用解出来的 key 通知）⇒ 可以按 key 精确过滤。
 * 但加密项**可能读不出来**（数据损坏 / 密钥失效），此时按空串处理，
 * 不能让整个流挂掉。
 */
internal class EncryptedPreferencesItem(
    private val sharedPreferences: SharedPreferences,
    private val key: String,
) : DataStoreItem<String> {
    override suspend fun get(): String = read()

    override suspend fun remove() {
        with(sharedPreferences.edit()) {
            remove(key)
            apply()
        }
    }

    override val flow: Flow<String>
        get() = PreferenceValueFlow(
            key = key,
            read = ::read,
            changeSource = SharedPreferencesChangeSource(sharedPreferences),
        )

    override suspend fun set(value: String) {
        with(sharedPreferences.edit()) {
            putString(key, value)
            apply()
        }
    }

    /** 读不出来（数据损坏 / 密钥失效）时按空串处理，不抛。 */
    private fun read(): String = runCatching {
        sharedPreferences.getString(key, null).orEmpty()
    }.getOrDefault("")
}
