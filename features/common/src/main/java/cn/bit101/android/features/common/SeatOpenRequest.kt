package cn.bit101.android.features.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SeatOpenRequest {

    const val TAB_NEW_TASK = 0
    const val TAB_TASKS = 1

    private val _tab = MutableStateFlow<Int?>(null)
    val tab: StateFlow<Int?> = _tab

    fun request(tab: Int) {
        _tab.value = tab
    }

    fun consume() {
        _tab.value = null
    }
}
