package cn.bit101.android.config.user

import cn.bit101.android.config.common.toSettingItem
import cn.bit101.android.config.datastore.UserDataStore
import cn.bit101.android.config.user.base.LoginStatus
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** BIT101 社区账号自己的密码摘要（与学校密码分开，见 LoginStatus 注释）。 */
    override val communityPasswordDigest = userDataStore.communityPasswordDigest.toSettingItem()

    /** 学校身份验证失败的冷却（见 LoginRetryPolicy）。 */
    override val identityRetryAt = userDataStore.loginIdentityRetryAt.toSettingItem()
    override val identityAttempts = userDataStore.loginIdentityAttempts.toSettingItem()

    /** 运行期状态：BIT101 服务端是否可用（不落盘，默认可用）。 */
    override val bit101Available = MutableStateFlow(true)
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
        // ⚠️ 这里**不清** communityPasswordDigest：
        //   本方法也被「登录过程中的 clearAll()」调用，清了它会让每次手动登录
        //   都丢掉社区密码摘要 ⇒ 每次退回统一身份认证 ⇒ 又去撞学校 CAS。
        //   只有**主动登出**才清（见 DefaultLoginRepo.logout）。
        // 换号/登出后必须解除冷却：新账号不该继承上一个账号的退避窗口
        identityRetryAt.set(0L)
        identityAttempts.set(0)
        cookieManager.cookieStore.removeAll()
    }
}
