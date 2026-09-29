package cn.bit101.android.features.schedule.course

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「调休配色」的单测。
 *
 * 这些东西肉眼也能看，但**肉眼看不出的错**才麻烦：哪天有人把 Holiday 也接到平常色上，
 * 界面上「什么都没变」，谁都不会发现。这里把「三套色必须互不相同」「边框透明度不能丢」
 * 钉住，改坏了就跑红。
 */
class ScheduleItemColorTest {

    private val base = ScheduleItemColor(
        // 边框本来就是半透明的（`secondary.copy(alpha = 0.25f)`）
        boarderColor = Color(0xFF112233L).copy(alpha = 0.25f),
        containerColor = Color(0xFF445566L),
        contextColor = Color(0xFF778899L),
    )

    private val greyContainer = Color(0xFF9A9A9AL)
    private val greyOn = Color(0xFF202020L)

    @Test
    fun `靠拢程度为0时保持原样`() {
        assertEquals(base, base.tinted(greyContainer, greyOn, 0f))
    }

    @Test
    fun `靠拢程度为1时完全变成目标色`() {
        val tinted = base.tinted(greyContainer, greyOn, 1f)
        assertEquals(greyContainer, tinted.containerColor)
        assertEquals(greyOn, tinted.contextColor)
        // 边框只比 RGB —— 它的 alpha 是**刻意保留**的（见下一条）
        assertEquals(greyContainer.red, tinted.boarderColor.red, 1e-6f)
        assertEquals(greyContainer.green, tinted.boarderColor.green, 1e-6f)
        assertEquals(greyContainer.blue, tinted.boarderColor.blue, 1e-6f)
    }

    /**
     * ⚠️ 边框的 alpha 必须保留 —— 否则「显示边框」一开，放假列就是一圈实线硬边。
     * （`mixColor` 就会丢 alpha，所以 [tinted] 没用它。）
     */
    @Test
    fun `靠拢时保留边框的透明度`() {
        val tinted = base.tinted(greyContainer, greyOn, 0.85f)
        assertEquals(base.boarderColor.alpha, tinted.boarderColor.alpha, 1e-6f)
    }

    /** ⚠️ 「区分」得真的看得出来：平常 / 放假 / 补课三套容器色两两不同。 */
    @Test
    fun `放假与补课的色调互不相同`() {
        val holiday = base.tinted(greyContainer, greyOn, 0.85f)
        val makeUp = base.tinted(Color(0xFF00FF00L), Color(0xFF000000L), 0.6f)
        assertNotEquals(base.containerColor, holiday.containerColor)
        assertNotEquals(base.containerColor, makeUp.containerColor)
        assertNotEquals(holiday.containerColor, makeUp.containerColor)
    }
}
