package cn.bit101.android.config.user

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.UserDataStore
import cn.bit101.android.config.user.base.LoginStatus
import java.net.CookieManager
import java.net.CookiePolicy
import javax.inject.Inject

internal class DefaultLoginStatus @Inject constructor(
    private val userDataStore: UserDataStore
) : LoginStatus {
    override val sid = userDataStore.loginSid.toSettingItem()
    override val password = userDataStore.loginPassword.toSettingItem()
    override val status = userDataStore.loginStatus.toSettingItem()
    override val webVpn = userDataStore.loginWebVpn.toSettingItem()


    override val fakeCookie = userDataStore.fakeCookie.toSettingItem()
    override val cookieManager = CookieManager(
        userDataStore.cookieStore,
        CookiePolicy.ACCEPT_ALL
    )

    override suspend fun clear() {
        status.set(false)
        webVpn.set(false)
        sid.set("")
        password.set("")
        fakeCookie.set("")
        cookieManager.cookieStore.removeAll()
        // 学校会话失效后 seatlib 的 phpCAS 会话也随之失效，一并清除座位侧凭据
        userDataStore.seatToken.remove()
    }
}
