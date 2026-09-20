package cn.bit101.android.features.seat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 座位图视口几何的单测。
 *
 * 这两段逻辑原先内联在 `SeatMapCanvas` 的 `pointerInput` / `LaunchedEffect` 里，
 * 靠肉眼看模拟器截图验证 —— 双指缩放又没法用 adb 模拟（`input swipe` 只能单指），
 * 于是「以双指中心缩放」这个改动**根本没被真正测过**，只靠读代码保证。
 * 抽成纯函数后可以在这里用数值断言锁住不变式。
 */
class SeatMapViewportTest {

    // 模拟机 Pixel 6 / API 34 竖屏的实测可视区（px）。底图按宽铺满时高 607.5px，
    // 视口高 1244px（模拟器实测座位图区域高度）→ 上下各留白约 318px，符合真机表现。
    // ⚠️ 视口高度刻意取得比「放大 2 倍后的图高」小：否则纵向永远走「居中」分支，
    // 纵向平移与锚点逻辑就测不到了（最初用 1600 就踩了这个坑）。
    private val viewW = 1080f
    private val viewH = 1244f
    private val imageH = viewW * 1080f / 1920f // 607.5f

    // ── 初始视口：整图可见 ───────────────────────────────────────────────

    @Test
    fun `初始视口为整图可见 宽度贴合 不缩放`() {
        val v = initialViewport(viewW, viewH)
        assertEquals("整图可见时 scale 必须是 1（按宽度贴合）", 1f, v.scale, 1e-4f)
        assertEquals("水平贴左，不留横向空档", 0f, v.offsetX, 1e-4f)
    }

    @Test
    fun `初始视口垂直居中 上下留白相等`() {
        val v = initialViewport(viewW, viewH)
        // offsetY = (viewH - imageH) / 2：图比视口矮时为正，表示整图向下推移居中
        val expected = (viewH - imageH) / 2f
        assertEquals(expected, v.offsetY, 1e-4f)

        // 图上边到视口顶 = offsetY（图被下推到此处）
        val topGap = v.offsetY
        // 图下边到视口底 = viewH - (offsetY + imageH)
        val bottomGap = viewH - (v.offsetY + imageH)
        assertEquals("上下留白必须相等（居中）", topGap, bottomGap, 1e-3f)
        assertTrue("留白应为正（图比视口矮）", topGap > 0f)
    }

    @Test
    fun `初始视口整图完整落入可视区`() {
        val v = initialViewport(viewW, viewH)
        val left = v.offsetX
        val right = v.offsetX + viewW * v.scale
        val top = v.offsetY
        val bottom = v.offsetY + imageH * v.scale
        assertTrue("左边界不能越出屏幕左侧", left >= -1e-3f)
        assertTrue("右边界不能越出屏幕右侧", right <= viewW + 1e-3f)
        assertTrue("上边界不能越出屏幕顶部", top >= -1e-3f)
        assertTrue("下边界不能越出可视区底部", bottom <= viewH + 1e-3f)
        // 图比视口小（本用例）时必须是完整可见、不能有任何一边被裁
        assertTrue("整图可见：图宽应等于视口宽", (right - left) <= viewW + 1e-3f)
        assertTrue("整图可见：图高应不超过视口", (bottom - top) <= viewH + 1e-3f)
    }

    // ── 捏合缩放：以双指中心为锚点 ───────────────────────────────────────

    /**
     * 核心不变式：**变换前位于 centroid 下方的那个图像点，变换后必须还在 centroid。**
     * 这正是「以双指中心为中心缩放」的定义，也是这次修复的验收标准。
     *
     * ⚠️ 必须在**不触发边界钳制**的前提下验证：钳制（防止拖出空白）优先级高于锚点不变式，
     * 一旦图被拉到边界，锚点必然漂移 —— 这是有意为之，否则会露出画布底色。
     * 这里取一个放大后图仍能覆盖住锚点、钳制不生效的场景。
     */
    @Test
    fun `双指中心下方的图像点在缩放前后保持不动`() {
        val centroidX = 300f; val centroidY = 400f
        val start = ViewportState(scale = 1f, offsetX = 0f, offsetY = 0f)

        // 变换前，centroid 对应的图像坐标
        val pImageX = (centroidX - start.offsetX) / start.scale
        val pImageY = (centroidY - start.offsetY) / start.scale

        // zoom=2：图 2160×1215。横向超出视口 → 需要锚点计算；
        // 纵向 1215 略小于视口 1244，会被居中钳制，所以只断言横向锚点，
        // 纵向另由 `图纵向未超出视口时自动居中` 覆盖。
        val next = applyTransform(
            scale = start.scale, offsetX = start.offsetX, offsetY = start.offsetY,
            centroidX = centroidX, centroidY = centroidY, panX = 0f, panY = 0f, zoom = 2f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )

        val backX = next.offsetX + pImageX * next.scale
        assertEquals("锚点 X 漂移了", centroidX, backX, 1e-2f)
    }

    /**
     * 纵向同样要验证锚点不变式，但需要一个**图高真正超出视口、且缩放后锚点仍落在
     * 合法平移区间内**的初始状态：先把图放大到 3 倍（高 1822.5 > 1244）并让视口
     * 恰好覆盖图中部，再等比放大，此时纵向钳制不生效，锚点应严格不动。
     */
    @Test
    fun `纵向锚点在缩放时不漂移`() {
        // 图 3240×1822.5，视口 1080×1244 → 纵向可平移区间 [-578.5, 0]
        val start = ViewportState(scale = 3f, offsetX = -1000f, offsetY = -300f)
        val centroidX = 500f; val centroidY = 700f

        val pImageX = (centroidX - start.offsetX) / start.scale
        val pImageY = (centroidY - start.offsetY) / start.scale

        // 再放大 1.2 倍：图高 2187 > 1244，纵向仍超出
        val next = applyTransform(
            scale = start.scale, offsetX = start.offsetX, offsetY = start.offsetY,
            centroidX = centroidX, centroidY = centroidY, panX = 0f, panY = 0f, zoom = 1.2f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )

        assertEquals("precondition: 应未触及 MIN/MAX 截断", 3.6f, next.scale, 1e-3f)
        assertEquals("锚点 X 漂移了", centroidX, next.offsetX + pImageX * next.scale, 1e-2f)
        assertEquals("锚点 Y 漂移了", centroidY, next.offsetY + pImageY * next.scale, 1e-2f)
    }

    /**
     * 钳制优先于锚点：当锚点位置会把图拉出边界（露出空白）时，宁可让锚点漂移。
     * 这是刻意的取舍 —— 露出一大片画布底色比「指尖下的点轻微偏移」糟糕得多。
     */
    @Test
    fun `锚点会越界时钳制优先于锚点不变式`() {
        val centroidX = 100f; val centroidY = 100f
        val next = applyTransform(
            scale = 1f, offsetX = 0f, offsetY = 0f,
            centroidX = centroidX, centroidY = centroidY, panX = 0f, panY = 0f, zoom = 2f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        // 结果必须仍在合法区间，不能为了锚点而露出空白
        val w = viewW * next.scale
        val h = imageH * next.scale
        if (w > viewW) {
            assertTrue("横向越界了", next.offsetX in (viewW - w)..0f)
        }
        if (h > viewH) {
            assertTrue("纵向越界了", next.offsetY in (viewH - h)..0f)
        }
    }

    @Test
    fun `非中心锚点缩放 锚点同样不动`() {
        // 取一个明显偏离屏幕中心的锚点（左上角区域）。
        // 起始 scale=1.5（图 1620×911），放大 2 倍到 3（图 3240×1822）——
        // 横向区间 [-2160, 0]、纵向 [-578.5, 0]，锚点解算结果都在区间内，不触发钳制。
        val centroidX = 120f; val centroidY = 90f
        val start = ViewportState(scale = 1.5f, offsetX = -100f, offsetY = -200f)
        val pImageX = (centroidX - start.offsetX) / start.scale
        val pImageY = (centroidY - start.offsetY) / start.scale

        val next = applyTransform(
            scale = start.scale, offsetX = start.offsetX, offsetY = start.offsetY,
            centroidX = centroidX, centroidY = centroidY, panX = 0f, panY = 0f, zoom = 2f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )

        // 锚点解算：offsetY = 90 - (90 + 200) * 2 = -490，落在 [-578.5, 0] 内 → 不钳制
        assertEquals("precondition: 纵向不该被钳制", -490f, next.offsetY, 1e-2f)
        assertEquals(centroidX, next.offsetX + pImageX * next.scale, 1e-2f)
        assertEquals(centroidY, next.offsetY + pImageY * next.scale, 1e-2f)
    }

    @Test
    fun `zoom 为 2 时缩放比例翻倍`() {
        val next = applyTransform(
            scale = 1f, offsetX = 0f, offsetY = 0f,
            centroidX = 500f, centroidY = 500f, panX = 0f, panY = 0f, zoom = 2f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals(2f, next.scale, 1e-4f)
    }

    @Test
    fun `缩放被 MAX_SCALE 截断时 锚点仍不漂移`() {
        // 已接近上限，再放大 3 倍会被截断到 MAX_SCALE —— 必须按截断后的实际
        // 倍率重算 offset，否则锚点会漂
        val centroidX = 700f; val centroidY = 500f
        val start = ViewportState(scale = 2f, offsetX = -300f, offsetY = -100f)
        val pImageX = (centroidX - start.offsetX) / start.scale
        val pImageY = (centroidY - start.offsetY) / start.scale

        val next = applyTransform(
            scale = start.scale, offsetX = start.offsetX, offsetY = start.offsetY,
            centroidX = centroidX, centroidY = centroidY, panX = 0f, panY = 0f, zoom = 3f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )

        assertEquals("应被截断到 MAX_SCALE", 4f, next.scale, 1e-4f)
        assertEquals(centroidX, next.offsetX + pImageX * next.scale, 1e-2f)
        assertEquals(centroidY, next.offsetY + pImageY * next.scale, 1e-2f)
    }

    // ── 单指拖动退化为纯平移 ─────────────────────────────────────────────

    @Test
    fun `zoom 为 1 时 pan 表现为纯平移`() {
        // scale=3：图 3240×1822.5，横纵都超出视口（1080×1244）→ 两个方向都走平移分支
        val start = ViewportState(scale = 3f, offsetX = -500f, offsetY = -300f)
        val next = applyTransform(
            scale = start.scale, offsetX = start.offsetX, offsetY = start.offsetY,
            centroidX = 400f, centroidY = 400f, panX = -50f, panY = 30f, zoom = 1f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals("缩放不该变", start.scale, next.scale, 1e-4f)
        assertEquals("单指拖动应等价于平移", start.offsetX + (-50f), next.offsetX, 1e-3f)
        assertEquals("单指拖动应等价于平移", start.offsetY + 30f, next.offsetY, 1e-3f)
    }

    @Test
    fun `图纵向未超出视口时自动居中`() {
        // scale=2：图高 1215 < 视口 1244 → 纵向不该响应拖动，而应居中
        val next = applyTransform(
            scale = 2f, offsetX = -500f, offsetY = -999f,
            centroidX = 400f, centroidY = 400f, panX = 0f, panY = 999f, zoom = 1f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        val h = imageH * next.scale
        assertEquals("图比视口矮时纵向必须居中，不能被拖走", (viewH - h) / 2f, next.offsetY, 1e-3f)
    }

    // ── 边界钳制 ─────────────────────────────────────────────────────────

    @Test
    fun `图小于视口时水平居中`() {
        // scale 被压到 MIN_SCALE(0.8) 时，图宽 864 < viewW 1080 → 应居中
        val next = applyTransform(
            scale = 1f, offsetX = 0f, offsetY = 0f,
            centroidX = 500f, centroidY = 500f, panX = 1000f, panY = 0f, zoom = 0.5f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals(0.8f, next.scale, 1e-4f)
        val w = viewW * next.scale
        assertEquals("图比视口窄时必须居中，不能被拖走", (viewW - w) / 2f, next.offsetX, 1e-3f)
    }

    @Test
    fun `放大后拖动不会把图拖出边界`() {
        // scale=3：图宽 3240 > 视口 1080，横向可平移
        // 向右猛拖（panX 很大）→ offsetX 应被钳到 0（左边界）
        val right = applyTransform(
            scale = 3f, offsetX = -500f, offsetY = -300f,
            centroidX = 400f, centroidY = 400f, panX = 99999f, panY = 0f, zoom = 1f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals("拖到最右时左边界应为 0", 0f, right.offsetX, 1e-3f)

        // 向左猛拖 → offsetX 应被钳到 viewW - w
        val left = applyTransform(
            scale = 3f, offsetX = -500f, offsetY = -300f,
            centroidX = 400f, centroidY = 400f, panX = -99999f, panY = 0f, zoom = 1f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals(viewW - viewW * 3f, left.offsetX, 1e-3f)
    }

    @Test
    fun `缩小到 MIN_SCALE 后继续缩小不会更小`() {
        val next = applyTransform(
            scale = 0.8f, offsetX = 0f, offsetY = 0f,
            centroidX = 400f, centroidY = 400f, panX = 0f, panY = 0f, zoom = 0.1f,
            viewW = viewW, viewH = viewH, imageH = imageH,
        )
        assertEquals(0.8f, next.scale, 1e-4f)
    }
}
