package cn.bit101.android.features.theme.palette

import androidx.compose.material3.ColorScheme
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.CourseColorFamily
import cn.bit101.android.features.theme.CoursePalette
import cn.bit101.android.features.theme.GridTokens
import cn.bit101.android.features.theme.SharedGridTokens
import cn.bit101.android.features.theme.sharedGridTokensDark
import cn.bit101.android.features.theme.sharedGridTokensLight

/**
 * 三套 palette 的**唯一分派点**（初稿 §5.1）。
 *
 * 生产入口只有这两个函数；各 palette 表（[ClassicColor] / [WarmColor] / [BrandColor]）
 * 是数据，不允许调用方直接挑表——否则「新增一套配色」要改遍调用点。
 *
 * ⚠️ **缺档必须 fail-fast**：查不到登记项直接 `error()`，绝不静默回退——
 * 静默回退会让「用户选了却没效果」变成幽灵 bug（还查不到原因）。
 * 新增 `AppPalette` 枚举值时的唯一待办：在 [registry] 登记，漏登记 = 运行期炸，
 * 不会无声无息 fallback 到别的配色。
 */
internal object Palettes {

    /**
     * 登记表：`AppPalette` 枚举名 → 明暗两档 token。
     *
     * 用枚举名（String）而不是枚举本身做键，是为了让「漏登记」在**同一处**炸
     * （见 [paletteSchemeByName]）——按枚举键 + `when` 穷举的写法会把漏登记
     * 变成编译期穷举报警，看似更安全，实则逼人每次加枚举都要回来改这里，
     * 且 `else ->` 分支仍不可测；名字键 + 显式 error 消息可测、信息含 palette 名。
     */
    private val registry: Map<String, PaletteTokens> = mapOf(
        AppPalette.Classic.name to ClassicColor.tokens,
        AppPalette.Warm.name to WarmColor.tokens,
        AppPalette.Brand.name to BrandColor.tokens,
    )

    /** 当前 palette + 明暗的 M3 角色全表。 */
    fun scheme(palette: AppPalette, isDark: Boolean): ColorScheme =
        paletteSchemeByName(palette.name, isDark).scheme

    /**
     * 当前 palette + 明暗 + 课程色族的 [GridTokens]。
     *
     * 组装：随 palette 变的字段来自 [PaletteScheme]；跨 palette 共享的字段
     * （num 三档 / state 三档 / success·accent·info 族）来自
     * [SharedGridTokens]（§5.2：语义色不随品牌变）。
     *
     * [family] 决定课程 12 支颜料取哪套族表（原版统色 / 经典 12 色 / 莫兰迪 / 北理蓝）。
     * Auto 按 palette 解析（Classic⇒Legacy 统色，Warm/Brand⇒Classic 12 色）——此规则与
     * `:data` 的 `CourseColorResolver.resolve` 同义（此处内联一份，避免 :theme 反向依赖 :data）。
     */
    fun tokens(palette: AppPalette, isDark: Boolean, family: CourseColorFamily): GridTokens {
        val ps = paletteSchemeByName(palette.name, isDark)
        val shared = if (isDark) sharedGridTokensDark else sharedGridTokensLight
        val courses = when (family) {
            CourseColorFamily.Legacy -> if (isDark) CoursePalette.legacyDark else CoursePalette.legacyLight
            CourseColorFamily.Morandi -> if (isDark) CoursePalette.morandiDark else CoursePalette.morandiLight
            CourseColorFamily.Classic -> if (isDark) CoursePalette.dark else CoursePalette.light
            // 北理蓝：围绕校色 #1B4F9C 的蓝色系 12 支（与经典 12 色的彩虹系明确区分）
            CourseColorFamily.Brand -> if (isDark) CoursePalette.brandDark else CoursePalette.brandLight
            // Auto：按 palette 解析（同 CourseColorResolver.resolve）
            CourseColorFamily.Auto -> when (palette) {
                AppPalette.Classic -> if (isDark) CoursePalette.legacyDark else CoursePalette.legacyLight
                AppPalette.Warm, AppPalette.Brand -> if (isDark) CoursePalette.dark else CoursePalette.light
            }
        }
        return GridTokens(
            // ── 随 palette（PaletteScheme）──
            surface2 = ps.surface2,
            surface3 = ps.surface3,
            disabledBg = ps.disabledBg,
            skeleton = ps.skeleton,
            textPlaceholder = ps.textPlaceholder,
            textDisabled = ps.textDisabled,
            pressed = ps.pressed,
            pressedHeld = ps.pressedHeld,

            // ── 跨 palette 共享（SharedGridTokens，仍分明暗）──
            success = shared.success,
            successContainer = shared.successContainer,
            onSuccessContainer = shared.onSuccessContainer,
            accent = shared.accent,
            accentText = shared.accentText,
            accentContainer = shared.accentContainer,
            onAccentContainer = shared.onAccentContainer,
            infoContainer = shared.infoContainer,
            onInfoContainer = shared.onInfoContainer,
            stateCalm = shared.stateCalm,
            stateAttention = shared.stateAttention,
            stateUrgent = shared.stateUrgent,
            numLg = shared.numLg,
            numMd = shared.numMd,
            numSm = shared.numSm,

            // ── 第三轴：按 family 取族表（Auto 已在此按 palette 解析）──
            coursePalette = courses,
        )
    }

    /**
     * 按 palette 名取档；**缺档 fail-fast**（测试与生产共用同一条路径）。
     *
     * 生产经 [scheme] / [tokens] 的枚举重载进来（`palette.name`）；
     * 漏登记新枚举值时炸的就是这个分支，消息里带 palette 名与明暗。
     */
    internal fun paletteSchemeByName(paletteName: String, isDark: Boolean): PaletteScheme {
        val pt = registry[paletteName] ?: error(
            "Palettes 缺档：palette=$paletteName（isDark=$isDark）没有注册配色表——" +
                "新增 AppPalette 枚举值必须在 Palettes.registry 登记；" +
                "静默回退会让「选了没效果」变幽灵 bug，故 fail-fast"
        )
        return if (isDark) pt.dark else pt.light
    }
}
