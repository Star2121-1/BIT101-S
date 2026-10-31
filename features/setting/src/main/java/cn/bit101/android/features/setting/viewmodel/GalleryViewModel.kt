package cn.bit101.android.features.setting.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.GallerySettings
import cn.bit101.android.data.repo.base.UserRepo
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.withScope
import cn.bit101.android.features.common.helper.withSimpleDataStateLiveData
import cn.bit101.api.model.common.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal data class GallerySettingData(
    val hideBotPoster: Boolean,
    val hideBotPosterInSearch: Boolean,
    val hideStrictMode: Boolean,
    val allowHorizontalScroll: Boolean,
) {
    companion object {
        val default = GallerySettingData(
            hideBotPoster = false,
            hideBotPosterInSearch = false,
            hideStrictMode = false,
            allowHorizontalScroll = true
        )
    }
}

/**
 * 话廊设置里**单个**开关的身份。
 *
 * ⚠️ 与 [CalendarToggle] 同一个理由：页面原来每个开关都调
 * `onSettingChange(settingData.copy(x = it))`，ViewModel 再把 4 个 key 全写一遍。
 * 而 `settingData` 首帧是 [GallerySettingData.default]（`hide*` 全 false、
 * `allowHorizontalScroll` 却是 **true**）—— 在这段占位窗口里点任意一个开关，
 * 其余三个会被写成默认值（用户开着「允许横向滑动」会被悄悄打开/关掉，反之亦然）。
 */
internal enum class GalleryToggle {
    HideBotPoster,
    HideBotPosterInSearch,
    HideStrictMode,
    AllowHorizontalScroll,
}

@HiltViewModel
internal class GalleryViewModel @Inject constructor(
    private val gallerySettings: GallerySettings,
    private val userRepo: UserRepo,
) : ViewModel() {
    val settingDataFlow = combine(
        gallerySettings.hideBotPoster.flow,
        gallerySettings.hideBotPosterInSearch.flow,
        gallerySettings.hideStrictMode.flow,
        gallerySettings.allowHorizontalScroll.flow,
    ) { settings ->
        GallerySettingData(
            hideBotPoster = settings[0],
            hideBotPosterInSearch = settings[1],
            hideStrictMode = settings[2],
            allowHorizontalScroll = settings[3],
        )
    }

    val hideUserUidsFlow = gallerySettings.hideUserUids.flow

    val hideUserInfosFlow = MutableLiveData<SimpleDataState<List<User>>?>(null)

    fun getHideUserInfos() = withSimpleDataStateLiveData(hideUserInfosFlow) {
        hideUserUidsFlow.map { uids ->
            uids.map { uid ->
                userRepo.getUserInfo(uid.toLong()).user
            }
        }.first()
    }

    fun reshowUser(index: Int) {
        val infoList = (hideUserInfosFlow.value as? SimpleDataState.Success)?.data?.toMutableList()
        withSimpleDataStateLiveData(hideUserInfosFlow) {
            val uidList = gallerySettings.hideUserUids.get().toMutableList()
            uidList.removeAt(index)
            gallerySettings.hideUserUids.set(uidList)

            infoList!!.removeAt(index)
            infoList
        }
    }

    fun switchShowAnonymous() = withScope {
        val uidList = gallerySettings.hideUserUids.get()
        if (uidList.isNotEmpty() && uidList.first() == -1) {
            gallerySettings.hideUserUids.set(uidList.drop(1))
        } else {
            gallerySettings.hideUserUids.set(listOf(-1).plus(uidList))
        }
    }

    /**
     * 改写**单个**开关（见 [GalleryToggle] 的说明）。
     *
     * 不要再回到「把整个 GallerySettingData 写一遍」：首帧占位值会把用户没碰过的
     * 开关覆盖掉。
     */
    fun setToggle(toggle: GalleryToggle, value: Boolean) = withScope {
        when (toggle) {
            GalleryToggle.HideBotPoster -> gallerySettings.hideBotPoster.set(value)
            GalleryToggle.HideBotPosterInSearch -> gallerySettings.hideBotPosterInSearch.set(value)
            GalleryToggle.HideStrictMode -> gallerySettings.hideStrictMode.set(value)
            GalleryToggle.AllowHorizontalScroll -> gallerySettings.allowHorizontalScroll.set(value)
        }
    }
}