package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NdefShortcutLogic] 的单测。
 *
 * 这一层是整个 NFC 功能里**唯一能把判定写死**的部分：纯字符串、零依赖。
 * 具体盯三件事：① 目标清单必须来自 `:config`（改了底栏不会漂移）
 * ② 写出来的格式要与解出来的一一对得上 ③ 来历不明的标签不许把 App 指到一个不存在的地方。
 */
class NdefShortcutLogicTest {

    private fun targets() = NdefShortcutLogic.targets()

    // ---------------------------------------------------------------- 目标清单

    @Test
    fun `目标清单取自 config 而不是本模块抄的一份`() {
        val routes = targets().map { it.route }
        // 底栏页全部在列
        assertTrue(routes.contains("schedule"))
        assertTrue(routes.contains("map"))
        assertTrue(routes.contains("gallery"))
        assertTrue(routes.contains("mine"))
        assertTrue(routes.contains("seat"))
        // 组件已经在用的两个无参顶层路由也要在
        assertTrue(routes.contains("login"))
        assertTrue(routes.contains("score"))
    }

    @Test
    fun `每个目标都有给人看的名字且没有重复的 route`() {
        val all = targets()
        assertTrue(all.all { it.label.isNotBlank() })
        assertEquals(all.size, all.distinctBy { it.route }.size)
    }

    // ------------------------------------------------------------ 编解码往返

    @Test
    fun `每个目标都能原样往返`() {
        targets().forEach { target ->
            val payload = NdefShortcutLogic.encode(target.route)
            assertEquals("${target.route} 应该能编码", target.route, NdefShortcutLogic.decode(payload!!))
        }
    }

    @Test
    fun `载荷是一眼看得懂的 URI`() {
        assertEquals("bit101://nfc/shortcut?to=schedule", NdefShortcutLogic.encode("schedule"))
    }

    // ---------------------------------------------------------------- 拒绝情况

    @Test
    fun `不在白名单里的 route 不许编进标签`() {
        assertNull(NdefShortcutLogic.encode("whatever"))
        assertNull(NdefShortcutLogic.encode(""))
        assertNull(NdefShortcutLogic.encode("   "))
        assertFalse(NdefShortcutLogic.isSupported("  "))
    }

    /**
     * 别人写的标签、或者 App 改版后已经不存在的老 route：
     * 都必须是「认不出来」而不是「照着跳」，否则用户会点到一个要么没反应、
     * 要么随便某个默认页的地方 —— 那比不识别更难排。
     */
    @Test
    fun `来历不明的目标一律判不出来`() {
        assertNull(NdefShortcutLogic.decode("https://bit101.cn/"))
        assertNull(NdefShortcutLogic.decode("bit101://nfc/shortcut"))
        assertNull(NdefShortcutLogic.decode("bit101://nfc/shortcut?to="))
        assertNull(NdefShortcutLogic.decode("bit101://nfc/shortcut?to=whatever"))
        assertNull(NdefShortcutLogic.decode("bit101://nfc/other?to=schedule"))
        assertNull(NdefShortcutLogic.decode(""))
    }

    @Test
    fun `标签里被 NFC 工具包了一层空白也认得出来`() {
        assertEquals("schedule", NdefShortcutLogic.decode("  bit101://nfc/shortcut?to=schedule\n"))
    }

    /**
     * 将来格式真的要变，会加一个问号后面的第二个参数（如 `v=2`）。
     * 这里先钉住「多余参数不影响现有字段的读取」，免得到那天才发现旧的解析器一遇到
     * 新字段就整体返回 null。
     */
    @Test
    fun `多余的查询参数不影响目标字段`() {
        assertEquals("seat", NdefShortcutLogic.decode("bit101://nfc/shortcut?to=seat&v=2"))
        assertEquals("seat", NdefShortcutLogic.decode("bit101://nfc/shortcut?v=2&to=seat"))
    }
}
