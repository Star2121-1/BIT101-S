package cn.bit101.android.features.schedule.classroom

import cn.bit101.api.model.common.BuildingInfo

/**
 * 空教室页的一个**校区分组**（三级折叠的最外层：校区 → 教学楼 → 教室）。
 *
 * @property campusCode 校区代码（分组键）；[BuildingInfo.campusCode] 为空时退化成校区名
 * @property campusName 展示用的校区名（保证非空）
 * @property buildings 该校区下的教学楼，保持服务端返回顺序
 * @property isCurrent 是不是设置里选的那个校区（它排在列表最前并自动展开）
 */
internal data class CampusGroup(
    val campusCode: String,
    val campusName: String,
    val buildings: List<BuildingInfo>,
    val isCurrent: Boolean,
)

/**
 * 把「全部教学楼」按校区分组（纯函数，可单测）。
 *
 * ## 为什么在客户端分组
 *
 * 空教室接口一次就能拿到楼，而每栋楼都自带所属校区（`XXXQDM` / `XXXQDM_DISPLAY`）——
 * 那就不必再为每个校区单独发一次请求。校区列表接口（`ggzdpx.do`）只在设置页
 * 切换校区时才需要。
 *
 * ## 排序
 *
 * **当前校区排最前**，其余保持服务端顺序（良乡在前之类的顺序是学校定的，不重排）。
 */
internal object CampusGrouping {

    /** 楼里连校区信息都没有时的兜底桶（正常不该出现）。 */
    const val UNKNOWN_CODE = "__unknown__"

    fun group(
        buildings: List<BuildingInfo>,
        currentCampusCode: String = "",
        currentCampusName: String = "",
    ): List<CampusGroup> {
        // LinkedHashMap：插入顺序 = 服务端顺序
        val buckets = LinkedHashMap<String, MutableList<BuildingInfo>>()
        val names = HashMap<String, String>()

        buildings.forEach { building ->
            // 校区代码为空时退化成校区名，再不行丢进兜底桶 —— 总之不能让两栋楼
            // 因为「代码都为空」被合成同一个校区
            val code = building.campusCode.ifBlank { building.campusName }.ifBlank { UNKNOWN_CODE }

            buckets.getOrPut(code) { mutableListOf() }.add(building)

            if (names[code].isNullOrBlank()) {
                names[code] = building.campusName.ifBlank { code }
            }
        }

        val groups = buckets.map { (code, list) ->
            CampusGroup(
                campusCode = code,
                campusName = names[code].orEmpty().ifBlank { code },
                buildings = list,
                isCurrent = isCurrent(code, names[code].orEmpty(), currentCampusCode, currentCampusName),
            )
        }

        return groups.filter { it.isCurrent } + groups.filterNot { it.isCurrent }
    }

    /**
     * 判定某个分组是不是「当前校区」。
     *
     * 代码优先；设置里只存了名字（老版本可能只有名字）时退化成名字比对。
     */
    private fun isCurrent(
        code: String,
        name: String,
        currentCampusCode: String,
        currentCampusName: String,
    ): Boolean = when {
        currentCampusCode.isNotBlank() -> code == currentCampusCode
        currentCampusName.isNotBlank() -> name == currentCampusName
        else -> false
    }
}
