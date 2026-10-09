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

    /**
     * 考试提醒开关。
     *
     * 默认开 —— 考试只有一次机会，错过无法补救，是最该提醒的一类。
     */
    val examEnabled: SettingItem<Boolean>

    /** 考前一天提醒（固定提前 24 小时）。 */
    val examDayEnabled: SettingItem<Boolean>

    /** 考试提前量（分钟）。默认 60：够从容走到考场、找座位。 */
    val examLeadMinutes: SettingItem<Long>

    /**
     * 教学安排调整（**补课**）提醒开关。
     *
     * 默认开 —— 缺课是真实损失（点名、进度落下），而且这条说的是「**课表本身变了**」，
     * 按平时的课表出门就会走错。
     *
     * ⚠️ 只有开关、**没有提前量**：时机固定为「补课日**前一天 20:00**」（睡前能看到，
     * 够安排第二天带哪本书、要不要早起）。给了提前量反而会让用户以为自己能改出更好的时机。
     */
    val adjustmentEnabled: SettingItem<Boolean>
}
