package cn.bit101.android.data.schedule

import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.CourseColorFamily

/**
 * 课程配色族解析：把「用户存的族」解析成「实际生效的族」。
 *
 * ## 为什么住 `:data`
 * 课程色要跨端一致（课表网格 / 桌面组件 / 未来的时间线抽屉），组件侧（RemoteViews）
 * 拿不到 CompositionLocal，只能重算 ⇒ 解析必须是纯函数，放在大家都能依赖的 `:data`。
 * 枚举住 `:config`（:data 已依赖 config，见 data/build.gradle）。
 *
 * ## auto 按 palette 解析，不按 skin（关键决策，有单测固化）
 * 原方案 auto 按 skin 联动（skin=classic ⇒ LEGACY 统色）。但当前 alpha 用户默认
 * palette=warm、skin=classic，若按 skin 解析，升级后**所有** alpha 用户课程色
 * 从 12 色掉成统色——silent regression。按 palette 解析后：
 * ① 默认用户（palette=warm）观感零变化，仍拿经典 12 色；
 * ② 语义自洽：课程颜料跟随「配色世界」而非「布局形态」；
 * ③ 选「原版界面 × 原版配色」的忠实组合用户拿统色，正是「整套回到原版」的叙事。
 *
 * ⚠️ 显式选族永远优先于 auto——用户覆写过就不再随外观变（一次性覆写语义）。
 */
object CourseColorResolver {

    /**
     * 解析实际生效的课程配色族。
     *
     * @param stored 用户存的族（[CourseColorFamily.Auto] = 跟随外观）
     * @param palette 当前生效的配色轴
     * @return 实际生效的族（Auto 永远被解析掉，不会往下传）
     */
    fun resolve(stored: CourseColorFamily, palette: AppPalette): CourseColorFamily = when (stored) {
        CourseColorFamily.Auto -> when (palette) {
            AppPalette.Classic -> CourseColorFamily.Legacy
            AppPalette.Warm, AppPalette.Brand -> CourseColorFamily.Classic
        }
        else -> stored
    }
}
