package cn.bit101.android.config.datastore.basic

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PreferenceValueFlow] 的纯逻辑单测。
 *
 * 钉住三条约定：
 * 1. 订阅即发当前值（不等下一次变更）；
 * 2. 只对本 key 的变更反应（别的 key 变了不关我事）；
 * 3. 值没变就不重复发（去重）。
 *
 * ⚠️ 变更源用**可控的假实现**而不是 `SharedPreferences`：
 * 真实实现的变更回调发生在**写盘线程**（没法在单测里可靠地驱动），
 * 而且要 `Robolectric` 才能跑 —— 这里只需要验证**时序逻辑**，
 * Android 那层（`SharedPreferencesChangeSource`）另有人盯。
 */
class PreferenceValueFlowTest {

    /** 可手动触发的假变更源。 */
    private class FakeChangeSource : PreferenceChangeSource {
        private val handlers = mutableMapOf<String, () -> Unit>()

        override fun subscribe(key: String, onChanged: () -> Unit): () -> Unit {
            handlers[key] = onChanged
            return { handlers.remove(key) }
        }

        fun fire(key: String) {
            handlers[key]?.invoke()
        }
    }

    @Test
    fun `订阅时先发当前值`() = runTest {
        val source = FakeChangeSource()
        val flow = PreferenceValueFlow(key = "sid", read = { "112233" }, changeSource = source)

        val got = mutableListOf<String>()
        val job = launch { flow.collect { got += it } }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("112233"), got)
        job.cancel()
    }

    @Test
    fun `本 key 变更后发新值`() = runTest {
        val source = FakeChangeSource()
        var value = "old"
        val flow = PreferenceValueFlow(key = "sid", read = { value }, changeSource = source)

        val got = mutableListOf<String>()
        val job = launch { flow.collect { got += it } }
        testScheduler.advanceUntilIdle()

        value = "new"
        source.fire("sid")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("old", "new"), got)
        job.cancel()
    }

    @Test
    fun `别的 key 变更不触发`() = runTest {
        val source = FakeChangeSource()
        val flow = PreferenceValueFlow(key = "sid", read = { "112233" }, changeSource = source)

        val got = mutableListOf<String>()
        val job = launch { flow.collect { got += it } }
        testScheduler.advanceUntilIdle()

        source.fire("seat_token")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("112233"), got)
        job.cancel()
    }

    @Test
    fun `值没变不重复发`() = runTest {
        val source = FakeChangeSource()
        val flow = PreferenceValueFlow(key = "sid", read = { "same" }, changeSource = source)

        val got = mutableListOf<String>()
        val job = launch { flow.collect { got += it } }
        testScheduler.advanceUntilIdle()

        source.fire("sid")
        source.fire("sid")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("same"), got)
        job.cancel()
    }

    @Test
    fun `先注册监听再读当前值，不丢事件`() = runTest {
        val source = FakeChangeSource()
        var value = "old"
        val flow = PreferenceValueFlow(key = "sid", read = { value }, changeSource = source)

        val got = mutableListOf<String>()
        val job = launch { flow.collect { got += it } }
        testScheduler.advanceUntilIdle()

        // 变更发生在「监听已装上」之后 ⇒ 必须收到
        value = "new"
        source.fire("sid")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("old", "new"), got)
        job.cancel()
    }
}
