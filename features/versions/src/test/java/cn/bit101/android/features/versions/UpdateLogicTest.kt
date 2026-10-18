package cn.bit101.android.features.versions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新提示判定的真值表测试（v1.12.0-alpha.12「降级提示」修复的守卫）。
 *
 * 变量：S = 服务端 version_code；I = 装机 version_code；
 *       M = min_version_code（强更线）；G = 用户已忽略值
 *       （`SettingDataStore.settingIgnoreVersion`，默认 -1L）。
 *
 * 判据：`shouldPrompt = (I < M) || (S > I && S > G)`
 * —— 强更（I < M）优先、不受 G 约束；常规提示必须同时 S > I 且 S > G。
 *
 * ⚠️ 第 ① 组（S < I 不弹）就是本缺陷的回归钉：老代码没有 `S > I` 这条，
 * 服务端数据陈旧时对所有「从未忽略」的用户弹降级提示。
 */
class UpdateLogicTest {

    private fun prompt(s: Long, i: Long, m: Long, g: Long) =
        UpdateLogic.shouldPrompt(
            serverVersionCode = s,
            installedVersionCode = i,
            minVersionCode = m,
            ignoredVersionCode = g,
        )

    // ---------- ① 服务端号 < 装机号且未忽略 ⇒ 不弹（降级守卫） ----------

    @Test
    fun `服务端号远低于装机号且未忽略_不弹`() {
        assertFalse(prompt(s = 7, i = 1011, m = 6, g = -1))
    }

    @Test
    fun `服务端号低装机号一档且未忽略_不弹`() {
        assertFalse(prompt(s = 1012, i = 1013, m = 6, g = -1))
    }

    // ---------- ② 服务端号 > 装机号且未忽略 ⇒ 弹 ----------

    @Test
    fun `服务端号高于装机号且未忽略_弹`() {
        assertTrue(prompt(s = 1013, i = 1011, m = 6, g = -1))
    }

    @Test
    fun `服务端号高装机号一档且未忽略_弹`() {
        assertTrue(prompt(s = 1012, i = 1011, m = 6, g = -1))
    }

    // ---------- ③ 装机号 < minVersionCode（强更）⇒ 弹，哪怕 S < I ----------

    @Test
    fun `强更时即使服务端号低于装机号也要弹`() {
        assertTrue(prompt(s = 1013, i = 1015, m = 1016, g = -1))
    }

    @Test
    fun `强更时即使已忽略该服务端号也要弹`() {
        assertTrue(prompt(s = 1013, i = 1015, m = 1016, g = 1015))
    }

    // ---------- ④ 已忽略该版本 ⇒ 不弹 ----------

    @Test
    fun `已忽略该服务端号_不弹`() {
        assertFalse(prompt(s = 1013, i = 1011, m = 6, g = 1013))
    }

    // ---------- 边界组 ----------

    @Test
    fun `同号_不弹`() {
        assertFalse(prompt(s = 1011, i = 1011, m = 6, g = -1))
    }

    @Test
    fun `忽略过更新的号_更旧的服务端号不弹`() {
        assertFalse(prompt(s = 1013, i = 1011, m = 6, g = 1015))
    }

    @Test
    fun `忽略过旧号_更新的服务端号仍弹`() {
        assertTrue(prompt(s = 1015, i = 1011, m = 6, g = 1013))
    }

    @Test
    fun `忽略值为默认负一_不影响常规提示`() {
        assertTrue(prompt(s = 1013, i = 1011, m = 6, g = -1L))
    }

    @Test
    fun `强更边界_装机号恰好等于最低版本_不算强更`() {
        // I == M：可用；此时是否提示退化为常规判据
        assertTrue(prompt(s = 1013, i = 1012, m = 1012, g = -1))
        assertFalse(prompt(s = 1012, i = 1012, m = 1012, g = -1))
    }
}
