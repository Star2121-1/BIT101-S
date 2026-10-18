package cn.bit101.android.features.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 守「组件页签槽位数」的一致性。
 *
 * ## 为什么要有这一条
 *
 * 组件页数散在**几个互不相干的地方**，而它们之间**没有编译期耦合**：
 * ① `PageKind` 枚举（页数与顺序）
 * ② `widget_root.xml` 里的 `tab_N` 槽位（A6 起每页还有一块 `tab_sel_N` 选中态）
 * ③ `WidgetViews.TAB_IDS`（页签 id 数组，另有成对的 `TAB_SEL_IDS`）
 *
 * 加新页时**只改 ①** 照样编译得过、单测（`build 固定返回五页…`）也过得去，
 * 表现是「新页永远不出现」。v1.9.54 加「自习」页就是这么漏的 —— 由用户先发现。
 *
 * ## ⚠️ 为什么读源码文本，而不是直接引用 `R.id`
 *
 * 变化点恰恰在「布局」与「代码数组」这两处**文本**里，数一数就能守住；
 * 而 JVM 单测里引用 `R` 会引入额外依赖。这两处只要 id 名对不上，
 * 编译期就会报错（`R.id.tab_N` 必须存在于布局）⇒ 本条补的正是**编译管不到**的那一段。
 */
class WidgetTabSlotTest {

    @Test
    fun `页签槽位数与 PageKind 一致`() {
        val layout = readSource("src/main/res/layout/widget_root.xml")
        val views = readSource("src/main/java/cn/bit101/android/features/widget/WidgetViews.kt")

        val layoutIds = Regex("""@\+id/tab_\d+""")
            .findAll(layout).map { it.value.removePrefix("@+id/") }.toSet()
        val codeIds = Regex("""R\.id\.tab_\d+""")
            .findAll(views).map { it.value.removePrefix("R.id.") }.toSet()
        // A6（v1.12.0-alpha.12）：每页多了一块选中态视图 tab_sel_N，必须成对存在
        val layoutSelIds = Regex("""@\+id/tab_sel_\d+""")
            .findAll(layout).map { it.value.removePrefix("@+id/") }.toSet()
        val codeSelIds = Regex("""R\.id\.tab_sel_\d+""")
            .findAll(views).map { it.value.removePrefix("R.id.") }.toSet()

        assertNotNull(layoutIds)
        assertEquals(
            "widget_root.xml 的 tab_N 槽位数必须等于 PageKind 的页数（加新页要同时加槽位）",
            PageKind.entries.size,
            layoutIds.size,
        )
        assertEquals(
            "WidgetViews.TAB_IDS 的个数必须等于 PageKind 的页数",
            PageKind.entries.size,
            codeIds.size,
        )
        // 只数个数会漏掉「个数对但两边 id 不同」⇒ 必须比**同一批 id**
        assertEquals("布局与 TAB_IDS 必须指向同一批槽位", layoutIds, codeIds)

        // 选中态视图：与未选中态严格成对（tab_N ⇔ tab_sel_N，不许缺、不许多）
        assertEquals(
            "widget_root.xml 的 tab_sel_N 槽位数必须与 tab_N 相等",
            layoutIds.size,
            layoutSelIds.size,
        )
        assertEquals(
            "WidgetViews.TAB_SEL_IDS 的个数必须与 TAB_IDS 相等",
            codeIds.size,
            codeSelIds.size,
        )
        assertEquals("布局与 TAB_SEL_IDS 必须指向同一批槽位", layoutSelIds, codeSelIds)
        assertEquals(
            "tab_N 与 tab_sel_N 序号必须一一对应",
            layoutIds.map { it.substringAfter("tab_") }.toSet(),
            layoutSelIds.map { it.substringAfter("tab_sel_") }.toSet(),
        )
        assertEquals(
            "TAB_IDS 与 TAB_SEL_IDS 序号必须一一对应",
            codeIds.map { it.substringAfter("tab_") }.toSet(),
            codeSelIds.map { it.substringAfter("tab_sel_") }.toSet(),
        )
    }

    /** 从模块源码树里读文件（Android 单测的 CWD 是模块目录，兜底再试仓库根）。 */
    private fun readSource(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("features/widget/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过：${candidates.joinToString { it.absolutePath }}）")
        return file.readText()
    }
}
