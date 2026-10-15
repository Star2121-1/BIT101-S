package cn.bit101.android.config.setting.base

/**
 * 配色方案（palette 轴）——「外观切换」三轴之一。
 *
 * 决定 M3 70 角色 + GridTokens 面色族填什么值，与 UI 形态轴（[AppSkin]）正交：
 * 「原版界面 × 北理配色」这类组合靠本轴表达，原版页面零布局改动即可换色。
 *
 * ⚠️ 为什么住 `:config` 而不住 `features/theme`：同 [AppSkin]——ThemeSettings 要暴露
 * `SettingItem<AppPalette>`，枚举住 theme 会与 config 成环。
 *
 * ⚠️ value 字符串（`name.lowercase()`）是**发布后冻结的数据兼容契约**：改名 = 老用户设置静默回默认。
 *
 * ⚠️ 命名（N1）：按**配色本身**命名 Classic/Warm/Brand，不用 Grid——Grid 同时是 skin 的名字，
 * 「palette=Grid + skin=Classic」读起来像「皮肤 classic、配色 grid」，长期误读。
 */
enum class AppPalette(
    /** 能不能选：false ⇒ UI 置灰（如官方 VI 未到位时） */
    val available: Boolean = true,
    /** 选了要不要标「实验性」：true ⇒ 可选但带实验性标注（如占位色值） */
    val experimental: Boolean = false,
) {
    /** 原版配色（master Color.kt 回迁） */
    Classic,

    /** 一格 GRID 暖棕配色（默认；分支 P1 token 落地事实） */
    Warm,

    /** 北理工配色（占位值待官方 VI，[experimental] = true） */
    Brand(experimental = true),
    ;

    /**
     * 本配色在 DataStore 里的 value 字符串（冻结契约，见类 KDoc）。
     * 成员属性而非扩展属性：调用方（如 config.setting 包的 Default*Settings）免导入。
     */
    val value: String
        get() = name.lowercase()

    companion object {

        /**
         * 全部配色（UI 展示序：Classic 在前）。
         * ⚠️ **展示序 ≠ 默认值**：首个是 Classic，默认却是 Warm
         * ⇒ 兜底必须用显式默认值，用 `first()` 会把脏值回落成 Classic（老用户莫名变原版色）。
         */
        val allPalettes: List<AppPalette>
            get() = entries

        /**
         * 通过 datastore 字符串取配色，**脏值回落显式默认值**（Warm，不是 `first()`）。
         */
        fun getPalette(value: String): AppPalette =
            entries.firstOrNull { it.value == value } ?: Warm
    }
}

/**
 * 配色数据：对话框单选行用（展示名 + datastore 值），照 [DarkThemeData] 范式。
 */
data class AppPaletteData(
    /** 展示名（中文） */
    val name: String,
    /** datastore 值（冻结） */
    val value: String,
)

/** 转为 [AppPaletteData]。 */
fun AppPalette.toAppPaletteData() = AppPaletteData(
    when (this) {
        AppPalette.Classic -> "原版配色"
        AppPalette.Warm -> "一格配色"
        AppPalette.Brand -> "北理配色"
    },
    value,
)
