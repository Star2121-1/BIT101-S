package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.LibBorrowResult

/**
 * 图书馆借阅（超星智慧门户「我的借阅」，`mylib.bit.edu.cn`）。
 *
 * ## 会话
 *
 * 与 eclass / 一卡通**同构**：用户在 **App 内 WebView** 走一次学校 CAS
 * （见 [cn.bit101.android.data.school.LibBorrowLogic.LOGIN_URL]，
 * 全程在 `*.bit.edu.cn` 域内，不会跳出 App），会话 cookie 落在全局
 * CookieManager；每次请求前经 `WebViewCookieSync` 同步进 OkHttp 的 cookie jar。
 *
 * ⚠️ **不需要新的账号体系** —— 图书馆用的就是学校统一身份认证。
 *
 * ## 为什么两个方法而不是一个
 *
 * 「当前在借」是**到期提醒的数据源**（必须在后台也能取），
 * 「历史借阅」只是展示用，失败不该拖累前者，所以分开、各自独立失败。
 */
interface LibBorrowRepo {

    /** 当前在借（提醒的数据源）。 */
    suspend fun fetchCurrent(): LibBorrowResult

    /** 历史借阅（纯展示）。 */
    suspend fun fetchHistory(): LibBorrowResult
}
