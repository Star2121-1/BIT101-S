package cn.bit101.android.data.school

import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.api.option.PROD_URLS
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.NetworkEnv
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 乐学（Moodle）的**网页主页地址**。
 *
 * ## 谁在用
 *
 * 1. DDL 页的「学士帽」按钮 —— 弹窗里选「乐学主页」
 * 2. 动态页的设置页 —— 乐学入口
 *
 * 两处都要用，所以放在 `data` 层（放任一 feature 里另一处就拿不到）。
 *
 * ## 为什么不能写死一个常量
 *
 * 乐学的地址随「校内 / 校外（WebVPN）」两套环境不同：校内是
 * `https://lexue.bit.edu.cn`，校外要走 `webvpn.bit.edu.cn/https/<加密串>/` 的长地址。
 * BIT-Login SDK 把这两套表放在 `Config.Urls.campus` / `Config.Urls.webvpn`，
 * 并在登录时按当前模式写进 `Config.Urls.active`。
 *
 * 所以这里**照 SDK 的做法**来：按用户当前的「校外访问」设置选表、写回 `active`、
 * 取不到才回退静态配置 —— 否则校外用户点「乐学主页」会打开一个打不开的校内地址。
 *
 * ⚠️ 乐学**没有会话检查接口**（对比延河课堂有 `EclassRepo.isSessionAlive()`），
 * 所以点进主页可能先落到登录页 —— 这一点只能如实告诉用户，没法在这里免登录。
 */
@Singleton
class LexueUrls @Inject constructor(
    private val loginStatus: LoginStatus,
) {

    /** 当前环境下乐学主页的完整地址（保证非空）。 */
    suspend fun home(): String {
        val webVpn = runCatching { loginStatus.webVpn.get() }.getOrDefault(false)

        // SDK 要求先初始化（内部会装好 Config.Urls 的各套地址表）
        runCatching { NetworkEnv.ensureInitialized() }

        val campus = runCatching { Config.Urls.campus["lexue"] }.getOrNull()
        val viaWebVpn = runCatching { Config.Urls.webvpn["lexue"] }.getOrNull()

        // 与 SDK 行为保持一致：把「当前模式」下的地址落到 active
        runCatching {
            (if (webVpn) viaWebVpn else campus)?.let { Config.Urls.active["lexue"] = it }
        }

        return pickLexueHome(webVpn = webVpn, campus = campus, webVpnUrl = viaWebVpn)
    }
}

/**
 * 地址回退链（纯逻辑，单测锁）：**当前模式的那张表** → 静态配置兜底。
 *
 * ⚠️ 全仓原本没有任何地方引用 `ApiUrlOption.lexueUrl`（死配置），
 * 这里把它作为**最后兜底**：SDK 地址表读不出来时，至少给个校内地址，
 * 总比返回空字符串让按钮点了没反应强。
 */
internal fun pickLexueHome(webVpn: Boolean, campus: String?, webVpnUrl: String?): String =
    (if (webVpn) webVpnUrl else campus)?.takeIf { it.isNotBlank() }
        ?: PROD_URLS.lexueUrl
