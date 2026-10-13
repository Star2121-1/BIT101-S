package cn.bit101.android.data.school

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 校园坐标表的**双向覆盖守卫**。
 *
 * ## 为什么需要它
 *
 * 坐标表（[CampusLocations]）和「现实」之间有两个会脱节的地方，**两个都栽过**：
 *
 * | 脱节方向 | 栽过的样子 |
 * |---|---|
 * | 表里**少了**一栋真实存在的楼 | 别名表里没有接口真名「综合教学楼大楼A/B」⇒ 那栋楼永远算不出距离、也永远不被认成「用户自己的楼」，而**所有单测全绿** —— 因为断言写的是真实数据里根本不存在的「综合教学大楼A」 |
 * | 表里的坐标**不是**真实坐标 | 手编一个看着差不多的坐标，地图定位与距离全错，且没有任何测试会红 |
 *
 * 所以这里做三个方向的检查：
 *
 * 1. 表里每条坐标都要落在 OSM 某栋建筑的 **2 格**（约 62 米）内（防手编坐标）
 * 2. OSM 里的**每一栋**建筑都要有归宿：要么已经在表里，要么在下面的 [EXEMPT] 里**写明理由**
 * 3. 表里与 OSM 同名的地点，坐标必须一致（防两边各改一半而漂移）
 *
 * ## fixture 是拉下来的，不是我抄的
 *
 * `src/test/resources/campus/osm-liangxiang-2026-10-13.json`：2026-10-13 用 Overpass
 * 按 `relation/12485117`（北京理工大学良乡校区）拉的**全部带名字建筑**，文件 `source`
 * 字段里存着原始查询语句与区域 id，重跑即可更新。
 *
 * ⚠️ 为什么固化成文件、而不是在测试里手写一份清单：手写等于**再抄一遍**，抄错一个
 * 楼名这条测试就跟着错（铁律 0.3：核验方法不对 ⇒ 得出假的否定结论）。
 *
 * ## ⚠️ 这条测试**只管良乡校区**
 *
 * 良乡能这么查是因为 OSM 上有干净的校区边界：
 * `relation/12485117 北京理工大学良乡校区`，用 `area()` 一框就只剩自己的楼。
 *
 * **中关村查不了**，原因是 OSM 上那个校区**不是闭合多边形/关联**（代码注释里写的
 * `way 30748230` 拿去当 `area()` 查不到东西），只能退回外接框 —— 而外接框里
 * 混着人大、北外、农科院、人民教育出版社的楼（2026-10-13 实测 84 栋里一大半不是
 * 我们的）。那种清单拿来当「必须有归宿」的标准只会逼人写一堆假豁免。
 *
 * ⇒ 中关村那 13 条坐标目前只有「地图页看起来落对了」这一层保障（见 CHANGES.md），
 *   **没有自动守卫**。别以为这张表整体都有守护。
 *
 * ## ⚠️ 这条测试红了怎么处理
 *
 * **不要**先把期望值改掉。按顺序问：
 *
 * 1. OSM 里新出现的那栋楼，**教务里叫什么**？（去真机空教室页看楼列表，
 *    或看课表里的教室字符串）
 * 2. 认得出 ⇒ 给 [CampusLocations] 补别名，并在 `StudySpotLogicTest`
 *    的「接口返回的楼名都能认出坐标」里加上它
 * 3. 认不出 ⇒ 进 [EXEMPT] 并写清「待核实什么」
 */
class CampusOsmSurveyTest {

    /** OSM 建筑（只保留本测试要用的字段）。 */
    private data class OsmBuilding(
        val name: String,
        val x: Double,
        val y: Double,
        val building: String?,
        val levels: Int?,
        /** OSM 原始标识（node/way/relation + id），失败信息里要用它指路 */
        val osmRef: String,
    )

    private val osm: List<OsmBuilding> by lazy { loadFixture() }

    private fun loadFixture(): List<OsmBuilding> {
        val stream = checkNotNull(javaClass.getResourceAsStream(FIXTURE)) { "缺 fixture：$FIXTURE" }
        val root = stream.use { Gson().fromJson(it.reader(), JsonObject::class.java) }
        check(root.has("source")) { "fixture 少了 source 段（出处不能省）：$FIXTURE" }
        val array = checkNotNull(root.getAsJsonArray("buildings")) { "fixture 少了 buildings 数组" }
        return array.map { element ->
            val o = element.asJsonObject
            OsmBuilding(
                name = o.get("name").asString,
                x = o.get("x").asDouble,
                y = o.get("y").asDouble,
                building = o.get("building")?.takeIf { !it.isJsonNull }?.asString,
                levels = o.get("levels")?.takeIf { !it.isJsonNull }?.asInt,
                osmRef = "${o.get("osmType").asString}/${o.get("osmId").asLong}",
            )
        }
    }

    /**
     * ⚠️ **这条是给上面三条测试兜底的**：fixture 读不出来时，上面那些
     * `assertTrue(all { ... })` 会**空集合全真** ⇒ 整套守卫静默失效还显示绿色。
     * 守着守卫自己（铁律 0.7：降级后看起来也正常的那种假通过）。
     */
    @Test
    fun `fixture 能读出来 而且不是空的`() {
        assertTrue("fixture 里只解析出 ${osm.size} 栋建筑，太少了", osm.size >= 30)
        assertTrue("fixture 里的名字不该有空串", osm.all { it.name.isNotBlank() })
    }

    /**
     * 方向一：**表里的坐标不能是编的**。
     *
     * 每条要在 OSM 某栋建筑的 ≤1 格（约 31 米）内找到落点。放宽到一格是因为
     * 坐标只存六位小数 —— 文萃楼 H/I/M 那几栋真的会被舍入到同一格（见
     * [CampusLocations] 的类注释）。
     */
    @Test
    fun `坐标表里每条良乡坐标都落在 OSM 某栋建筑两格以内`() {
        val orphans = CampusLocations.ALL.asSequence()
            .filter { it.campusName == LIANGXIANG }
            .filter { place -> osm.none { within(place.x, place.y, it.x, it.y, SURVEY_TOLERANCE) } }
            .map { "${it.name}(${it.x}, ${it.y})" }
            .toList()

        assertTrue(
            "这些地点在 OSM 里找不到对应建筑，坐标可能是手编的：$orphans",
            orphans.isEmpty(),
        )
    }

    /**
     * 方向二：**OSM 里每栋建筑都要有归宿**。
     *
     * 这是本轮最有价值的一条：OSM 里冒出一栋新教学楼而表里没有时，
     * 它会直接红 —— 而不是等用户「下节课在甘棠A 上课」时才发现算不出距离。
     */
    @Test
    fun `OSM 里每栋建筑都有归宿 —— 在坐标表内 或 有豁免理由`() {
        val tableNames = CampusLocations.ALL.map { it.name }.toSet()
        val homeless = osm
            .filter { it.name !in tableNames }
            .filter { exemptReason(it.name) == null }
            .map { "${it.name}(${it.osmRef})" }
            .distinct()

        assertTrue(
            "OSM 里有建筑既不在坐标表、也没给豁免理由：$homeless\n" +
                "⇒ 先去真机确认它在教务里叫什么，能认出就补 CampusLocations 的别名；" +
                "认不出就往 CampusOsmSurveyTest.EXEMPT 里加一条并写明待核实什么",
            homeless.isEmpty(),
        )
    }

    /**
     * 方向三：**两边同名的地方，坐标必须一致**。
     *
     * 防的是「表里改了半格、OSM 那边也改了半格」这种双向漂移 ——
     * 单看任何一边都看不出来，而距离分档正好卡在阈值上时会翻档。
     */
    @Test
    fun `与 OSM 同名的地点 坐标必须一致`() {
        val drifted = mutableListOf<String>()
        CampusLocations.ALL.forEach { place ->
            val same = osm.filter { it.name == place.name }
            // 陪跑：这栋楼在 OSM 里可能压根没有同名建筑（比如「文萃楼」这种有意聚合的条目），
            // 那种情况由方向一管，这里不比。
            if (same.isEmpty()) return@forEach
            if (same.none { within(place.x, place.y, it.x, it.y, SAME_NAME_TOLERANCE) }) {
                drifted += "${place.name}：表(${place.x}, ${place.y}) vs OSM(${same.map { "${it.x}, ${it.y}" }})"
            }
        }

        assertTrue("这些地点与 OSM 同名但坐标对不上：$drifted", drifted.isEmpty())
    }

    /** 两点是否在给定容差内（按分量比，避免开方的舍入噪声）。 */
    private fun within(ax: Double, ay: Double, bx: Double, by: Double, tolerance: Double): Boolean =
        abs(ax - bx) <= tolerance && abs(ay - by) <= tolerance

    /**
     * `OSM 建筑名 → 归宿理由`。
     *
     * 支持两种写法：**精确名**，或**前缀 + `*`**（给成组的宿舍楼用）。
     *
     * ⚠️ 这张表是**判断的载体**，所以每条都要写清「为什么可以不管它」。
     *    写「其他」这种理由是没用的 —— 它等于把守卫关掉。
     */
    private fun exemptReason(name: String): String? =
        EXEMPT.entries.firstOrNull { (key, _) ->
            if (key.endsWith("*")) name.startsWith(key.dropLast(1)) else name == key
        }?.value

    companion object {
        private const val FIXTURE = "/campus/osm-liangxiang-2026-10-13.json"
        private const val LIANGXIANG = "良乡校区"

        /** 六位小数的最小单位（归一化坐标 1 格 ≈ 31 米，见 [CampusLocations] 类注释）。 */
        private const val GRID = 1e-6

        /**
         * 方向一的容差：**2 格**（约 62 米）。
         *
         * ⚠️ 实测（2026-10-13）表里最差的一条正好是 **1.0 格** —— 「文萃楼」与
         * 「学生服务中心」这两条是有意取整的（前者是文萃楼群的几何中心，
         * 后者 OSM 名多了「北京理工大学」前缀、位置差一格）。**零余量**的阈值会让
         * 这条测试因为无关的舍入而红，那不是我们要抓的东西。
         * ⇒ 这一条只负责抓「坐标是编的」（那种会差几十格），精度由方向三守。
         */
        private const val SURVEY_TOLERANCE = 2 * GRID

        /**
         * 方向三的容差：**1 格**。
         *
         * 两边同名就不该有分歧 —— 实测所有同名项都是 **0 格**，所以这里卡紧。
         */
        private const val SAME_NAME_TOLERANCE = GRID

        /**
         * OSM 里有、但**不进坐标表**的建筑，以及理由。
         *
         * ⚠️ 分三类，别混：① 不是教学楼的（本来就不该进表）；② 是教学楼但
         * **教务叫法还没核实**的（这一类的存在就是「已知缺口」，别删）；
         * ③ 已被表里某条别名覆盖的。
         */
        private val EXEMPT: Map<String, String> = mapOf(
            // ── ① 学生公寓：不做自习推荐，也不需要坐标 ──────────────────
            //    ⚠️「丹枫园C」「至善园」两栋**已经收进坐标表**了 —— 接口的空教室系统
            //    把它们当教学楼列出来（⇒ 有教室）。剩下这些才是纯公寓。
            "丹枫A" to "学生公寓（OSM building=apartments）",
            "丹枫B" to "学生公寓（同上）",
            "丹枫C" to "= 表里的「丹枫园C」（接口把那栋当教学楼列 ⇒ 已收）",
            "槐泽*" to "学生公寓",
            "疏桐园-*" to "学生公寓",
            "静园-*" to "学生公寓",
            "博雅园-学生公寓" to "学生公寓",
            "博士后公寓" to "公寓（OSM 把它标成 university，是 OSM 那边的错标）",

            // ── ① 食堂 / 后勤 / 配套 ────────────────────────────────
            "东食堂" to "食堂",
            "南食堂" to "食堂",
            "北食堂" to "食堂",
            "民族食堂" to "食堂",
            // ⚠️ OSM 里这是**用 relation 建模的**那一栋（只查 way 会漏掉它，
            //    所以 fixture 的查询语句必须是 nwr）
            "食堂" to "食堂",
            "校医院" to "配套（不做自习推荐）",
            "动力中心" to "后勤设施",
            "热力站" to "后勤设施",
            "锅炉房" to "后勤设施",
            "北校区邮政驿站" to "配套",
            "亭" to "景观（OSM 里就这一个字的名字）",

            // ── ① 同一栋、两种叫法（OSM 名 ≠ 接口名；两栋都**已收进坐标表**）────
            "徐特立图书馆" to "= 表里的「良乡图书馆」（同栋关系是**推断**，见坐标表那边的注释）",
            "前沿交叉研究院大楼" to "= 表里的「前沿交叉大楼」",

            // ── ② 教学类，但**接口的空教室系统里根本没有它** ⇒ 不收录 ──────
            //    ★ 2026-10-13 真机把空教室页的良乡 30 栋楼名**全 dump 下来了**
            //    （取证 `.workbuddy/screenshots/v116-验收-2026-10-13/`，界面自称「30 栋」
            //    而且数对得上），下面这些**一栋都不在那 30 栋里**
            //    ⇒ 空教室系统里没有它们的教室 ⇒ 自习推荐用不到它们的坐标。
            //
            //    ⚠️ 这条把原来「教务叫法未核实」的**猜测**换成了**取证过的结论** ——
            //    所以它们不再是「已知缺口」，只是「不归这个功能管」。
            //    （`甘棠A~D` 在 OSM 被标成 `building=university` 且 10 层，多半是 OSM 那边标错，
            //     它们更可能是公寓。）
            "甘棠A" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "甘棠B" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "甘棠C" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "甘棠D" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "信创楼-A" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "先进结构技术研究院" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "大学生活动中心" to "不在接口的良乡 30 栋里 ⇒ 无教室",
            "工业生态楼二期" to "不在接口的良乡 30 栋里（表里收的是「工业生态楼」）",

            // ── ① OSM 名 ≠ 接口名：接口的良乡 30 栋里**确实有**这一栋 ──────
            //    「文萃楼圆形报告厅」是接口真名（2026-10-13 真机 dump 核实，良乡 30 栋之一），
            //    表里已按 OSM 这栋「圆形教学楼」的坐标单列了一条。
            //    ⚠️ 同栋关系是**推断**（名字不同、位置同格），所以 OSM 这个名字仍然豁免。
            "圆形教学楼" to "= 表里的「文萃楼圆形报告厅」（同栋是推断，见坐标表注释）",
            //    「报告厅、演艺厅」是它旁边另一栋 —— **不在**接口的良乡 30 栋里 ⇒ 无教室。
            "报告厅、演艺厅" to "不在接口的良乡 30 栋里 ⇒ 无教室",

            // ── ③ 已被表里某条别名覆盖 ─────────────────────────────
            "北京理工大学学生服务中心" to "= 表里的「学生服务中心」（别名「学生服务中心」是它的子串）",
        )
    }
}
