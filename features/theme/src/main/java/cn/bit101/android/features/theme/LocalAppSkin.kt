package cn.bit101.android.features.theme

import androidx.compose.runtime.staticCompositionLocalOf
import cn.bit101.android.config.setting.base.AppSkin

/**
 * 当前 UI 形态（skin 轴）——由 `BIT101Theme` 注入（P5-F 起；本轮只落声明）。
 *
 * 默认 [AppSkin.Classic]：预览不提供也不崩（独立预览 / RemoteViews 之外的自绘
 * 直接渲染组件时拿到原版形态，与「GRID skin 未上线」的现状一致）。
 *
 * ⚠️ 为什么不与 [LocalThemeIsDark] 一样在 P5-B 就由 `BIT101Theme` 提供：
 * skin 决定布局/导航，它的切换要连带 NavHost 图表重建（P5-F，`key(skin)`），
 * 本轮只把 CompositionLocal 与默认值落定，让组件可以先按「读它」的形态写。
 *
 * ⚠️ 文件名刻意**不叫 AppSkin.kt**：`:config` 已有同名枚举文件
 * （`cn.bit101.android.config.setting.base.AppSkin`），全仓两个 AppSkin.kt
 * 会让 grep 必混淆。
 */
val LocalAppSkin = staticCompositionLocalOf<AppSkin> { AppSkin.Classic }
