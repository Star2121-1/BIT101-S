package cn.bit101.android.config.setting

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.SettingDataStore
import cn.bit101.android.config.setting.base.ActivitySettings
import javax.inject.Inject

internal class DefaultActivitySettings @Inject constructor(
    settingDataStore: SettingDataStore
) : ActivitySettings {
    override val onlyHomework = settingDataStore.eclassActivityOnlyHomework.toSettingItem()

    override val limit = settingDataStore.eclassActivityLimit.toSettingItem()

    override val showExpired = settingDataStore.eclassActivityShowExpired.toSettingItem()
}
