package cn.bit101.android.config.datastore.basic

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * 「某个偏好项的值」流。
 *
 * ## 为什么不用 `flowOf(读一次)`
 *
 * 一次性快照的流**永远不会更新**：用户登录后学号变了，界面（登录页 / 设置页的「学号」）
 * 还显示旧值 —— 那是 `EncryptedPreferencesItem` 以前的老毛病。
 *
 * ## 这里的三个约定
 *
 * 1. **订阅时先发一次当前值**（否则新订阅者要等下一次变更才知道现值）；
 * 2. **值没变就不发**（否则同一个值会反复刷 UI）；
 * 3. **先注册监听、再读当前值**（否则「读到旧值 → 变更发生 → 监听还没装上」
 *    之间会丢一次事件 ⇒ 界面停在旧值）。
 *
 * ⚠️ 每次 `collect` 都会**单独注册一个监听**（并在收集结束时注销）：
 * 一个值可能被多个界面同时订阅，各自一份订阅互不干扰。
 */
internal class PreferenceValueFlow(
    private val key: String,
    private val read: () -> String,
    private val changeSource: PreferenceChangeSource,
) : Flow<String> {

    override suspend fun collect(collector: FlowCollector<String>) {
        // 1) 先把「变更了」的信号接上（订阅是同步完成的 ⇒ 后面不会漏事件）
        //    CONFLATED：变更可以合并，反正每次都要重读当前值
        val signals = Channel<Unit>(Channel.CONFLATED)
        val unsubscribe = changeSource.subscribe(key) { signals.trySend(Unit) }
        try {
            // 2) 再读当前值 —— 此刻起不会漏事件
            var last = read()
            collector.emit(last)

            // 3) 之后每次变更都重读一次（值没变就不发）
            for (signal in signals) {
                val current = read()
                if (current != last) {
                    last = current
                    collector.emit(current)
                }
            }
        } finally {
            unsubscribe()
            signals.close()
        }
    }
}
