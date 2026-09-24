package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.repo.base.EclassRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 带「延河课堂会话状态」的 ViewModel 基类 —— DDL 设置页与动态设置页共用。
 *
 * `null` = 检查中；`false` 只代表**这次没查通**（含网络抖动），UI 文案据此引导登录。
 */
internal abstract class EclassSessionViewModel : ViewModel() {

    protected abstract val eclassRepo: EclassRepo

    private val _eclassSessionAlive = MutableStateFlow<Boolean?>(null)
    val eclassSessionAlive: StateFlow<Boolean?> = _eclassSessionAlive.asStateFlow()

    /** 重新检查会话 —— 用户可能刚在 WebView 里登录完回来。 */
    fun checkEclassSession() {
        viewModelScope.launch {
            _eclassSessionAlive.value = null
            _eclassSessionAlive.value =
                runCatching { eclassRepo.isSessionAlive() }.getOrDefault(false)
        }
    }
}
