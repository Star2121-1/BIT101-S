package cn.bit101.android.features.map

import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData

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
 * ## 数据来源（不是我编的）
 *
 * 全部坐标来自 **OpenStreetMap**（Overpass API，2026-09-28 拉取）：
 * OSM 上良乡与中关村两个校区都有**带名字的建筑多边形**，直接取 `center` 即可，
 * 不需要靠猜或试。中关村的楼还用校区多边形（`amenity=university`，way 30748230）
 * 的外接框筛过一遍 —— 那一带紧挨着人大与农科院，**不筛会把别人的楼当成我们的**。
 *
 * ## ⚠️ 两个刻意的保守
 *
 * 1. **良乡只收主校区**（约 39.722°N 以北）。OSM 在 39.715~39.720 还有一片
 *    「图书馆 / 经济学院 / 计算机楼 / 行政楼」的建筑群，但那一片**不属于北理工**
 *    （良乡高教园区里还有别的学校）—— **宁可不收，也不把人指到别的学校去**。
 *    同理，良乡的「图书馆」不在表里（徐特立图书馆的位置 OSM 未收录）。
 * 2. **珠海 / 嘉兴暂不收录**：那两片 OSM 数据太稀（嘉兴整个院区只有几栋无名建筑），
 *    没有可信的建筑坐标。匹配不到时 UI 会明说「暂未收录」，**不会乱跳**。
 *
 * ## 别名里的「不带区」条目
 *
 * `文萃楼`（不带 A~M）与 `理学楼`（不带 A/B/C）用的是**各分区坐标的几何中心**：
 * 教务偶尔只写「文萃楼305」不写区号，这时跳到楼群中心比什么都不做有用。
 * 匹配按**最长别名**优先，所以 `文萃楼I404` 一定会落到文萃楼 I 而不是中心点。
 */
object CampusPlaces {

    /** 全部地点。 */
    val ALL: List<CampusPlace> = listOf(
        // ── 良乡校区（本科生主要上课地，OSM 数据最全）────────────────────
        CampusPlace("文萃楼A", "良乡校区", 0.822690, 0.379552, listOf("文萃楼A", "文萃A")),
        CampusPlace("文萃楼B", "良乡校区", 0.822689, 0.379554, listOf("文萃楼B", "文萃B")),
        CampusPlace("文萃楼C", "良乡校区", 0.822689, 0.379555, listOf("文萃楼C", "文萃C")),
        CampusPlace("文萃楼D", "良乡校区", 0.822688, 0.379555, listOf("文萃楼D", "文萃D")),
        CampusPlace("文萃楼E", "良乡校区", 0.822687, 0.379555, listOf("文萃楼E", "文萃E")),
        CampusPlace("文萃楼F", "良乡校区", 0.822688, 0.379553, listOf("文萃楼F", "文萃F")),
        CampusPlace("文萃楼G", "良乡校区", 0.822687, 0.379554, listOf("文萃楼G", "文萃G")),
        CampusPlace("文萃楼H", "良乡校区", 0.822687, 0.379549, listOf("文萃楼H", "文萃H")),
        CampusPlace("文萃楼I", "良乡校区", 0.822688, 0.379549, listOf("文萃楼I", "文萃I")),
        CampusPlace("文萃楼J", "良乡校区", 0.822687, 0.379548, listOf("文萃楼J", "文萃J")),
        CampusPlace("文萃楼K", "良乡校区", 0.822688, 0.379548, listOf("文萃楼K", "文萃K")),
        CampusPlace("文萃楼L", "良乡校区", 0.822689, 0.379548, listOf("文萃楼L", "文萃L")),
        CampusPlace("文萃楼M", "良乡校区", 0.822689, 0.379549, listOf("文萃楼M", "文萃M")),
        CampusPlace("文萃楼", "良乡校区", 0.822688, 0.379551, listOf("文萃楼", "文萃")),
        CampusPlace("理学楼A", "良乡校区", 0.822682, 0.379565, listOf("理学楼A", "理学A")),
        CampusPlace("理学楼B", "良乡校区", 0.822682, 0.379564, listOf("理学楼B", "理学B")),
        CampusPlace("理学楼C", "良乡校区", 0.822682, 0.379562, listOf("理学楼C", "理学C")),
        CampusPlace("理学楼", "良乡校区", 0.822682, 0.379564, listOf("理学楼", "理学")),
        CampusPlace("综合教学大楼", "良乡校区", 0.822681, 0.379549, listOf("综合教学大楼", "综教", "综教楼")),
        // ⚠️「理教楼」是**真机课表里实际出现的写法**（2026-09-28 在 NP05J 上看到
        // 「理教楼406」）。理科教学楼是良乡南校区的日常教学主楼（校内文件里叫
        //「数字化基础教学楼 1 号楼」），学生与教务都简称「理教」——
        // 注意**不是**理学楼（那是南邻的另一组三栋楼）。
        CampusPlace(
            "理科教学楼", "良乡校区", 0.822681, 0.379560,
            listOf("理科教学楼", "理科楼", "理教楼", "理教"),
        ),
        CampusPlace("工业生态楼", "良乡校区", 0.822680, 0.379575, listOf("工业生态楼", "生态楼")),
        CampusPlace("物理实验中心", "良乡校区", 0.822679, 0.379564, listOf("物理实验中心", "物理实验楼")),
        CampusPlace("化学实验中心", "良乡校区", 0.822679, 0.379568, listOf("化学实验中心", "化学实验楼")),
        CampusPlace("工程训练中心", "良乡校区", 0.822688, 0.379575, listOf("工程训练中心")),
        CampusPlace("文化体育中心", "良乡校区", 0.822697, 0.379554, listOf("文化体育中心", "文体中心")),
        CampusPlace("学生服务中心", "良乡校区", 0.822671, 0.379552, listOf("学生服务中心")),
        CampusPlace("行政楼", "良乡校区", 0.822682, 0.379552, listOf("行政楼")),
        CampusPlace("文博中心", "良乡校区", 0.822696, 0.379549, listOf("文博中心")),

        // ── 中关村校区（已按校区多边形筛过，排除人大 / 农科院的楼）────────
        CampusPlace("主楼", "中关村校区", 0.823098, 0.378730, listOf("主楼")),
        CampusPlace("中心教学楼", "中关村校区", 0.823086, 0.378731, listOf("中心教学楼", "中教")),
        CampusPlace("信息教学楼", "中关村校区", 0.823086, 0.378737, listOf("信息教学楼", "信息楼")),
        CampusPlace("研究生教学楼", "中关村校区", 0.823089, 0.378738, listOf("研究生教学楼", "研究生楼")),
        CampusPlace("信息科技实验楼", "中关村校区", 0.823092, 0.378737, listOf("信息科技实验楼")),
        CampusPlace("1#教学楼", "中关村校区", 0.823098, 0.378727, listOf("1#教学楼", "1号教学楼", "一号教学楼")),
        CampusPlace("3#教学楼", "中关村校区", 0.823090, 0.378728, listOf("3#教学楼", "3号教学楼", "三号教学楼")),
        CampusPlace("4#教学楼", "中关村校区", 0.823090, 0.378734, listOf("4#教学楼", "4号教学楼", "四号教学楼")),
        CampusPlace("5#教学楼", "中关村校区", 0.823099, 0.378733, listOf("5#教学楼", "5号教学楼", "五号教学楼")),
        CampusPlace("7#教学楼", "中关村校区", 0.823086, 0.378734, listOf("七号教学楼", "7号教学楼", "7#教学楼")),
        CampusPlace("车辆重点实验楼", "中关村校区", 0.823098, 0.378737, listOf("车辆重点实验楼")),
        CampusPlace("图书馆", "中关村校区", 0.823094, 0.378734, listOf("图书馆")),
        CampusPlace("体育馆", "中关村校区", 0.823079, 0.378732, listOf("体育馆")),
    )

    /**
     * 归一化：去空白、全角转半角、字母转大写。
     *
     * ⚠️ 教务数据里混着全角数字与全角字母（`Ｉ４０４` 这种），不转就匹配不上。
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch.isWhitespace() -> Unit
                ch in '\uFF01'..'\uFF5E' -> sb.append((ch.code - 0xFEE0).toChar())
                ch == '\u3000' -> Unit
                else -> sb.append(ch.uppercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 把教室/考场字符串匹配到地点；匹配不到返回 null。
     *
     * 取**命中的最长别名**：`文萃楼I404` 会同时命中 `文萃楼I`（4 字）与 `文萃楼`（3 字），
     * 必须选长的那个，否则所有区都会被折到楼群中心。
     */
    fun match(raw: String): CampusPlace? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        var best: CampusPlace? = null
        var bestLen = 0
        ALL.forEach { place ->
            place.aliases.forEach { alias ->
                val a = normalize(alias)
                if (a.length > bestLen && text.contains(a)) {
                    best = place
                    bestLen = a.length
                }
            }
        }
        return best
    }

    /**
     * 该地点所属校区（匹配不到校区名时返回 null）。
     *
     * `internal`：[MapCampus] 是地图模块内部类型，不对外暴露。
     */
    internal fun campusOf(place: CampusPlace): MapCampus? =
        MapCampus.ALL.firstOrNull { it.name == place.campus }

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
