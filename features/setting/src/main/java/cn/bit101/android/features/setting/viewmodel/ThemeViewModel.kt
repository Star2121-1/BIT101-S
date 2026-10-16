package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.ThemeSettings
import cn.bit101.android.features.common.helper.withScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class ThemeViewModel @Inject constructor(
    private val themeSettings: ThemeSettings
) : ViewModel() {
    val darkThemeMode = themeSettings.darkThemeMode
    val dynamicTheme = themeSettings.dynamicTheme
    val autoRotate = themeSettings.autoRotate

    /** 配色方案（P5-C）：原版 / 一格暖棕 / 北理工，写入后主题层实时换色 */
    val appPalette = themeSettings.appPalette

    fun setDarkThemeMode(mode: DarkThemeMode) = withScope {
        themeSettings.darkThemeMode.set(mode)
    }

    fun setDynamicTheme(dynamic: Boolean) = withScope {
        themeSettings.dynamicTheme.set(dynamic)
    }

    fun setAutoRotate(rotate: Boolean) = withScope {
        themeSettings.autoRotate.set(rotate)
    }

    fun setAppPalette(palette: AppPalette) = withScope {
        themeSettings.appPalette.set(palette)
    }
}