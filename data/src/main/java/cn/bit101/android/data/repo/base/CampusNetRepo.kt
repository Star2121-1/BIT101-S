package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.CampusNetResult

/**
 * 校园网在线信息（深澜 Srun 自助接口，`http://10.0.0.55`）。
 *
 * ⚠️ 仅校园网环境可达；校外会失败（[CampusNetResult.Failed]）。
 * 接口免登录（返回请求方所在 NAT 的在线会话），无需任何凭据。
 */
interface CampusNetRepo {

    /**
     * 抓取校园网在线信息。
     *
     * ⚠️ 返回 [CampusNetResult] 而不是可空值：**「不在校内」与「在校内但没认证」
     * 要能分辨**，否则 UI 只能一律说「需连接校园网」（用户在校内会被误导）。
     */
    suspend fun fetchOnlineInfo(): CampusNetResult
}
