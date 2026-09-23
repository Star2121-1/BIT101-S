package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.CampusCardSnapshot

/**
 * 校园卡（一卡通，`dkykt.info.bit.edu.cn`）。
 *
 * 登录与延河课堂同构：`/home/openHomePageByCas` 跳学校统一身份认证（USTC CAS），
 * 用户在 **App 内 WebView** 登录一次后 cookie 落在全局 CookieManager，
 * 这里经 `WebViewCookieSync` 同步进 OkHttp 的 cookie jar 即可携带会话请求。
 */
interface CampusCardRepo {

    /**
     * 取一张首页快照。
     *
     * 一卡通首页的接口形态还没实测过（首版先抓 HTML 做启发式解析），
     * 所以返回**原始快照**而不是结构化余额：解析出的候选字段 + 原文摘要。
     * UI 拿到什么显示什么，解析不出就引导用户点开 WebView。
     */
    suspend fun fetchSnapshot(): CampusCardSnapshot
}
