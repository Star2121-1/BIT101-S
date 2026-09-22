package cn.bit101.android.config.setting

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.SettingDataStore
import cn.bit101.android.config.setting.base.NotifySettings
import javax.inject.Inject

internal class DefaultNotifySettings @Inject constructor(
    settingDataStore: SettingDataStore
) : NotifySettings {
    override val enabled = settingDataStore.notifyEnabled.toSettingItem()
    override val classEnabled = settingDataStore.notifyClassEnabled.toSettingItem()
    override val classLeadMinutes = settingDataStore.notifyClassLeadMinutes.toSettingItem()
    override val ddlEnabled = settingDataStore.notifyDdlEnabled.toSettingItem()
    override val ddlDayEnabled = settingDataStore.notifyDdlDayEnabled.toSettingItem()
    override val ddlHourEnabled = settingDataStore.notifyDdlHourEnabled.toSettingItem()
}
