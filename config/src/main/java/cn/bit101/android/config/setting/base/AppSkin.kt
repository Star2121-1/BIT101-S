package cn.bit101.android.config.setting.base

/**
 * UI 形态（skin 轴）——「外观切换」三轴之一。
 *
 * 决定用哪套布局/组件/导航：原版界面（master 冻结区）还是一格 GRID。
 * 与配色轴（[AppPalette]）、课程色轴（[CourseColorFamily]）**正交**，
 * 三者自由组合（如「原版界面 × 北理配色」）。
 *
 * ⚠️ 为什么住 `:config` 而不住 `features/theme`：
 * ThemeSettings/CourseScheduleSettings 就在本包，依赖方向是 theme→common→config；
 * 枚举若住 theme，config 要暴露 `SettingItem<AppSkin>` 就得反向依赖 theme，成环。
 *
 * ⚠️ value 字符串（`name.lowercase()`）是**发布后冻结的数据兼容契约**：
 * 改名 = 老用户设置静默回默认。要改名只能在发布前，发布后靠 [AppSkin.getSkin] 容错。
 */
enum class AppSkin(
    /** 能不能选：false ⇒ UI 置灰（建设中），枚举值仍在（备份恢复写入也不会崩） */
    val available: Boolean = true,
) {
    /** 原版界面（master 布局，冻结维护：只修 bug，新功能只进 GRID） */
    Classic,

    /** 一格 GRID 界面（P4 页面级翻新落地后开放，当前 [available] = false） */
    Grid(available = false),
    ;

    /**
     * 本形态在 DataStore 里的 value 字符串（冻结契约，见类 KDoc）。
     * 成员属性而非扩展属性：调用方（如 config.setting 包的 Default*Settings）免导入。
     */
    val value: String
        get() = name.lowercase()

    companion object {

        /**
         * 全部形态（UI 展示序：Classic 在前）。
         * ⚠️ 展示序 ≠ 默认值——本枚举恰好一致，但 [AppPalette] 不一致
         * （展示序 Classic 在前、默认 Warm），那边不能图省事用 `first()` 兜底。
         */
        val allSkins: List<AppSkin>
            get() = entries

        /**
         * 通过 datastore 字符串取形态，**脏值回落显式默认值**（不是 `first()`）。
         */
        fun getSkin(value: String): AppSkin =
            entries.firstOrNull { it.value == value } ?: Classic
    }
}

/**
 * UI 形态数据：对话框单选行用（展示名 + datastore 值），照 [DarkThemeData] 范式。
 */
data class AppSkinData(
    /** 展示名（中文） */
    val name: String,
    /** datastore 值（冻结） */
    val value: String,
)

/** 转为 [AppSkinData]。 */
fun AppSkin.toAppSkinData() = AppSkinData(
    when (this) {
        AppSkin.Classic -> "原版界面"
        AppSkin.Grid -> "一格 GRID"
    },
    value,
)
