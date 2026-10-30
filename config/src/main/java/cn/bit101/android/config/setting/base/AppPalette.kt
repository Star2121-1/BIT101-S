package cn.bit101.android.config.setting.base

/**
 * 配色方案（palette 轴）——「外观切换」三轴之一。
 *
 * 决定 M3 70 角色 + GridTokens 面色族填什么值，与 UI 形态轴（[AppSkin]）正交：
 * 「原版界面 × BIT101经典配色」这类组合靠本轴表达，原版页面零布局改动即可换色。
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
    /**
     * BIT101经典配色（默认配色）。
     * 色值仍是未验证占位（官方校色经《章程》查证为绿+墨绿+褐，深蓝占位与之不符）；
     * 用户拍板：作为首屏默认配色，故不再标「实验性」。
     */
    Brand,

    /**
     * 暖棕配色（展示名，P5-C 起；master Color.kt 回迁，观感桃粉+暖橙）。
     * ⚠️ 值虽是 master 逐格回迁，展示名**不冠「原版」**（P5-C 后续用户拍板）。
     */
    Classic,

    /** 清新青绿配色（展示名，P5-C 起；默认，分支 P1 token 落地事实） */
    Warm,
    ;

    /**
     * 本配色在 DataStore 里的 value 字符串（冻结契约，见类 KDoc）。
     * 成员属性而非扩展属性：调用方（如 config.setting 包的 Default*Settings）免导入。
     */
    val value: String
        get() = name.lowercase()

    companion object {

        /**
         * 全部配色（UI 展示序：Brand 在前——P5-C 后续用户拍板「BIT101经典配色」提至首位，
         * Classic/Warm 顺延）。
         * 默认值 = Brand（与展示序首个一致，用户拍板）。兜底仍写显式 Brand，别依赖 `first()`。
         */
        val allPalettes: List<AppPalette>
            get() = entries

        /**
         * 通过 datastore 字符串取配色，**脏值回落显式默认值**（Brand）。
         */
        fun getPalette(value: String): AppPalette =
            entries.firstOrNull { it.value == value } ?: Brand
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

/**
 * 转为 [AppPaletteData]。展示名按**用户拍板**定（P5-C + 后续两轮正名）：
 *
 * - Brand = 深蓝主色占位（#1B4F9C，值未验证）⇒「BIT101经典配色」，展示首位。
 *   P5-C 后续用户拍板：保持色值、提至首位、改名。**不再称「北理工配色」**——
 *   官方校色经《北京理工大学章程》第四十二条查证为「主色绿 + 辅助墨绿/褐」，
 *   深蓝占位与官方色系不符 ⇒ 占位表不蹭学校名（[experimental] 仍保留）；
 * - Classic = master 逐格回迁（#FFF8F5 桃粉底 + #FFDBC8 暖橙容器 + 棕主色）⇒「暖棕配色」，
 *   **不冠「原版」**（值确是 master 回迁，但用户拍板展示名不称原版）；
 * - Warm = P1 分支现状（纯白底 + #D5E9DF 青绿容器）⇒「清新青绿配色」，观感清新、
 *   **不是**暖棕——旧名「一格 GRID 暖棕配色」名不副实（初稿 §119 意图是暖棕，
 *   实现出来偏青绿），故改。
 *
 * ⚠️ datastore value（classic/warm/brand）是**发布后冻结契约**，永不随展示名改。
 * ⚠️ 新名不得含「一格 GRID」：与 [AppSkin.Grid] 的展示名撞词（N1 命名警示）。
 */
fun AppPalette.toAppPaletteData() = AppPaletteData(
    when (this) {
        AppPalette.Brand -> "BIT101经典配色"
        AppPalette.Classic -> "暖棕配色"
        AppPalette.Warm -> "清新青绿配色"
    },
    value,
)
