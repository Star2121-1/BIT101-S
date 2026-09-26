package cn.bit101.android.features.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * [MapCampus] 的坐标校验。
 *
 * ## 为什么值得为「几个常量」写单测
 *
 * 这些坐标是**手抄/换算进来的经纬度**，一位数字写错就会让按钮把地图带到几十公里外，
 * 而那是纯 UI 行为、单测覆盖不到、真机上还得靠肉眼发现。所以这里做两件事：
 *
 * 1. 把每个校区**反算回经纬度**，断言落在期望的城市范围里（防抄错）
 * 2. 断言经纬度与预期值相差不到 ~500 m（防「差一位」这种微妙错误）
 *
 * 换算口径与瓦片服务 `map.bit101.flwfdd.xyz` 一致（全球墨卡托，见 [MapCampus] 的注释）。
 * ⚠️ **嘉兴校区**没有现成坐标，是从百度地图 POI 的 BD09-MC 逐级换算成 WGS-84 的
 * ——这里反算出来必须回到那对经纬度，否则说明换算/抄写环节出了问题。
 */
class MapCampusTest {

    /** 归一化坐标 → 经纬度（[MapCampus] 注释里那个公式的逆运算）。 */
    private fun lonLatOf(x: Double, y: Double): Pair<Double, Double> {
        val lon = x * 360 - 180
        val lat = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * y))))
        return lon to lat
    }

    /** 反算结果与预期的距离（米，够用即可，不追求大地测量精度）。 */
    private fun distanceMeters(lon: Double, lat: Double, expectLon: Double, expectLat: Double): Double {
        val dLon = (lon - expectLon) * 111_320 * Math.cos(Math.toRadians(lat))
        val dLat = (lat - expectLat) * 110_540
        return Math.hypot(dLon, dLat)
    }

    /**
     * 每个校区：反算出来的经纬度必须**贴着**注释里标的那对经纬度。
     *
     * 容差 500 m —— 比一个校园小，但足够吸收经纬度只写到小数点后 4 位的误差。
     */
    @Test
    fun `每个校区坐标都能反算回真实经纬度`() {
        val expected = mapOf(
            "良乡校区" to (116.1666 to 39.7290),
            "中关村校区" to (116.3099 to 39.9584),
            "珠海校区" to (113.5370 to 22.3708),
            "嘉兴校区" to (120.7399 to 30.8326),
        )

        MapCampus.ALL.forEach { campus ->
            val (lon, lat) = lonLatOf(campus.x, campus.y)
            val (expLon, expLat) = expected.getValue(campus.name)

            val away = distanceMeters(lon, lat, expLon, expLat)
            assertTrue(
                "${campus.name} 坐标偏差 ${away.toInt()} m，超过 500 m —— 多半是常数抄错了",
                away < 500,
            )
        }
    }

    /** 四个校区必须落在各自的城市里（防「纬度换成了经度」这类整体错位）。 */
    @Test
    fun `校区落在各自的城市范围`() {
        val boxes = mapOf(
            "良乡校区" to listOf(115.8, 116.5, 39.5, 39.9),     // 北京房山
            "中关村校区" to listOf(116.1, 116.5, 39.8, 40.1),   // 北京海淀
            "珠海校区" to listOf(113.3, 113.8, 22.1, 22.6),     // 广东珠海
            "嘉兴校区" to listOf(120.4, 121.0, 30.5, 31.1),     // 浙江嘉兴秀洲
        )

        MapCampus.ALL.forEach { campus ->
            val (lon, lat) = lonLatOf(campus.x, campus.y)
            val (lon0, lon1, lat0, lat1) = boxes.getValue(campus.name)

            assertTrue("${campus.name} 经度 $lon 越界", lon in lon0..lon1)
            assertTrue("${campus.name} 纬度 $lat 越界", lat in lat0..lat1)
        }
    }

    /** 反算-正算往返自洽（公式两侧都写对的交叉验证）。 */
    @Test
    fun `归一化坐标与经纬度互为逆运算`() {
        MapCampus.ALL.forEach { campus ->
            val (lon, lat) = lonLatOf(campus.x, campus.y)

            val x = (lon + 180) / 360
            val f = Math.toRadians(lat)
            val y = 0.5 - ln(tan(Math.PI / 4 + f / 2)) / (2 * Math.PI)

            assertTrue("${campus.name} 往返 x 不一致", abs(x - campus.x) < 1e-9)
            assertTrue("${campus.name} 往返 y 不一致", abs(y - campus.y) < 1e-9)
        }
    }

    /** 按钮上的字必须是一个汉字且互不重复（否则两颗按钮看着一样）。 */
    @Test
    fun `按钮短标签唯一且单字`() {
        MapCampus.ALL.forEach { campus ->
            assertEquals("${campus.name} 的按钮标签应为单字", 1, campus.short.length)
        }

        val shorts = MapCampus.ALL.map { it.short }
        assertEquals("按钮标签不能重复", shorts.size, shorts.distinct().size)
    }

    /** 归一化坐标必须在 0..1 内，且每个校区都要有名字。 */
    @Test
    fun `坐标在有效范围内且校名非空`() {
        assertTrue("至少要有一个校区", MapCampus.ALL.isNotEmpty())

        MapCampus.ALL.forEach { campus ->
            assertTrue("${campus.name} 的 x 越界", campus.x in 0.0..1.0)
            assertTrue("${campus.name} 的 y 越界", campus.y in 0.0..1.0)
            assertTrue("校名不能为空", campus.name.isNotBlank())
            assertTrue("缩放倍率必须为正", campus.scale > 0f)
        }
    }
}
