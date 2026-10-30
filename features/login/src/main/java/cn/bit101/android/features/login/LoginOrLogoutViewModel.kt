package cn.bit101.android.features.login

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.data.common.SmsCodeRequestHub
import cn.bit101.android.data.repo.base.LoginRepo
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.helper.withScope
import cn.bit101.android.features.common.helper.withSimpleStateLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class LoginOrLogoutViewModel @Inject constructor(
    private val loginStatus: LoginStatus,
    private val loginRepo: LoginRepo,
    private val smsCodeRequestHub: SmsCodeRequestHub,
) : ViewModel() {
    // 过程的状态
    val checkLoginStateLiveData = MutableLiveData<SimpleState?>(null)
    val postLoginStateLiveData = MutableLiveData<SimpleState?>(null)

    val sidFlow = loginStatus.sid.flow

    // 检查登录状态
    fun checkLoginState() = withSimpleStateLiveData(checkLoginStateLiveData) {
        val res = loginRepo.checkLogin()
        if(!res) throw Exception("check login error")
    }

    // 登录（校园账号：学校 + best-effort 社区）
    fun login(username: String, password: String) = withSimpleStateLiveData(postLoginStateLiveData) {
        val smsCodeHandler = smsCodeRequestHub.createSmsCodeHandler()
        val res = loginRepo.login(username, password, smsCodeHandler)
        if(!res) throw Exception("login failed")
    }

    /**
     * 只登录 BIT101 社区账号（不动校园会话）。
     *
     * @param useSchoolIdentity true = 统一身份认证（服务端代做学校 CAS）；
     *   false = 社区密码登录（`/user/login`，服务端不碰 CAS）
     */
    fun loginCommunity(username: String, password: String, useSchoolIdentity: Boolean) =
        withSimpleStateLiveData(postLoginStateLiveData) {
            val res = loginRepo.loginCommunity(username, password, useSchoolIdentity)
            if (!res) throw Exception("community login failed")
        }

    /** 切换分页时清掉上一次的登录结果，避免旧 Success 让新分页立刻跳走。 */
    fun clearLoginResult() {
        postLoginStateLiveData.postValue(null)
    }

    // 登出
    fun logout() = withScope {
        loginRepo.logout()
        postLoginStateLiveData.postValue(null)
    }
}
