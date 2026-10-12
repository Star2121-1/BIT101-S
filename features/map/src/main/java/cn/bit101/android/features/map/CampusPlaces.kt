package cn.bit101.android.features.map

import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData
import cn.bit101.android.data.school.CampusLocations

/**
 * 校园里的一个**可定位地点**（教学楼为主）。
 *
 * [x] / [y] 是 MapCompose 的归一化坐标，与 [MapCampus] 同一套换算
 * （`x=(lon+180)/360`、`y=0.5-ln(tan(45°+lat/2))/2π`），单位与量级都对得上。
 */
data class CampusPlace(
    /** 展示名，如 `文萃楼 I` */
    val name: String,

    /** 所在校区（对应 `MapCampus.name`，用于切换按钮高亮） */
    val campus: String,

    val x: Double,
    val y: Double,

    /**
     * 课表 / 考试地点里可能出现的写法（匹配用的**别名**）。
     *
     * ⚠️ 教务系统给的是自由文本（`文萃楼I404`、`综教B301`），
     * 同一个楼有好几种写法，只能靠别名表兜住。
     */
    val aliases: List<String>,

    /** 定位时的缩放倍率；比校区默认更近（要看清一栋楼）。 */
    val scale: Float = SCALE,
) {
    companion object {
        /**
         * 看一栋楼时的缩放：校区级是 `0.25`（≈ z17），这里是 `0.5`（≈ z18）。
         *
         * ⚠️ 不能再放大：瓦片服务**只提供 z15~z18**，超过 z18 会开始取不到瓦片。
         */
        const val SCALE = 0.5f
    }
}

/**
 * 教室/考场字符串 → 地点。
 *
 * ## ⚠️ 坐标表**不在这里**（2026-10-12 下沉）
 *
 * 坐标原本只写在本文件里，但「现在去哪儿自习」要**算距离**，而它住在 `data` 层 ——
 * `data` 不能依赖 `features`。为了不出现第二份会漂移的表，
 * 数据下沉到 [CampusLocations]（`data/school`），本文件改成**引用**它。
 *
 * 这里只保留地图页特有的东西：[CampusPlace.SCALE] 与 [MapCampus] 的换算。
 */
object CampusPlaces {

    /** 全部地点；**数据源是 [CampusLocations.ALL]**，这里只补上地图页要的缩放。 */
    val ALL: List<CampusPlace> = CampusLocations.ALL.map {
        CampusPlace(
            name = it.name,
            campus = it.campusName,
            x = it.x,
            y = it.y,
            aliases = it.aliases,
        )
    }

    /**
     * 归一化：去空白、全角转半角、字母转大写。**实现只有一份**，见 [CampusLocations.normalize]。
     */
    fun normalize(raw: String): String = CampusLocations.normalize(raw)

    /**
     * 把教室/考场字符串匹配到地点；匹配不到返回 null。
     *
     * 取**命中的最长别名**：`文萃楼I404` 会同时命中 `文萃楼I`（4 字）与 `文萃楼`（3 字），
     * 必须选长的那个，否则所有区都会被折到楼群中心。
     */
    fun match(raw: String): CampusPlace? {
        val name = CampusLocations.match(raw)?.name ?: return null
        return ALL.firstOrNull { it.name == name }
    }

    /**
     * 按**校区名**查校区（匹配不到返回 null）。
     *
     * `internal`：[MapCampus] 是地图模块内部类型，不对外暴露。
     */
    internal fun campusOf(campusName: String): MapCampus? =
        MapCampus.ALL.firstOrNull { it.name == campusName }

    /** 「图」页的路由 —— 从别处跳过来时用（底栏页只认这个 route）。 */
    val route: String get() = PageShowOnNav.Map.toPageData().value
}

/**
 * 一次「跳到地图并定位到某处」的请求。
 *
 * ⚠️ 为什么需要一个中转：底栏页之间的跳转走 `GotoRequest`，而它**只带 route**，
 * 传不了坐标参数（见 `docs/widget.md`）。所以位置先放在这里，地图页进来时取走。
 */
data class MapTarget(
    val name: String,
    val campus: String,
    val x: Double,
    val y: Double,
    val scale: Float = CampusPlace.SCALE,
)

object MapTargetHolder {

    @Volatile
    private var pending: MapTarget? = null

    @Synchronized
    fun request(target: MapTarget) {
        pending = target
    }

    /** 取走待跳转目标（**一次性**：取完即清空）。 */
    @Synchronized
    fun consume(): MapTarget? {
        val t = pending
        pending = null
        return t
    }

    fun from(place: CampusPlace): MapTarget =
        MapTarget(place.name, place.campus, place.x, place.y, place.scale)
}
