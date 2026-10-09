package cn.bit101.android.features.nfc.logic

import cn.bit101.android.config.setting.base.AppRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「贴卡即用」的判据单测。
 *
 * ## 为什么这几条值得单独钉
 *
 * 这个功能在真机上的失败方式是**沉默**的：贴了卡什么都没发生，用户只会觉得坏了，
 * 而从现象完全看不出是「卡号没比中」还是「开关没生效」还是「reader mode 被顶掉了」。
 * 所以凡是**能在这里定死的判断，就别留给真机去猜**。
 *
 * 用的卡号是真卡实测值 `7775F07B`（见 `docs/nfc-card-probe.md`）。
 */
class NfcTapLogicTest {

    private val myUid = "7775F07B"

    // ------------------------------------------------------------ 卡号归一化

    /**
     * 同一个卡号的不同写法必须比中。
     *
     * 来源不同写法就不同：本 App 存的是 `7775F07B`，而用户从别处抄来、
     * 或第三方工具给出来的常是 `77:75:F0:7B`、`7775f07b` 这种。
     * 直接 `==` 会判成两张卡 —— 现象就是「我的卡贴上去没反应」。
     */
    @Test
    fun `卡号归一化 去掉分隔符并统一大写`() {
        assertEquals("7775F07B", NfcTapLogic.normalizeUid("77:75:F0:7B"))
        assertEquals("7775F07B", NfcTapLogic.normalizeUid("77-75-f0-7b"))
        assertEquals("7775F07B", NfcTapLogic.normalizeUid("7775f07b"))
        assertEquals("7775F07B", NfcTapLogic.normalizeUid(" 7775F07B "))
    }

    @Test
    fun `卡号归一化 空值给空串`() {
        assertEquals("", NfcTapLogic.normalizeUid(null))
        assertEquals("", NfcTapLogic.normalizeUid(""))
        assertEquals("", NfcTapLogic.normalizeUid("   "))
    }

    // ------------------------------------------------------------ 是不是我的卡

    @Test
    fun `我的卡 写法不同也算同一张`() {
        assertTrue(NfcTapLogic.isMyCard(myUid, "77:75:F0:7B"))
        assertTrue(NfcTapLogic.isMyCard("7775f07b", "7775F07B"))
    }

    @Test
    fun `不是我的卡 卡号不同一律判否`() {
        assertFalse(NfcTapLogic.isMyCard(myUid, "1234ABCD"))
    }

    /**
     * 没存过卡时必须判否。
     *
     * 这条守的是「放行」方向：如果有人把空值当成「匹配任意」，那么**没读过卡的机器上
     * 贴任何一张卡都会触发跳转** —— 那不是便利，是骚扰，而且在真机上会表现成
     * 「别人的卡也能开我的 App」。
     */
    @Test
    fun `没存过卡时 不给任何卡放行`() {
        assertFalse(NfcTapLogic.isMyCard(null, myUid))
        assertFalse(NfcTapLogic.isMyCard("", myUid))
        assertFalse(NfcTapLogic.isMyCard("   ", myUid))
        // 两边都空也不能算同一张
        assertFalse(NfcTapLogic.isMyCard(null, null))
        assertFalse(NfcTapLogic.isMyCard("", ""))
    }

    @Test
    fun `贴上来的卡号读不到时 判否`() {
        assertFalse(NfcTapLogic.isMyCard(myUid, null))
        assertFalse(NfcTapLogic.isMyCard(myUid, ""))
    }

    // ------------------------------------------------------------ 该去哪个页面

    /**
     * 未登录先去登录页，**不管用户选的目标页是什么**。
     *
     * 理由：课表 / 座位这些目标页几乎都要求登录，直接跳过去只是撞上「请先登录」的门禁。
     * 而登录页会自己用本机凭据静默登一次 —— 有凭据就直接进去、没有才要人输，
     * 这正好就是「贴卡登录」想要的效果，且没有任何凭据被放进卡里。
     */
    @Test
    fun `未登录时 一律先去登录页`() {
        assertEquals(AppRoutes.LOGIN, NfcTapLogic.target("schedule", loggedIn = false))
        assertEquals(AppRoutes.LOGIN, NfcTapLogic.target("seat", loggedIn = false))
        // 连用户根本没选过目标页时也是登录页
        assertEquals(AppRoutes.LOGIN, NfcTapLogic.target(null, loggedIn = false))
    }

    @Test
    fun `已登录时 去用户选的目标页`() {
        assertEquals("seat", NfcTapLogic.target("seat", loggedIn = true))
        assertEquals("login", NfcTapLogic.target("login", loggedIn = true))
    }

    @Test
    fun `已登录但没选过目标页 落到默认页`() {
        assertEquals(NfcTapLogic.DEFAULT_ROUTE, NfcTapLogic.target(null, loggedIn = true))
        assertEquals(NfcTapLogic.DEFAULT_ROUTE, NfcTapLogic.target("", loggedIn = true))
        assertEquals(NfcTapLogic.DEFAULT_ROUTE, NfcTapLogic.target("   ", loggedIn = true))
    }

    /** 默认落点必须是**真实存在**的底栏页 route，否则跳过去会是一次静默失败。 */
    @Test
    fun `默认落点是个真实存在的页面`() {
        assertTrue(
            "默认 route 「${NfcTapLogic.DEFAULT_ROUTE}」不在底栏页列表里",
            NdefShortcutLogic.isSupported(NfcTapLogic.DEFAULT_ROUTE),
        )
    }
}
