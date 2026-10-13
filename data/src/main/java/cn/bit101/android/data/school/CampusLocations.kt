package cn.bit101.android.data.school

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * 校园建筑的**坐标表** —— 全 App 只有这一份。
 *
 * ## 为什么放在 data 层
 *
 * 它有**两个消费方**：地图页（跳过去定位）与「现在去哪儿自习」（算距离）。
 * 原来只在地图模块里，而 `data` **不能依赖 `features`** ⇒ 为了不让第二份表出现，
 * 表下沉到这里，地图那边改成引用 [CampusLocations]。
 *
 * ## 数据来源（不是我编的）
 *
 * 全部坐标来自 **OpenStreetMap**（Overpass API，2026-09-28 拉取）：
 * OSM 上良乡与中关村两个校区都有**带名字的建筑多边形**，直接取 `center` 即可。
 *
 * ## ⚠️ 这张表有守卫，别手改坐标
 *
 * 良乡校区的 OSM 建筑清单已固化成 fixture
 *（`src/test/resources/campus/osm-liangxiang-2026-10-13.json`，含 Overpass 查询原文与
 * relation id，可重跑更新），由 `CampusOsmSurveyTest`（在 test 源集里）
 * 做**双向覆盖**：
 *
 * - 本表每条坐标都要落在 OSM 某栋建筑的 **2 格**（约 62 米）内（防手编坐标）
 * - OSM 里的教学类建筑必须**在本表内**，或出现在该测试的**豁免表**里并写明理由
 *
 * ⇒ **OSM 新增一栋教学楼时那条测试会红**，那不是误报 —— 是要你去真机核对该楼在
 *   教务里叫什么，然后补别名。这正是上一版栽过的坑（别名表认不出接口真名
 *   「综合教学楼大楼A/B」而测试全绿）。
 * 中关村的楼还用校区多边形（`amenity=university`，way 30748230）的外接框筛过一遍 ——
 * 那一带紧挨着人大与农科院，**不筛会把别人的楼当成我们的**。
 *
 * ### ⚠️ 两个刻意的保守
 *
 * 1. **良乡只收主校区**（约 39.722°N 以北）。OSM 在 39.715~39.720 还有一片
 *    「图书馆 / 经济学院 / 计算机楼 / 行政楼」的建筑群，但那一片**不属于北理工**
 *    （良乡高教园区里还有别的学校）—— **宁可不收，也不把人指到别的学校去**。
 * 2. **珠海 / 嘉兴暂不收录**：那两片 OSM 数据太稀，没有可信的建筑坐标。
 *    匹配不到时 UI 会明说「暂未收录」，**不会乱跳**。
 *
 * ## ⚠️⚠️ 坐标精度只有约 31 米一格
 *
 * 存的是**六位小数**。在良乡的纬度上，归一化坐标 1 个最小单位 ≈ 31 米：
 *
 * | 实测 | 结果 |
 * |---|---|
 * | 文萃楼I → 文萃楼H | 31 m |
 * | 文萃楼I → 文萃楼K | 31 m |
 * | 文萃楼I → 文萃楼M | 31 m |
 *
 * 这三栋**实际上并不重合**，只是被舍入到了同一个格子里。
 * ⇒ 结论：**只能用来分档**（[DistanceBucket]），
 *   **绝不能报「精确到米」的数字** —— 那是假的精度。
 */
object CampusLocations {

    /** 一个地点（建筑）。 */
    data class Location(
        /** 展示名，如 `文萃楼 I`（这里是 `文萃楼I`） */
        val name: String,
        /** 校区名（与地图页的校区按钮对应） */
        val campusName: String,
        /** 归一化 Web-Mercator 横坐标（`(lon+180)/360`） */
        val x: Double,
        /** 归一化 Web-Mercator 纵坐标（`0.5-ln(tan(45°+lat/2))/2π`） */
        val y: Double,
        /**
         * 课表 / 考试地点里可能出现的写法（匹配用的**别名**）。
         *
         * ⚠️ 教务系统给的是自由文本（`文萃楼I404`、`综教B301`），同一个楼有好几种写法，
         * 只能靠别名表兜住。
         */
        val aliases: List<String>,
    )

    /** 全部地点。 */
    val ALL: List<Location> = listOf(
        // ── 良乡校区（本科生主要上课地，OSM 数据最全）────────────────────
        Location("文萃楼A", "良乡校区", 0.822690, 0.379552, listOf("文萃楼A", "文萃A")),
        Location("文萃楼B", "良乡校区", 0.822689, 0.379554, listOf("文萃楼B", "文萃B")),
        Location("文萃楼C", "良乡校区", 0.822689, 0.379555, listOf("文萃楼C", "文萃C")),
        Location("文萃楼D", "良乡校区", 0.822688, 0.379555, listOf("文萃楼D", "文萃D")),
        Location("文萃楼E", "良乡校区", 0.822687, 0.379555, listOf("文萃楼E", "文萃E")),
        Location("文萃楼F", "良乡校区", 0.822688, 0.379553, listOf("文萃楼F", "文萃F")),
        Location("文萃楼G", "良乡校区", 0.822687, 0.379554, listOf("文萃楼G", "文萃G")),
        Location("文萃楼H", "良乡校区", 0.822687, 0.379549, listOf("文萃楼H", "文萃H")),
        Location("文萃楼I", "良乡校区", 0.822688, 0.379549, listOf("文萃楼I", "文萃I")),
        Location("文萃楼J", "良乡校区", 0.822687, 0.379548, listOf("文萃楼J", "文萃J")),
        Location("文萃楼K", "良乡校区", 0.822688, 0.379548, listOf("文萃楼K", "文萃K")),
        Location("文萃楼L", "良乡校区", 0.822689, 0.379548, listOf("文萃楼L", "文萃L")),
        Location("文萃楼M", "良乡校区", 0.822689, 0.379549, listOf("文萃楼M", "文萃M")),
        // ⚠️ 不带区号的写法（教务偶尔只写「文萃楼305」）用各分区的**几何中心**
        Location("文萃楼", "良乡校区", 0.822688, 0.379551, listOf("文萃楼", "文萃")),
        // ⚠️⚠️「文萃楼圆形报告厅」是**空教室接口里的真实楼名**（2026-10-13 真机 dump：
        //    它和「文萃楼B…M」并列出现在楼列表里，是**独立的一栋**）。
        //    不给它单独一条的后果：别名「文萃楼」是它的子串 ⇒ 它会被折到上面那条
        //    「文萃楼」上，于是**地点名报成「文萃楼」**（坐标恰好同格，所以距离看不出来，
        //    只有名字是错的 —— 铁律 0.4 那种「数字对、主语错」）。
        // ⚠️ 坐标取自 OSM 在该位置的独立建筑「圆形教学楼」（relation 12485117，见
        //    `src/test/resources/campus/osm-liangxiang-2026-10-13.json`）。
        //    **它与接口楼名的同栋关系未证实** —— 两者位置差 ≤1 格（31 米），
        //    足以正确分档；不据它做任何精确断言。
        Location(
            "文萃楼圆形报告厅", "良乡校区", 0.822687, 0.379551,
            // ⚠️ 别名**绝不能**含「文萃楼」：那会把整个文萃楼群吞掉
            listOf("文萃楼圆形报告厅", "圆形报告厅"),
        ),
        Location("理学楼A", "良乡校区", 0.822682, 0.379565, listOf("理学楼A", "理学A")),
        Location("理学楼B", "良乡校区", 0.822682, 0.379564, listOf("理学楼B", "理学B")),
        Location("理学楼C", "良乡校区", 0.822682, 0.379562, listOf("理学楼C", "理学C")),
        Location("理学楼", "良乡校区", 0.822682, 0.379564, listOf("理学楼", "理学")),
        // ⚠️⚠️「综合教学楼大楼A/B」是**空教室接口里的真实楼名**（2026-10-13 真机 dump 核实：
        //    楼列表第 13/14 项就是「综合教学楼大楼A」「综合教学楼大楼B」）。
        //    它比课表里的「综教B301」多一个「楼」字，而旧别名表里
        //    「综合教学大楼 / 综教 / 综教楼」**一个都不是它的子串** ⇒ 这栋楼
        //    **永远算不出距离、也永远不被认成「用户自己的楼」**
        //    （v1.11.3 想修的「用户自己的楼优先」对它完全失效）。
        Location(
            "综合教学大楼", "良乡校区", 0.822681, 0.379549,
            listOf("综合教学大楼", "综合教学楼大楼", "综教", "综教楼"),
        ),
        // ⚠️「理教楼」是**真机课表里实际出现的写法**（2026-09-28 在 NP05J 上看到
        //「理教楼406」；2026-10-12 又看到一次）。理科教学楼是良乡南校区的日常教学主楼
        //（校内文件里叫「数字化基础教学楼 1 号楼」），学生与教务都简称「理教」——
        // 注意**不是**理学楼（那是南邻的另一组三栋楼）。
        // ⚠️「理科教学大楼」是**空教室接口里的写法**（2026-10-12 在空教室页看到：
        //   楼列表里排在第 15 位的就是「理科教学大楼」）—— 同一栋楼、两种叫法，
        //   不收进来就会两头认不出彼此（既认不出「用户自己的楼」，也算不出距离）。
        Location(
            "理科教学楼", "良乡校区", 0.822681, 0.379560,
            listOf("理科教学楼", "理科教学大楼", "理科楼", "理教楼", "理教"),
        ),
        Location("工业生态楼", "良乡校区", 0.822680, 0.379575, listOf("工业生态楼", "生态楼")),
        Location("物理实验中心", "良乡校区", 0.822679, 0.379564, listOf("物理实验中心", "物理实验楼")),
        Location("化学实验中心", "良乡校区", 0.822679, 0.379568, listOf("化学实验中心", "化学实验楼")),
        Location("工程训练中心", "良乡校区", 0.822688, 0.379575, listOf("工程训练中心")),
        Location("文化体育中心", "良乡校区", 0.822697, 0.379554, listOf("文化体育中心", "文体中心")),
        Location("学生服务中心", "良乡校区", 0.822671, 0.379552, listOf("学生服务中心")),
        Location("行政楼", "良乡校区", 0.822682, 0.379552, listOf("行政楼")),
        Location("文博中心", "良乡校区", 0.822696, 0.379549, listOf("文博中心")),

        // ── 中关村校区（已按校区多边形筛过，排除人大 / 农科院的楼）────────
        Location("主楼", "中关村校区", 0.823098, 0.378730, listOf("主楼")),
        Location("中心教学楼", "中关村校区", 0.823086, 0.378731, listOf("中心教学楼", "中教")),
        Location("信息教学楼", "中关村校区", 0.823086, 0.378737, listOf("信息教学楼", "信息楼")),
        Location("研究生教学楼", "中关村校区", 0.823089, 0.378738, listOf("研究生教学楼", "研究生楼")),
        Location("信息科技实验楼", "中关村校区", 0.823092, 0.378737, listOf("信息科技实验楼")),
        Location("1#教学楼", "中关村校区", 0.823098, 0.378727, listOf("1#教学楼", "1号教学楼", "一号教学楼")),
        Location("3#教学楼", "中关村校区", 0.823090, 0.378728, listOf("3#教学楼", "3号教学楼", "三号教学楼")),
        Location("4#教学楼", "中关村校区", 0.823090, 0.378734, listOf("4#教学楼", "4号教学楼", "四号教学楼")),
        Location("5#教学楼", "中关村校区", 0.823099, 0.378733, listOf("5#教学楼", "5号教学楼", "五号教学楼")),
        Location("7#教学楼", "中关村校区", 0.823086, 0.378734, listOf("七号教学楼", "7号教学楼", "7#教学楼")),
        Location("车辆重点实验楼", "中关村校区", 0.823098, 0.378737, listOf("车辆重点实验楼")),
        Location("图书馆", "中关村校区", 0.823094, 0.378734, listOf("图书馆")),
        Location("体育馆", "中关村校区", 0.823079, 0.378732, listOf("体育馆")),
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
     * 把教室 / 考试 / 楼名字符串匹配到地点；匹配不到返回 `null`。
     *
     * ⚠️ 取**命中的最长别名**：`文萃楼I404` 会同时命中 `文萃楼I`（4 字）与 `文萃楼`（3 字），
     * 必须选长的那个，否则所有区都会被折到楼群中心。
     *
     * ⚠️ 匹配不到时**返回 null 而不是猜一个** —— 猜错会把人指到别的楼
     *（`前沿楼`就不在表里，它拿不到坐标，也就不给距离）。
     */
    fun match(raw: String?): Location? {
        if (raw.isNullOrBlank()) return null
        val text = normalize(raw)
        if (text.isBlank()) return null
        var best: Location? = null
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

    /** 按**展示名**查地点（`文萃楼I` 这种精确名）；找不到返回 `null`。 */
    fun find(name: String?): Location? =
        ALL.firstOrNull { it.name == name }

    /**
     * 两点**直线**距离（米）。任一为 `null`、或两点不在同一校区 → `null`。
     *
     * ⚠️ **同一校区**才比：良乡与中关村隔着十几公里，跨校区比出来的数没有意义
     *（而且那种情况本身说明数据有问题）。
     */
    fun distanceMeters(a: Location?, b: Location?): Double? {
        if (a == null || b == null) return null
        if (a.campusName != b.campusName) return null
        val mlat = Math.toRadians((latOf(a) + latOf(b)) / 2)
        val dx = (lonOf(b) - lonOf(a)) * METERS_PER_LON_DEGREE * cos(mlat)
        val dy = (latOf(b) - latOf(a)) * METERS_PER_LAT_DEGREE
        return hypot(dx, dy)
    }

    /** 归一化 → 经度（`x` 的逆运算）。 */
    fun lonOf(location: Location): Double = 360 * location.x - 180

    /** 归一化 → 纬度（`y` 的逆运算，与地图组件同一套公式）。 */
    fun latOf(location: Location): Double =
        Math.toDegrees(2 * (atan(exp(2 * Math.PI * (0.5 - location.y))) - Math.PI / 4))

    private const val METERS_PER_LAT_DEGREE = 110540.0
    private const val METERS_PER_LON_DEGREE = 111320.0

    /**
     * 距离**分档** —— 只给档位、不给米数。
     *
     * ## ⚠️ 为什么不能给米数
     *
     * 坐标精度只有约 31 米一格（见类注释）。给「约 30 米」和「约 43 米」这两个数
     * 会让用户以为是两个不同的远近，而它们其实是同一个格子里的舍入差异 ——
     * 那是**假的精度**。分档则不受影响：31 米和 43 米都落在「紧挨着」这一档。
     *
     * ⚠️ 档位描述的是**直线距离**，不涉及怎么走、要走多久（我们没有路网数据，
     * 也没有用户的位置 —— 参考点是「上一节课 / 下节课的楼」，是个代理）。
     */
    enum class Distance(val label: String) {
        /** 同一栋楼 */
        SAME("同一栋楼"),
        /** ≤ 80 米：楼挨着楼 */
        NEXT_DOOR("紧挨着"),
        /** ≤ 400 米：同一片教学区里走一段 */
        NEAR("隔一段路"),
        /** > 400 米：另一片区域 */
        FAR("在另一片"),
    }

    /** 把直线距离分档；`null`（算不出来）返回 `null`。 */
    fun distanceOf(meters: Double?): Distance? {
        if (meters == null || meters < 0 || meters.isNaN() || meters.isInfinite()) return null
        return when {
            meters <= SAME_BUILDING_METERS -> Distance.SAME
            meters <= NEXT_DOOR_METERS -> Distance.NEXT_DOOR
            meters <= NEAR_METERS -> Distance.NEAR
            else -> Distance.FAR
        }
    }

    /**
     * 同一栋楼的判定阈值（米）。
     *
     * ⚠️ 不能是 `0`：同一个楼群里的两栋楼（文萃楼H 与 I）算出来就是 31 米，
     * 用 0 会把它们判成「不同的楼、紧挨着」——**其实它们是同一栋不同的翼**。
     * 取一格多一点（40 米），把这些舍入差异吸收掉。
     */
    private const val SAME_BUILDING_METERS = 40.0

    private const val NEXT_DOOR_METERS = 80.0
    private const val NEAR_METERS = 400.0
}
