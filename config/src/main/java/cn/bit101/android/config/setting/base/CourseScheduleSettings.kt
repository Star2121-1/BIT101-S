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

    /** 北理蓝 12 色：占位（待官方 VI，[experimental] = true） */
    Brand(experimental = true),
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
         * ⚠️ **展示序 ≠ 默认值**：首个是 Auto，默认也是 Auto——本枚举恰好一致；
         * 但 [AppPalette] 不一致，那边不能图省事用 `first()` 兜底。
         */
        val allFamilies: List<CourseColorFamily>
            get() = entries

        /**
         * 通过 datastore 字符串取族，**脏值回落显式默认值**（Auto，不是 `first()`）。
         */
        fun getFamily(value: String): CourseColorFamily =
            entries.firstOrNull { it.value == value } ?: Auto
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
     * 课程配色族（默认 Auto = 跟随外观，按 palette 解析）
     */
    val courseColorFamily: SettingItem<CourseColorFamily>
}