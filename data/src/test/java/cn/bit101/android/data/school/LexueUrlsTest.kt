package cn.bit101.android.data.school

import cn.bit101.api.option.PROD_URLS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 乐学主页地址的**选表与回退链**（纯逻辑部分）。
 *
 * 锁的是「按当前环境选对那张地址表」——选错的后果不是崩溃，而是**打开一个打不开的地址**
 * （校外用户拿到校内地址、点进去一片白），这在真机上很难一眼看出是哪儿的问题。
 */
class LexueUrlsTest {

    private val campus = "https://lexue.bit.edu.cn"
    private val viaWebVpn =
        "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/"

    @Test
    fun `校内用 campus 表`() {
        assertEquals(
            campus,
            pickLexueHome(webVpn = false, campus = campus, webVpnUrl = viaWebVpn),
        )
    }

    @Test
    fun `校外用 webvpn 表`() {
        assertEquals(
            viaWebVpn,
            pickLexueHome(webVpn = true, campus = campus, webVpnUrl = viaWebVpn),
        )
    }

    /** SDK 地址表读不出来时，至少给一个能用的校内地址，而不是空串（点了没反应）。 */
    @Test
    fun `当前模式的表缺失时回退静态配置`() {
        assertEquals(
            PROD_URLS.lexueUrl,
            pickLexueHome(webVpn = false, campus = null, webVpnUrl = viaWebVpn),
        )
        assertEquals(
            PROD_URLS.lexueUrl,
            pickLexueHome(webVpn = true, campus = campus, webVpnUrl = null),
        )
    }

    /** 空白串与缺失同义：不能拿一个空地址去开 WebView。 */
    @Test
    fun `空白地址同样回退`() {
        assertEquals(
            PROD_URLS.lexueUrl,
            pickLexueHome(webVpn = false, campus = "   ", webVpnUrl = null),
        )
    }
}
