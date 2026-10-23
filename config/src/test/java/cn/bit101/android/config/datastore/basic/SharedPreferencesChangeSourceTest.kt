package cn.bit101.android.config.datastore.basic

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SharedPreferencesChangeSource]（`EncryptedPreferencesItem` 用的 Android 适配层）单测。
 *
 * [PreferenceValueFlowTest] 只测了「时序逻辑」（用假 `PreferenceChangeSource`），
 * 这层是**真正接 `SharedPreferences` 监听**的部分，之前没有覆盖。用 MockK 打桩
 * `SharedPreferences`，直接驱动 `SharedPreferencesChangeSource` 内部创建的那个
 * [SharedPreferences.OnSharedPreferenceChangeListener]，钉住两条：
 *
 * 1. **按明文 key 精确过滤**：别的 key 变更不触发 `onChanged`（加密存储回调里
 *    `changedKey` 就是明文 key，所以能直接比）；
 * 2. **退订 lambda 真的注销**：调用返回的退订函数后，`unregister...` 被调、
 *    之后变更不再触发。
 *
 * ⚠️ 不覆盖「`remove`/`clear` 不通知」这条 —— 那是 `EncryptedSharedPreferences`
 * 库的行为，靠 javap 核实过（见类注释），不在本类职责内。
 */
class SharedPreferencesChangeSourceTest {

    private fun stubPrefs(): SharedPreferences = mockk()

    @Test
    fun `本 key 变更触发、别的 key 不触发`() {
        val prefs = stubPrefs()
        val captured = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(captured)) } just Runs

        val source = SharedPreferencesChangeSource(prefs)
        var fired = 0
        source.subscribe("sid") { fired++ }

        prefs.let {
            captured.captured.onSharedPreferenceChanged(it, "login_password") // 别的 key ⇒ 不触发
            assertEquals(0, fired)
            captured.captured.onSharedPreferenceChanged(it, "sid")           // 本 key ⇒ 触发
            captured.captured.onSharedPreferenceChanged(it, "sid")
        }
        assertEquals(2, fired)
    }

    @Test
    fun `退订函数注销监听器、之后不再触发`() {
        val prefs = stubPrefs()
        val captured = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(captured)) } just Runs
        every { prefs.unregisterOnSharedPreferenceChangeListener(any()) } just Runs

        val source = SharedPreferencesChangeSource(prefs)
        var fired = 0
        val unsubscribe = source.subscribe("sid") { fired++ }

        captured.captured.onSharedPreferenceChanged(prefs, "sid")
        assertEquals(1, fired)

        unsubscribe()
        // 源类的职责是「调 unregister 注销」；「注销后 SharedPreferences 不再送达」
        // 是 SharedPreferences 的行为，这里只验 unregister 被用对的那个 listener 调了。
        verify { prefs.unregisterOnSharedPreferenceChangeListener(captured.captured) }
    }
}
