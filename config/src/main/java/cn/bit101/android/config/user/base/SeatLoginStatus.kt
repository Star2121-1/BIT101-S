package cn.bit101.android.config.user.base

import cn.bit101.android.config.common.SettingItem

/**
 * 座位预约（seatlib）的登录状态。
 *
 * seatlib 的 phpCAS 会话与 BIT101 的学校会话是两套独立体系（详见 BIT101-S 的登录链路说明），
 * 因此这里单独持久化 seatlib 侧凭据，不并入 LoginStatus。
 *
 * 注意：命名上刻意避开 `SeatStatus` —— 该名字已被座位模块的座位可用状态枚举占用
 * （`cn.bit101.android.features.seat.model.SeatStatus`）。
 */
interface SeatLoginStatus {

    /**
     * seatlib 的 JWT。通过 `/api/cas/user` 换取后持久化，冷启动可直接复用，
     * 无需再走一次 WebView CAS 登录。
     */
    val token: SettingItem<String>
}
