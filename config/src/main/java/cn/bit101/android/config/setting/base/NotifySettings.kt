package cn.bit101.android.config.setting.base

import cn.bit101.android.config.common.SettingItem

/**
 * 提醒（通知）设置。
 *
 * 各提醒的提前量按「分钟」存 —— 单位统一，UI 层负责换成「提前 10 分钟」这类文案。
 */
interface NotifySettings {

    /** 总开关。关掉后不再排任何提醒（已排的会在下次重排时清掉）。 */
    val enabled: SettingItem<Boolean>

    /** 上课提醒开关。 */
    val classEnabled: SettingItem<Boolean>

    /** 上课提前量（分钟）。 */
    val classLeadMinutes: SettingItem<Long>

    /** DDL（作业截止）提醒开关。 */
    val ddlEnabled: SettingItem<Boolean>

    /** DDL 提前一天提醒。 */
    val ddlDayEnabled: SettingItem<Boolean>

    /** DDL 提前一小时提醒。 */
    val ddlHourEnabled: SettingItem<Boolean>

    /**
     * 座位签到提醒开关。
     *
     * 默认开 —— 错过签到会记违约，这是最「真金白银」的一条提醒。
     */
    val seatEnabled: SettingItem<Boolean>

    /** 座位签到提前量（分钟）。默认 15：规则是「开始后 60 分钟内刷卡」，提前一刻钟够从容。 */
    val seatSignInLeadMinutes: SettingItem<Long>

    /**
     * 出分提醒开关。
     *
     * ⚠️ 通知里**只有课名、没有分数**（用户定的隐私边界，见 `docs/codebase-survey.md`）。
     */
    val scoreEnabled: SettingItem<Boolean>
}
