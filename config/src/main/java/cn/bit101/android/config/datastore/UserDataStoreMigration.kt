package cn.bit101.android.config.datastore

/**
 * 一次性数据迁移的**判定规则**（纯函数，可单测）。
 *
 * ## 背景（2026-10-22）
 *
 * `fake_cookie` 原先明文存在 `settings.preferences_pb`（DataStore）里，与「用户设置」
 * 挤在同一个文件。而这个文件**要参与云备份**（外观/提醒开关/页序得留住）⇒
 * 一个**活凭据**跟着设置一起上了云。
 *
 * ⇒ 把 `fake_cookie` 挪进加密存储，让 `settings.preferences_pb` 变成**纯用户设置**。
 *
 * ## 两条判定
 *
 * 1. **旧明文值要不要搬**：加密存储里已有值就不搬（不覆盖新值）——
 *    否则一次「老用户升级后恰好登出过」就会把登录态搞乱。
 * 2. **登录态要不要重置**：换了设备后 `sid` 是空的（加密存储不参与备份），
 *    但 `login_status` 会跟着设置文件被恢复成 `true` ⇒ 界面显示「已登录」、
 *    实际什么都做不了 ⇒ 这叫**幽灵登录态**，必须重置。
 */
internal object UserDataStoreMigration {

    /**
     * 旧明文值该不该搬进加密存储。
     *
     * @param legacyPlain 旧明文值（空 = 没有可搬的）
     * @param currentEncrypted 加密存储里现有的值
     * @return 要写入加密存储的值；`null` = 不用搬
     */
    fun valueToMigrate(legacyPlain: String, currentEncrypted: String): String? =
        legacyPlain.takeIf { it.isNotBlank() && currentEncrypted.isBlank() }

    /**
     * 登录态是不是「幽灵」（设备已换、学号没跟过来）。
     *
     * @param sid 加密存储里的学号
     */
    fun shouldResetLoginState(sid: String): Boolean = sid.isBlank()
}
