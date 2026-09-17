package cn.bit101.android.config.seat

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.UserDataStore
import cn.bit101.android.config.seat.base.SeatTaskStore
import javax.inject.Inject

internal class DefaultSeatTaskStore @Inject constructor(
    userDataStore: UserDataStore
) : SeatTaskStore {
    override val tasks = userDataStore.seatTasks.toSettingItem()
}
