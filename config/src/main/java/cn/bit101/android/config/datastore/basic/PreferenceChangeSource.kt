package cn.bit101.android.config.datastore.basic

import android.content.SharedPreferences

/**
 * 「某个偏好项变了」的变更源。
 *
 * 抽成接口是为了让 [PreferenceValueFlow] 的时序逻辑能脱离 Android 单测。
 */
internal interface PreferenceChangeSource {
    /**
     * 订阅某个 key 的变更。
     *
     * ⚠️ **注册必须同步完成**：调用方会「先注册、再读当前值」，
     * 这样「读到旧值 → 变更发生 → 监听还没装上」之间不会有丢事件的窗口。
     *
     * @return 取消订阅的函数
     */
    fun subscribe(key: String, onChanged: () -> Unit): () -> Unit
}

/**
 * 基于 [SharedPreferences] 的变更源。
 *
 * ⚠️ 加密存储（`EncryptedSharedPreferences`）的变更通知有两个坑：
 * 1. 通知里的 key 是**明文 key**（库内部把解出来的 key 放进 `mKeysChanged` 再通知），
 *    所以可以直接按明文 key 精确过滤；
 * 2. 只有 `putXxx` 会通知，**`remove` / `clear` 不会**（库把 key 从 `mKeysChanged`
 *    里**删掉**而不是加进去）—— 所以「删除某个值」不会触发流更新。
 */
internal class SharedPreferencesChangeSource(
    private val sharedPreferences: SharedPreferences,
) : PreferenceChangeSource {

    override fun subscribe(key: String, onChanged: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            // 回调发生在写盘线程 ⇒ 只能通知，不能阻塞
            if (changedKey == key) onChanged()
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        return { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
