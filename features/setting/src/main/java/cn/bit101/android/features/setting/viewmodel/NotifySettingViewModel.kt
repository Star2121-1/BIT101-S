package cn.bit101.android.features.setting.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.NotifySettings
import cn.bit101.android.features.common.helper.withScope
import cn.bit101.android.features.notify.NotifyAppStartup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
) : ViewModel() {

    val enabled = notifySettings.enabled
    val classEnabled = notifySettings.classEnabled
    val classLeadMinutes = notifySettings.classLeadMinutes
    val ddlEnabled = notifySettings.ddlEnabled
    val ddlDayEnabled = notifySettings.ddlDayEnabled
    val ddlHourEnabled = notifySettings.ddlHourEnabled
    val seatEnabled = notifySettings.seatEnabled
    val seatSignInLeadMinutes = notifySettings.seatSignInLeadMinutes

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
