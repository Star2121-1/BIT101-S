package cn.bit101.android.config.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserDataStoreMigration] 的判定规则单测。
 *
 * 这两条规则决定「明文 fake_cookie 怎么搬」与「幽灵登录态要不要重置」，
 * 都是**一次性的、错了很难发现**的逻辑（错了就是凭据上云 / 或界面假装已登录）⇒ 钉住。
 */
class UserDataStoreMigrationTest {

    // ------------------------------------------------------------ 搬迁判定

    @Test
    fun `旧明文非空且加密存储为空时要搬`() {
        assertEquals("old-value", UserDataStoreMigration.valueToMigrate("old-value", ""))
    }

    @Test
    fun `加密存储已有值时不搬`() {
        // 覆盖新值 = 把刚签发的凭据换成旧的
        assertNull(UserDataStoreMigration.valueToMigrate("old-value", "new-value"))
    }

    @Test
    fun `旧明文为空时不用搬`() {
        assertNull(UserDataStoreMigration.valueToMigrate("", ""))
        assertNull(UserDataStoreMigration.valueToMigrate("   ", ""))
    }

    @Test
    fun `旧明文非空但加密存储是空白字符时也搬`() {
        // 空白 = 没有有效凭据
        assertEquals("old-value", UserDataStoreMigration.valueToMigrate("old-value", "  "))
    }

    // ------------------------------------------------------------ 幽灵登录态

    @Test
    fun `学号为空时要重置登录态`() {
        // 换了设备：加密存储没跟过来（它不参与备份），但 login_status 被恢复成了 true
        assertTrue(UserDataStoreMigration.shouldResetLoginState(""))
    }

    @Test
    fun `学号还在时不重置登录态`() {
        // 正常设备上用户是登录着的 —— 重置会把人踢出去
        assertFalse(UserDataStoreMigration.shouldResetLoginState("112233"))
    }
}
