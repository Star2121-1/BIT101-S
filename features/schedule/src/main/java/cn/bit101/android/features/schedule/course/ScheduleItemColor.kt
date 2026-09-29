package cn.bit101.android.features.schedule.course

import androidx.compose.ui.graphics.Color

/**
 * 课程表上一张卡片（课程 / 考试 / 自定义日程）的配色。
 */
internal data class ScheduleItemColor(
    val boarderColor: Color,
    val containerColor: Color,
    val contextColor: Color,
)

/**
 * 「教学安排覆盖」给那一列的卡片定的**色调**。
 *
 * ## 为什么要有它
 * 学校调休时，某一天的意义和平时不一样，光靠列头的角标（放假 / 按周X）容易看漏：
 * - **放假**那天：课表上原本有课，但**不上**。直接把课藏掉会让人以为「本来就没课」，
 *   连这一周的进度都看不出来。所以**照常显示，只是换成灰调** —— 一眼就是「不作数」。
 * - **补课**那天：装的是**别的星期几**的课（10/10 周六按周四上课）。这些课的座位、
 *   老师、进度都跟标注的那天对不上，用主题第三色标出来，与提示条呼应。
 *
 * ⚠️ 色调只影响**观感**，不改变数据 —— 放假列里的日程仍然是「那天真的有的日程」。
 */
internal enum class ScheduleItemTint {
    /** 平常的一天。 */
    Normal,

    /** 放假：卡片转灰，表示「这天不上课」。 */
    Holiday,

    /** 补课：卡片转第三色，表示「这是借来的另一天的课表」。 */
    MakeUp,
}

/**
 * 把一套平常配色朝 [container] / [onContainer] 靠拢，得到调休用的变体。
 *
 * 刻意做成**纯函数**（进出都是 [Color]，不碰 `MaterialTheme`）：
 * 配色是「视觉决策」，但不该只能靠肉眼在真机上看出来 —— 抽出来就能写单测。
 *
 * @param ratio 靠拢程度（`1` = 完全变成 [container]）。⚠️ 别为了「柔和」调到很低，
 *   看不出来就等于这个功能不存在。
 */
internal fun ScheduleItemColor.tinted(
    container: Color,
    onContainer: Color,
    ratio: Float,
): ScheduleItemColor = ScheduleItemColor(
    boarderColor = blend(boarderColor, container, ratio),
    containerColor = blend(containerColor, container, ratio),
    contextColor = blend(contextColor, onContainer, ratio),
)

/**
 * 按 [ratio] 把 [from] 混向 [to]，**保留 [from] 的 alpha**。
 *
 * ⚠️ 刻意不用 `features.common` 的 `mixColor`：它内部走的是三参 `Color(r, g, b)`
 * 构造，**会把 alpha 丢成 1**。而边框色本来就是 `secondary.copy(alpha = 0.25f)`，
 * 一丢就变成实线框 —— 放假列一开「显示边框」就会满屏硬边。
 */
private fun blend(from: Color, to: Color, ratio: Float): Color = Color(
    red = from.red * (1 - ratio) + to.red * ratio,
    green = from.green * (1 - ratio) + to.green * ratio,
    blue = from.blue * (1 - ratio) + to.blue * ratio,
    alpha = from.alpha,
)
