package cn.bit101.android.features.schedule.transport

import androidx.lifecycle.ViewModel
import cn.bit101.android.data.bus.ShuttleLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 「交通」页的状态：只存**用户的选择**，不算时刻。
 *
 * ⚠️ 时刻推算全在 [ShuttleLogic]（纯函数、有单测），这里刻意不掺逻辑 ——
 * 这个页面最容易出错的就是「今天该看哪张表」，那部分必须可测。
 */
@HiltViewModel
internal class TransportViewModel @Inject constructor() : ViewModel() {

    /**
     * 当前选中的发车站点。
     *
     * 默认「北校区 徐特立图书馆」：校内的人多数是**从学校出发去地铁站**。
     */
    private val _stop = MutableStateFlow(ShuttleLogic.Stop.LIBRARY)
    val stop: StateFlow<ShuttleLogic.Stop> = _stop.asStateFlow()

    fun selectStop(value: ShuttleLogic.Stop) {
        _stop.value = value
    }

    /**
     * 用户手动指定的日类型；`null` = **按星期自动**。
     *
     * ⚠️ 为什么需要手动：法定节假日（国庆在周中）、调休补班、寒暑假 —— App **判断不了**，
     * 只能让用户覆盖。默认 `null`（自动）是有意的：大多数日子按星期就是对的，
     * 不该让用户每次进来都要选一次。
     */
    private val _dayTypeOverride = MutableStateFlow<ShuttleLogic.DayType?>(null)
    val dayTypeOverride: StateFlow<ShuttleLogic.DayType?> = _dayTypeOverride.asStateFlow()

    fun selectDayType(value: ShuttleLogic.DayType?) {
        _dayTypeOverride.value = value
    }
}
