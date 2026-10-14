package cn.bit101.android.features.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 《一格 GRID》token 层 · M3 ColorScheme 装不下的语义词（规范 §0 / §8.2 / §8.7）。
 *
 * 分工（实施计划 A3-c 混合方案）：
 * - M3 承担能对上的角色（bg / surface / onSurface / primary / error / outline…）→ 见 [Color.kt]；
 * - 本文件承载 M3 没有对应角色的部分：
 *   - 面：`--surface-2` / `--surface-3` / `--disabled-bg` / 骨架色
 *   - 文字：`--text-placeholder`（三级可读）/ `--text-disabled`（仅禁用态）
 *   - 语义色：Success / Accent（银杏金，**仅填充**）/ Info 容器
 *   - 状态语义层：`--state-calm` / `--state-attention` / `--state-urgent`（§8.8 同一语义跨 Tab 同色）
 *   - 数字排版档：num-lg / num-md / num-sm（§8.7 tabular）
 *   - 课程 12 色板（§8.2 权威表）：**只放色值**；色号函数 `fnv1a32(课名)%12` 属 P3，落 `:data`
 *
 * ⚠️ 取用入口只有 [GridTheme.tokens]（内部即 `LocalGridTokens.current`）—— 禁止各模块另立第二份色值。
 */
@Immutable
data class GridTokens(
    // ── 面 ──
    /** `--surface-2`：次级面（当前/下一节条目底、Chip 未选底、提示条底） */
    val surface2: Color,
    /** `--surface-3`：三级面（分割线、空态图标底、热度条底） */
    val surface3: Color,
    /** `--disabled-bg`：禁用态容器底（§8.1：禁用不得仅靠颜色，须同时换容器底） */
    val disabledBg: Color,
    /** 骨架屏底色（`--skeleton`；微光扫掠动画属组件层，P2） */
    val skeleton: Color,

    // ── 文字 ──
    /** `--text-placeholder`：三级可读（占位符 / 弱读数 / 组标题 / 缺值「—」） */
    val textPlaceholder: Color,
    /** `--text-disabled`：**仅**禁用控件文字/图标（§8.1 白名单三类，装饰性时间戳已移出） */
    val textDisabled: Color,

    // ── 语义色 ──
    /** Success（`--success`） */
    val success: Color,
    /** Success 容器底 */
    val successContainer: Color,
    /** Success 容器上的文字 */
    val onSuccessContainer: Color,
    /** Accent 银杏金 `#E8A317`：**仅作填充**；文字与 <24px 图标一律用 [accentText]（§0 红线） */
    val accent: Color,
    /** Accent 的文字/图标安全档 `#8A5A00`（亮）/ `#F2BE55`（暗） */
    val accentText: Color,
    /** Accent 容器底 */
    val accentContainer: Color,
    /** Accent 容器上的文字 */
    val onAccentContainer: Color,
    /** Info 容器底（信息提示条） */
    val infoContainer: Color,
    /** Info 容器上的文字 */
    val onInfoContainer: Color,

    // ── 状态语义层（§8.8：颜色 + 非颜色线索，attention 档强制额外线索）──
    /** 倒计时 >30min｜流量 ≤90%｜借阅 >3 天 */
    val stateCalm: Color,
    /** 倒计时 ≤30min｜流量 >90%（对齐 WARN_BYTES）｜**必须**配「即将开始」Chip / 图标 / 「已用 N%」文案 */
    val stateAttention: Color,
    /** 倒计时 ≤10min｜流量 >100%｜借阅 ≤3 天（含逾期） */
    val stateUrgent: Color,

    // ── 数字排版档（§8.7：字阶自带 tabular，不是可选工具类）──
    /** num-lg：44/52/700（hero 大数字，如倒计时分钟） */
    val numLg: TextStyle,
    /** num-md：22/30/700（余额 / 空教室数 / 借阅天数） */
    val numMd: TextStyle,
    /** num-sm：15/22/600（时间线时刻 `08:00` / 空到时间 `20:55`） */
    val numSm: TextStyle,

    // ── 课程 12 色板（§8.2，index 0..11 = 色号 0..11）──
    val coursePalette: List<CourseColors>,
) {
    /**
     * 按色号取课程色 —— **纯查表，不含哈希**。
     * 哈希 `fnv1a32(课名)%12` 是 P3 的活（落 `:data`，App / 组件 / 通知三处共用同一函数）；
     * 这里只回答「色号 i 显示成什么」。越界按 mod 12 收拢，传 -1 也不崩。
     */
    fun course(index: Int): CourseColors =
        coursePalette[Math.floorMod(index, coursePalette.size)]
}

/**
 * 一套课程色（§8.2 表的一行）。
 *
 * 暗色实例已按 §8.2 规则把 [mark] 位换成 ink-d
 * （「独立图形：亮色用 mark；**暗色改用 ink-d**」—— 暗态 mark 与底色对比不足），
 * 调用方不需要再分暗亮。竖条一律用 [ink]，**不得用 mark**（mark 贴浅色单元格边缘看不见）。
 */
@Immutable
data class CourseColors(
    /** 块底（大面积色带也用这档，无需 3:1） */
    val fill: Color,
    /** 块内文字 + 单元格左竖条 4px */
    val ink: Color,
    /** 亮色独立图形（圆点 / 图例 / Chip 点）；暗色实例此位 = ink-d。**一律加 1px 描边** */
    val mark: Color,
)

/** 取用入口：`GridTheme.tokens.surface2`。 */
object GridTheme {
    val tokens: GridTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalGridTokens.current
}

val LocalGridTokens = staticCompositionLocalOf<GridTokens> {
    error("No GridTokens provided — 检查 BIT101Theme 是否注入了 LocalGridTokens")
}

// ── 亮色实例（规范 §0 / §8.2 / HTML 原型 :root） ──

internal val lightGridTokens = GridTokens(
    surface2 = Color(0xFFF4EFE8),
    surface3 = Color(0xFFEAE3DA),
    disabledBg = Color(0xFFE3DBD1),
    skeleton = Color(0xFFE7E0D7),

    textPlaceholder = Color(0xFF726759),
    textDisabled = Color(0xFF8A7F72),

    success = Color(0xFF2F7D5A),
    successContainer = Color(0xFFD8EFE3),
    onSuccessContainer = Color(0xFF1B5B3F),
    accent = Color(0xFFE8A317),
    accentText = Color(0xFF8A5A00),
    accentContainer = Color(0xFFFBEBCC),
    onAccentContainer = Color(0xFF3D2A00),
    infoContainer = Color(0xFFCFE7F6),
    onInfoContainer = Color(0xFF0F5076),

    stateCalm = Color(0xFF1E1A16),
    stateAttention = Color(0xFF8A5A00),
    stateUrgent = Color(0xFFB3261E),

    numLg = numTextStyle(44, 52, FontWeight.Bold),
    numMd = numTextStyle(22, 30, FontWeight.Bold),
    numSm = numTextStyle(15, 22, FontWeight.SemiBold),

    coursePalette = CoursePalette.light,
)

// ── 暗色实例（规范 §0 暗色行 / §8.2 fill-d·ink-d 列 / HTML 原型 [data-theme="dark"]） ──

internal val darkGridTokens = GridTokens(
    surface2 = Color(0xFF272219),
    surface3 = Color(0xFF332C24),
    disabledBg = Color(0xFF2A251F),
    skeleton = Color(0xFF231F1A),

    // 规范未给暗色占位符档：按亮色 #726759 同族提亮推导（对 surface ≈6:1，仍属「三级可读」）
    textPlaceholder = Color(0xFFA79A8C),
    textDisabled = Color(0xFF857B72),

    success = Color(0xFF7FDCB4),
    successContainer = Color(0xFF0E3A2A),
    onSuccessContainer = Color(0xFF7FDCB4),
    accent = Color(0xFFF2BE55),
    accentText = Color(0xFFF2BE55),
    accentContainer = Color(0xFF3A2E12),
    onAccentContainer = Color(0xFFF2BE55),
    infoContainer = Color(0xFF123244),
    onInfoContainer = Color(0xFF9FD3F0),

    stateCalm = Color(0xFFF3EDE6),
    stateAttention = Color(0xFFF2BE55),
    stateUrgent = Color(0xFFFFB3A7),

    numLg = numTextStyle(44, 52, FontWeight.Bold),
    numMd = numTextStyle(22, 30, FontWeight.Bold),
    numSm = numTextStyle(15, 22, FontWeight.SemiBold),

    // mark 位按 §8.2 放 ink-d（暗态独立图形不用 mark）
    coursePalette = CoursePalette.dark,
)

/**
 * §8.7 数字排版档。
 *
 * - `fontFeatureSettings = "tnum"`：tabular 等宽数字（Roboto 的 tnum 表）；
 * - 字体取 [FontFamily.Default] —— §8.7 要求 `--font-num` 里 Roboto/Inter 排在 PingFang SC **之前**，
 *   Android 默认无衬线即 Roboto，天然满足该顺序，**不要**改踩中文字体栈；
 * - 防位数抖动（`9:58 → 10:02` 跳宽）靠容器 `min-width` 或补前导零，属组件层（P2），
 *   这里只给字阶本身；fontScale 200% 的降级（heightIn(min=52) + maxLines=1 + autoSize）同样属组件层。
 */
private fun numTextStyle(fontSize: Int, lineHeight: Int, fontWeight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    fontWeight = fontWeight,
    fontFeatureSettings = "tnum",
)
