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

    /**
     * 只清 **BIT101 自己**的登录态。
     *
     * ⚠️ 以前这里顺手清了 seatlib 的 token 与任务。方向没错（学校会话失效 ⇒
     * seatlib 的 phpCAS 会话确实也失效），但**位置错了**：配置层不该知道座位有哪些数据。
     * ⇒ 现在「一起清」由 `data` 的 `SessionCleanup.clearAll()` 负责。
     * **调用点请改用 `SessionCleanup`**，直接调这里只会清一半。
     */
    override suspend fun clear() {
        status.set(false)
        webVpn.set(false)
        sid.set("")
        password.set("")
        fakeCookie.set("")
        cookieManager.cookieStore.removeAll()
    }
}
