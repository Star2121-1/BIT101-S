package cn.bit101.android.features.setting.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.NotifySettings
import cn.bit101.android.features.common.helper.withScope
import cn.bit101.android.features.notify.NotifyAppStartup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 提醒设置的 ViewModel。
 *
 * ⚠️ 每次改动都要**重新排期** —— 已排的 WorkManager 任务是按旧设置定下的时刻，
 * 不重排的话「改了提前量却不生效」；关掉总开关时也要把已排任务取消掉。
 */
@HiltViewModel
internal class NotifySettingViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val notifySettings: NotifySettings,
    private val scoreRepo: cn.bit101.android.data.repo.base.ScoreRepo,
) : ViewModel() {

    /**
     * 出分检查的最近状态。
     *
     * ⚠️ 成绩检查要经**学校统一身份认证**，可能被风控拦成「要短信验证」，
     * 而它是后台静默跑的 —— 不给用户看状态，「为什么没提醒」就完全无从判断。
     */
    private val _scoreStatus = MutableStateFlow<String?>(null)
    val scoreStatus: StateFlow<String?> = _scoreStatus.asStateFlow()

    init {
        refreshScoreStatus()
    }

    /** 「立即检查一次」的执行中状态（检查要几秒，按钮上要给反馈）。 */
    private val _checkingScore = MutableStateFlow(false)
    val checkingScore: StateFlow<Boolean> = _checkingScore.asStateFlow()

    /**
     * 用户手动触发一次成绩检查（**跳过 12h 限频**）。
     *
     * 自动路径靠限频躲学校风控，但用户可能刚知道出分了想马上确认 —— 给他这个入口，
     * 同时按钮期间禁用，避免连点变成连续登录。
     */
    fun checkScoreNow() {
        if (_checkingScore.value) return
        viewModelScope.launch {
            _checkingScore.value = true
            try {
                runCatching {
                    cn.bit101.android.features.notify.ScoreNotifyChecker
                        .checkAndNotify(appContext, force = true)
                }
            } finally {
                _checkingScore.value = false
                refreshScoreStatus()
            }
        }
    }

    fun refreshScoreStatus() = viewModelScope.launch {
        _scoreStatus.value = runCatching {
            cn.bit101.android.data.score.ScoreCheckStore.statusText(scoreRepo.lastCheck())
        }.getOrNull()
    }


    val enabled = notifySettings.enabled
    val classEnabled = notifySettings.classEnabled
    val classLeadMinutes = notifySettings.classLeadMinutes
    val ddlEnabled = notifySettings.ddlEnabled
    val ddlDayEnabled = notifySettings.ddlDayEnabled
    val ddlHourEnabled = notifySettings.ddlHourEnabled
    val seatEnabled = notifySettings.seatEnabled
    val seatSignInLeadMinutes = notifySettings.seatSignInLeadMinutes
    val scoreEnabled = notifySettings.scoreEnabled

    /** 上课提前量的可选项（分钟）——不给自由输入，免得填出「提前 3 天」这种怪值。 */
    val leadOptions = listOf(5L, 10L, 15L, 20L, 30L)

    /**
     * 签到提醒提前量的可选项（分钟）。
     *
     * ⚠️ 上限比上课提醒大：契约规则是「开始后 **60 分钟**内刷卡」，
     * 提前一刻钟走过去是常态，60 分钟（一进馆就提醒）也该允许。
     */
    val seatLeadOptions = listOf(5L, 15L, 30L, 60L)

    fun setEnabled(value: Boolean) = write { notifySettings.enabled.set(value) }

    fun setClassEnabled(value: Boolean) = write { notifySettings.classEnabled.set(value) }

    fun setClassLeadMinutes(minutes: Long) = write { notifySettings.classLeadMinutes.set(minutes) }

    fun setDdlEnabled(value: Boolean) = write { notifySettings.ddlEnabled.set(value) }

    fun setDdlDayEnabled(value: Boolean) = write { notifySettings.ddlDayEnabled.set(value) }

    fun setDdlHourEnabled(value: Boolean) = write { notifySettings.ddlHourEnabled.set(value) }

    fun setSeatEnabled(value: Boolean) = write { notifySettings.seatEnabled.set(value) }

    fun setSeatLeadMinutes(minutes: Long) = write { notifySettings.seatSignInLeadMinutes.set(minutes) }

    fun setScoreEnabled(value: Boolean) = write { notifySettings.scoreEnabled.set(value) }

    /**
     * 写设置 + 立即重排。
     *
     * ⚠️ 参数是 **suspend 块** —— `SettingItem.set()` 本身是挂起函数；
     * 而重排必须等写完再跑（先写后读，否则重排读到的是旧值）。
     */
    private fun write(block: suspend () -> Unit) = withScope {
        block()
        NotifyAppStartup.reschedule(appContext)
    }
}
