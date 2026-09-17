package cn.bit101.android.config.user

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.UserDataStore
import cn.bit101.android.config.user.base.SeatLoginStatus
import javax.inject.Inject

internal class DefaultSeatLoginStatus @Inject constructor(
    userDataStore: UserDataStore
) : SeatLoginStatus {
    override val token = userDataStore.seatToken.toSettingItem()
}
