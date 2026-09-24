package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.CampusNetInfo

/**
 * 校园网在线信息（深澜 Srun 自助接口，`http://10.0.0.55`）。
 *
 * ⚠️ 仅校园网环境可达；校外返回 null（UI 提示「需校园网环境」）。
 * 接口免登录（返回请求方所在 NAT 的在线会话），无需任何凭据。
 */
interface CampusNetRepo {

    /** 抓取并解析在线信息；不可达 / 不在校园网 / 未在线时返回 null。 */
    suspend fun fetchOnlineInfo(): CampusNetInfo?
}
