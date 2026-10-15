package cn.bit101.android.config.setting

import cn.bit101.android.config.common.Transformer
import cn.bit101.android.config.common.map
import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.SettingDataStore
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.AppSkin
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.ThemeSettings
import cn.bit101.android.config.setting.base.toDarkThemeData
import javax.inject.Inject

internal class DefaultThemeSettings @Inject constructor(
    settingDataStore: SettingDataStore
) : ThemeSettings {

    /**
     * darkThemeMode 的转换器
     */
    private val darkThemeModeTransformer = object : Transformer<String, DarkThemeMode> {
        override fun invokeTo(value: String) = DarkThemeMode.getMode(value)
        override fun invokeFrom(value: DarkThemeMode) = value.toDarkThemeData().value
    }

    /**
     * appSkin 的转换器：脏值回落 Classic（getSkin 内的显式默认，不是 first()）
     */
    private val appSkinTransformer = object : Transformer<String, AppSkin> {
        override fun invokeTo(value: String) = AppSkin.getSkin(value)
        override fun invokeFrom(value: AppSkin) = value.value
    }

    /**
     * appPalette 的转换器：脏值回落 Warm（getPalette 内的显式默认，不是 first()
     * ——展示序首个是 Classic，用 first() 会让脏值用户莫名变回原版配色）
     */
    private val appPaletteTransformer = object : Transformer<String, AppPalette> {
        override fun invokeTo(value: String) = AppPalette.getPalette(value)
        override fun invokeFrom(value: AppPalette) = value.value
    }

    override val darkThemeMode = settingDataStore.settingDarkTheme.toSettingItem().map(darkThemeModeTransformer)

    override val appSkin = settingDataStore.settingAppSkin.toSettingItem().map(appSkinTransformer)

    override val appPalette = settingDataStore.settingAppPalette.toSettingItem().map(appPaletteTransformer)

    override val dynamicTheme = settingDataStore.settingDynamicTheme.toSettingItem()

    override val autoRotate = settingDataStore.settingRotate.toSettingItem()
}