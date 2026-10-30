package cn.bit101.android.config.setting.base

import cn.bit101.android.config.common.SettingItem
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 时间表项
 */
data class TimeTableItem(
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable

/**
 * 时间表
 */
typealias TimeTable = List<TimeTableItem>

/**
 * 将字符串转换为时间表，格式错误时抛出异常
 */
fun String.toTimeTable(): TimeTable {
    val timeTable = mutableListOf<TimeTableItem>()
    this.split("\n").forEach {
        if (it.isBlank()) return@forEach
        val x = it.split(",")
        timeTable.add(
            TimeTableItem(
                LocalTime.parse(x[0].trim()),
                LocalTime.parse(x[1].trim())
            )
        )
    }

    if(timeTable.isEmpty()) throw IllegalArgumentException("Invalid time table")

    timeTable.forEachIndexed { index, time ->
        if (time.endTime <= time.startTime) throw IllegalArgumentException("Invalid time table")
        if (index != 0) {
            if (time.startTime <= timeTable[index - 1].endTime) throw IllegalArgumentException("Invalid time table")
        }
    }

    return timeTable
}

/**
 * 将时间表转换为字符串
 */
fun TimeTable.toTimeTableString(): String {
    return this.joinToString("\n") {
        "${it.startTime}, ${it.endTime}"
    }
}

/**
 * 课程配色族（课程色第三轴）——课表课程块的 12 支颜料选哪一套。
 *
 * 与整体配色（[AppPalette]）**独立设置**：用户意图不同（区分 12 门课的功能性编码
 * vs 整套 App 的气质），且课程色有跨端同色义务（课表/组件同一门课必须同色），
 * 绑死在 palette 上会让「换个 App 配色」打乱用户认了一个学期的课程颜色。
 *
 * ⚠️ value 字符串（`name.lowercase()`）是**发布后冻结的数据兼容契约**。
 *
 * ⚠️ 命名与 [AppPalette] 的 Brand **同词同义**（枚举名同指「北理蓝」占位方向）——
 * 两个名字指同一概念，以后必有人问「Bit 和 Brand 是不是两回事」。
 * （[AppPalette] 侧展示名已改「BIT101经典配色」：官方校色经章程查证为绿+墨绿+褐，
 * 深蓝占位不蹭学校名。本族展示名「北理蓝 12 色」同 rationale，P5-D 做 UI 时一并复核。）
 */
enum class CourseColorFamily(
    /** 能不能选：false ⇒ UI 置灰 */
    val available: Boolean = true,
    /** 选了要不要标「实验性」：true ⇒ 可选但带实验性标注（如占位色表） */
    val experimental: Boolean = false,
) {
    /** 跟随外观：按 palette 解析（Classic⇒Legacy 统色 / Warm、Brand⇒Classic 12 色），见 CourseColorResolver */
    Auto,

    /** 原版统色：12 支同值，还原 master 课表体验 */
    Legacy,

    /** 经典 12 色：一格 GRID 课程色（现 CoursePalette） */
    Classic,

    /** 莫兰迪 12 色：低饱和柔和 */
    Morandi,

    /**
     * 北理蓝 12 色：围绕校色 #1B4F9C 的蓝色系。
     * 不再是占位——Brand 已成为默认 App 配色，再挂「实验性」自相矛盾。
     */
    Brand,
    ;

    /**
     * 本族在 DataStore 里的 value 字符串（冻结契约，见类 KDoc）。
     * 成员属性而非扩展属性：调用方（如 config.setting 包的 Default*Settings）免导入。
     */
    val value: String
        get() = name.lowercase()

    companion object {

        /**
         * 全部族（UI 展示序：Auto 在前）。
         * 默认值 = Legacy（原版统色，用户拍板）。
         */
        val allFamilies: List<CourseColorFamily>
            get() = entries

        /**
         * 通过 datastore 字符串取族，**脏值回落显式默认值**（Legacy，原版统色）。
         */
        fun getFamily(value: String): CourseColorFamily =
            entries.firstOrNull { it.value == value } ?: Legacy
    }
}

/**
 * 课程配色族数据：对话框单选行用（展示名 + datastore 值），照 [DarkThemeData] 范式。
 */
data class CourseColorFamilyData(
    /** 展示名（中文） */
    val name: String,
    /** datastore 值（冻结） */
    val value: String,
)

/** 转为 [CourseColorFamilyData]。 */
fun CourseColorFamily.toCourseColorFamilyData() = CourseColorFamilyData(
    when (this) {
        CourseColorFamily.Auto -> "跟随外观"
        CourseColorFamily.Legacy -> "原版统色"
        CourseColorFamily.Classic -> "经典 12 色"
        CourseColorFamily.Morandi -> "莫兰迪 12 色"
        CourseColorFamily.Brand -> "北理蓝 12 色"
    },
    value,
)

private const val OVERRIDE_KV_SEP = "\u0001"

/**
 * 单课颜色覆盖：课名 → 色号(0..11)。编码为每行 `课名\u0001色号`；
 * 课名里的换行/分隔符替换为空格（课程名极少数含换行，如考试前缀）。
 */
fun Map<String, Int>.toColorOverridesString(): String =
    entries.joinToString("\n") { (name, idx) ->
        "${name.replace("\n", " ").replace(OVERRIDE_KV_SEP, " ")}$OVERRIDE_KV_SEP$idx"
    }

/** 解析 [toColorOverridesString] 的产物；脏行忽略，色号收拢到 0..11。 */
fun String.toColorOverrides(): Map<String, Int> =
    lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val i = line.lastIndexOf(OVERRIDE_KV_SEP)
        if (i <= 0) return@mapNotNull null
        val idx = line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
        line.substring(0, i) to idx.coerceIn(0, 11)
    }.toMap()

/**
 * 课程表设置
 */
interface CourseScheduleSettings {

    /**
     * 学期
     */
    val term: SettingItem<String>

    /**
     * 开学第一天
     */
    val firstDay: SettingItem<LocalDate?>

    /**
     * 是否显示周六
     */
    val showSaturday: SettingItem<Boolean>

    /**
     * 是否显示周日
     */
    val showSunday: SettingItem<Boolean>

    /**
     * 是否显示边框
     */
    val showBorder: SettingItem<Boolean>

    /**
     * 是否高亮今天
     */
    val highlightToday: SettingItem<Boolean>

    /**
     * 是否显示分割线
     */
    val showDivider: SettingItem<Boolean>

    /**
     * 是否显示当前时间线
     */
    val showCurrentTime: SettingItem<Boolean>

    /**
     * 是否显示考试信息
     */
    val showExamInfo: SettingItem<Boolean>

    /**
     * 时间表
     */
    val timeTable: SettingItem<TimeTable>

    /**
     * 课程配色族（默认 Legacy = 原版统色）
     */
    val courseColorFamily: SettingItem<CourseColorFamily>

    /**
     * 单课颜色覆盖（课名 → 色号 0..11）。空 = 全部跟随族/hash。
     */
    val courseColorOverrides: SettingItem<Map<String, Int>>
}