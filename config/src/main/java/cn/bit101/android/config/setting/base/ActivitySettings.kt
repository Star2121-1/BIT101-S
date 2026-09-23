package cn.bit101.android.config.setting.base

import cn.bit101.android.config.common.SettingItem

/**
 * 「动态」页（延河课堂的课程动态）设置。
 *
 * 三项都只影响**展示口径**，不改变取数来源 —— 与「课程表设置」等一样，
 * 值存在 `SettingDataStore` 里，UI 通过 `SettingItem.flow` 实时订阅。
 */
interface ActivitySettings {

    /**
     * 只看作业。
     *
     * 默认 **false** —— 动态页的定位是"课程里最近发生了什么"，
     * 资料 / 公告都该显示；只有想当 DDL 用的人才需要打开。
     */
    val onlyHomework: SettingItem<Boolean>

    /** 条数上限。默认 **60**（页面提供 30 / 60 / 100 三选一）。 */
    val limit: SettingItem<Int>

    /**
     * 是否显示已过期条目。
     *
     * 默认 **true** —— 关掉后不再显示「截止时间已过」的作业动态
     * （只针对作业：资料 / 公告的 time 是发布时间，本就都在过去）。
     */
    val showExpired: SettingItem<Boolean>
}
