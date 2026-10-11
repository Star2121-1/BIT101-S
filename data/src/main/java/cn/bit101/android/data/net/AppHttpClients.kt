package cn.bit101.android.data.net

import cn.bit101.api.model.common.SchoolDomains
import net.gotev.cookiestore.okhttp.JavaNetCookieJar
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.CookieManager
import java.util.concurrent.TimeUnit

/**
 * 「HTTP 客户端怎么建」—— **data 层只有这一份**。
 *
 * ## 为什么要抽（2026-10-11 认证盘点）
 *
 * 此前 `data` 里有 4 个 repo 各建一份 `OkHttpClient`（一卡通 / 图书馆 / 教学调整 /
 * 校园网），连 `cookieJar(JavaNetCookieJar(...))` 这种固定搭配都抄了两遍。
 *
 * 抄出来的差异**看不出来**（每一种都「能跑」），代价要等到某个站点变慢时才付：
 * 表现是「这个页面特别容易失败」，而查的人会先怀疑解析、怀疑登录态，
 * 最后才想到是超时策略不一致。
 *
 * ## ⚠️ 这次只做「消除重复」，**不改行为**
 *
 * 各 repo 原来的超时是各自调过的（学校门户慢给 15/20、内网接口给 5/5），
 * 这里**原样保留**。要统一超时是另一件事，得单独评估 ——
 * 顺手统一会把「内网秒回」和「门户要等 20 秒」这两种真实差异抹掉。
 */
object AppHttpClients {

    /**
     * 「认得 CAS 会话」的 cookie jar —— **让跨子系统的自动登录成立**。
     *
     * ## 为什么需要它
     *
     * 一卡通 / 图书馆 / 延河课堂这些子系统，**自己域下的会话 cookie 必须完整走一遍
     * CAS 跳转链才能建立**（`子系统 → login.bit.edu.cn → sso.bit.edu.cn → 子系统?ticket=`）。
     *
     * 普通 cookie jar 是**按域名匹配**的：请求 `dkykt.info.bit.edu.cn` 时本来就不该带
     * CAS 的 cookie（浏览器也如此）。问题出在**跳到 CAS 那一步** —— 只要 CAS 会话
     * cookie 的 domain 与跳转目标域对不上，jar 就带不上它，CAS 认不出会话，直接停在
     * 登录页。**表现和「没登录」一模一样，极难定位。**
     *
     * v1.9.51 真机取证：BIT101 已登录、课表正常（课表是把 cookie **当参数显式传**的，
     * 不受域名匹配影响），但一卡通显示「未登录，点下方登录一卡通」。
     *
     * ## 做法与边界
     *
     * 只在**请求 CAS 域时**补 CAS 会话 cookie（按 name 去重，已有的不覆盖）；
     * 其余请求完全走原逻辑。**不把 CAS 的 cookie 滥发给任意子系统。**
     *
     * ⚠️ 单条 cookie 构造失败只跳过它自己 —— 一条坏 cookie 不该让整个子系统取不到数据
     * （与 `WebViewCookieSync` 的"逐条容错"同一原则）。
     */
    // internal 而不是 private：这条边界（只在 CAS 域带）必须有单测盯着，
    // 见 AppHttpClientsTest —— 滥发 CAS 会话 cookie 是安全问题，不能只靠人记着。
    internal class SsoAwareCookieJar(private val cookieManager: CookieManager) : CookieJar {

        private val delegate = JavaNetCookieJar(cookieManager)

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matched = delegate.loadForRequest(url)
            if (!SchoolDomains.isSsoDomain(url.host)) return matched

            val already = matched.mapTo(HashSet()) { it.name }
            val extra = SchoolCookieStore.ssoSnapshot(cookieManager.cookieStore)
                .filter { it.name !in already }
                .mapNotNull { school ->
                    runCatching {
                        Cookie.Builder()
                            .name(school.name)
                            .value(school.value)
                            .domain(school.domain)
                            .build()
                    }.getOrNull()
                }
            return matched + extra
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
            delegate.saveFromResponse(url, cookies)
    }

    /** 学校站点（一卡通 / 图书馆门户）确实慢，给宽一点。 */
    private const val SCHOOL_CONNECT_SEC = 15L
    private const val SCHOOL_READ_SEC = 20L

    /** 免登录小页面的默认档（教学调整的列表页 / 通知正文）。 */
    private const val PLAIN_CONNECT_SEC = 8L
    private const val PLAIN_READ_SEC = 12L

    /**
     * 学校侧客户端：**带会话 cookie** + 跟随重定向。
     *
     * ⚠️ 必须跟随重定向：CAS 登录链路全程靠 302 串联
     * （`lib.bit.edu.cn/login` → `login.bit.edu.cn` → `sso.bit.edu.cn`），
     * 不跟随的话拿到的永远是登录页 —— 而表现和「没登录」一模一样，极难定位。
     */
    fun school(cookieManager: CookieManager): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(SsoAwareCookieJar(cookieManager))
        .connectTimeout(SCHOOL_CONNECT_SEC, TimeUnit.SECONDS)
        .readTimeout(SCHOOL_READ_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 免登录客户端：**不带 cookie**（内网接口 / 免登录公开页面）。
     *
     * @param connectSec / readSec 默认给「学校静态页」那档。
     *   内网接口（如校园网查询 `10.0.0.55`）应显式传更短的值 ——
     *   内网要么秒回、要么根本不通，等十几秒没有意义。
     */
    fun plain(
        connectSec: Long = PLAIN_CONNECT_SEC,
        readSec: Long = PLAIN_READ_SEC,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .build()
}
