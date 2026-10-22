package cn.bit101.android.config.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.bit101.android.config.datastore.basic.EncryptedPreferencesItem
import cn.bit101.android.config.datastore.basic.Preferences
import cn.bit101.android.config.datastore.basic.PreferencesDataStoreItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.CookieStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class UserDataStore @Inject constructor(
    private val preferences: Preferences
) {
    // Cookie 存储
    val cookieStore: CookieStore = preferences.COOKIE_PREFERENCES_STORE

    // fake-cookie 存储
    //
    // ⚠️ 2026-10-22：**从明文 DataStore 迁到加密存储**。
    // 它是一个**活凭据**（BIT101 站点的会话票据，注入 WebView 的 localStorage），
    // 原先明文躺在 `settings.preferences_pb` 里 —— 而那个文件要参与云备份
    // （外观 / 提醒开关 / 页序得留住）⇒ 凭据跟着设置一起上云了。
    // 迁到加密存储后，`settings.preferences_pb` 变成**纯用户设置**，可以放心备份。
    //
    // 旧明文值的搬迁见 [migrateLegacyPlainFakeCookie]（幂等，失败不影响主流程）。
    private val FAKE_COOKIE_NAME = "fake_cookie"
    val fakeCookie = EncryptedPreferencesItem(preferences.ENCRYPTED_SHARED_PREFERENCES, FAKE_COOKIE_NAME)

    // 登陆状态
    private val LOGIN_STATUS = booleanPreferencesKey("login_status")
    val loginStatus = PreferencesDataStoreItem(LOGIN_STATUS, false, preferences.SETTING_DATASTORE)

    private val LOGIN_WEB_VPN = booleanPreferencesKey("login_web_vpn")
    val loginWebVpn = PreferencesDataStoreItem(LOGIN_WEB_VPN, false, preferences.SETTING_DATASTORE)

    // 下面都是加密存储
    // 登陆学号
    private val LOGIN_SID = "login_sid"
    val loginSid = EncryptedPreferencesItem(preferences.ENCRYPTED_SHARED_PREFERENCES, LOGIN_SID)

    // 密码
    private val LOGIN_PASSWORD = "login_password"
    val loginPassword = EncryptedPreferencesItem(preferences.ENCRYPTED_SHARED_PREFERENCES, LOGIN_PASSWORD)

    // 座位预约（seatlib）JWT，与学校会话独立，见 SeatLoginStatus
    private val SEAT_TOKEN = "seat_token"
    val seatToken = EncryptedPreferencesItem(preferences.ENCRYPTED_SHARED_PREFERENCES, SEAT_TOKEN)

    // 座位预约任务列表（JSON），需跨进程存活，见 SeatTaskStore
    private val SEAT_TASKS = stringPreferencesKey("seat_tasks")
    val seatTasks = PreferencesDataStoreItem(SEAT_TASKS, "", preferences.SETTING_DATASTORE)

    /**
     * 迁移用的后台作用域。
     *
     * 迁移是**幂等**的（跑一次就到位，再跑也无副作用），失败也只是「旧明文值还在」，
     * 不影响任何功能 ⇒ 放在后台跑，不阻塞 App 启动。
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrationScope.launch { migrateLegacyPlainFakeCookie() }
    }

    /**
     * 一次性迁移：明文 `fake_cookie` → 加密存储。
     *
     * 三步（顺序有讲究）：
     * 1. 读旧明文值；**加密存储里已有值就不搬**（不覆盖更新的登录态）
     * 2. 删掉旧明文 key —— 不删的话它会**继续被备份到云**
     * 3. 顺手自愈**幽灵登录态**：换了设备后学号没跟过来（加密存储不参与备份），
     *    但 `login_status` 跟着设置文件被恢复成了 `true` ⇒ 界面显示「已登录」、
     *    实际什么都做不了 ⇒ 重置为 false。
     */
    private suspend fun migrateLegacyPlainFakeCookie() {
        val legacyKey = stringPreferencesKey(FAKE_COOKIE_NAME)
        val legacyPlain = runCatching {
            preferences.SETTING_DATASTORE.data.first()[legacyKey]
        }.getOrNull().orEmpty()

        if (legacyPlain.isNotBlank()) {
            // 写之前**再确认一次**加密存储仍是空的：期间可能刚登录过，
            // 那时用旧明文盖掉新签发的凭据就坏了。
            val stillEmpty = runCatching { fakeCookie.get() }.getOrDefault("").isBlank()
            if (stillEmpty) {
                val toMigrate = UserDataStoreMigration.valueToMigrate(legacyPlain, "")
                if (toMigrate != null) {
                    runCatching { fakeCookie.set(toMigrate) }
                }
            }
            // ⚠️ 无论搬没搬成，旧明文 key 都要删 —— 留着它就等于继续把它备份上云
            runCatching {
                preferences.SETTING_DATASTORE.edit { it.remove(legacyKey) }
            }
        }

        // 幽灵登录态自愈：学号没了（换了设备）就别假装还登录着。
        // ⚠️ 只在「确实是 true」时才写 —— 否则每次冷启动都白写一次 DataStore。
        if (UserDataStoreMigration.shouldResetLoginState(runCatching { loginSid.get() }.getOrDefault(""))) {
            if (runCatching { loginStatus.get() }.getOrDefault(false)) {
                runCatching { loginStatus.set(false) }
            }
            if (runCatching { loginWebVpn.get() }.getOrDefault(false)) {
                runCatching { loginWebVpn.set(false) }
            }
        }
    }
}
