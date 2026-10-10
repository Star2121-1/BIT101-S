package cn.bit101.android.data.net

import net.gotev.cookiestore.okhttp.JavaNetCookieJar
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
        .cookieJar(JavaNetCookieJar(cookieManager))
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
