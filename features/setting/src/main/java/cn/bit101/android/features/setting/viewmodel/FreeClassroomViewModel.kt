package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.FreeClassroomSettings
import cn.bit101.android.data.repo.base.FreeClassroomRepo
import cn.bit101.android.data.repo.base.LoginRepo
import cn.bit101.android.features.common.helper.*
import cn.bit101.api.model.common.CampusInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

internal data class FreeClassroomSettingData(
    val currentCampus: CampusInfo,
    val hideBusyClassroom: Boolean,
    val freeMinutesThreshold: Long,
) {
    companion object {
        val default = FreeClassroomSettingData(
            currentCampus = CampusInfo("", ""),
            hideBusyClassroom = true,
            freeMinutesThreshold = 5L,
        )
    }
}

@HiltViewModel
internal class FreeClassroomViewModel @Inject constructor(
    private val freeClassroomSettings: FreeClassroomSettings,
    private val freeClassroomRepo: FreeClassroomRepo,
    private val loginRepo: LoginRepo,
) : ViewModel() {
    val settingDataFlow = combine(
        freeClassroomSettings.currentCampusCode.flow,
        freeClassroomSettings.currentCampusDisplayName.flow,
    ) { settings ->
        FreeClassroomSettingData(
            currentCampus = CampusInfo(
                code = settings[0],
                displayName = settings[1],
            ),
            hideBusyClassroom = freeClassroomSettings.hideBusyClassroom.get(),
            freeMinutesThreshold = freeClassroomSettings.freeMinutesThreshold.get(),
        )
    }

    val changeSettingStatusLiveData = MutableLiveData<SimpleState?>(null)

    fun setCampus(campus: CampusInfo) = withSimpleStateLiveData(changeSettingStatusLiveData) {
        freeClassroomSettings.currentCampusDisplayName.set(campus.displayName)
        freeClassroomSettings.currentCampusCode.set(campus.code)
    }
    fun setFreeMinutesThreshold(freeMinutesThreshold: Long) = withSimpleStateLiveData(changeSettingStatusLiveData) {
        freeClassroomSettings.freeMinutesThreshold.set(freeMinutesThreshold)
    }

    /**
     * 只改「隐藏当前不空闲的教室」这一个开关。
     *
     * ⚠️ 原来这里叫 `setSettingData(data)`，一次写 4 个 key，基准是页面首帧的
     * [FreeClassroomSettingData.default]（校区是 `CampusInfo("", "")`、阈值 5）。
     * 在校区/阈值真值到位前点一下这个开关，就会把**默认校区清空、阈值重置成 5** ——
     * 比课表那次更疼。校区与阈值各有独立 setter（[setCampus] / [setFreeMinutesThreshold]），
     * 本来就不该经过这里。
     */
    fun setHideBusyClassroom(hide: Boolean) = withScope {
        freeClassroomSettings.hideBusyClassroom.set(hide)
    }

    val getBuildingTypeStatusLiveData = MutableLiveData<SimpleDataState<List<CampusInfo>>?>(null)

    fun loadCampusInfos() = withSimpleDataStateLiveData(getBuildingTypeStatusLiveData) {
        loginRepo.doOperationRequiresLogin(freeClassroomRepo::getCampusInfos)
    }
}