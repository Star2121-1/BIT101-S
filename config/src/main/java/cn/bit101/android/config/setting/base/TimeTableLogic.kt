package cn.bit101.android.config.setting.base

import java.time.LocalTime

/**
 * 学校官方作息（党政办公室印发，自 2021-08-23 起实行）。
 *
 * ⚠️ 正常情况下**不用它** —— 取课表设置里的时间表
 * （`CourseScheduleSettings.timeTable`，用户可在设置里自行编辑）。
 * 这里只是设置读取失败时的保底，内容与 `SettingDataStore` 里的默认值一致。
 *
 * 放在 `config` 模块是为了让**课堂提醒（features/notify）**与
 * 桌面组件（features/widget）共用同一份，不写两套。
 */
val FALLBACK_TIME_TABLE: TimeTable = listOf(
    TimeTableItem(LocalTime.of(8, 0), LocalTime.of(8, 45)),
    TimeTableItem(LocalTime.of(8, 50), LocalTime.of(9, 35)),
    TimeTableItem(LocalTime.of(9, 55), LocalTime.of(10, 40)),
    TimeTableItem(LocalTime.of(10, 45), LocalTime.of(11, 30)),
    TimeTableItem(LocalTime.of(11, 35), LocalTime.of(12, 20)),
    TimeTableItem(LocalTime.of(13, 20), LocalTime.of(14, 5)),
    TimeTableItem(LocalTime.of(14, 10), LocalTime.of(14, 55)),
    TimeTableItem(LocalTime.of(15, 15), LocalTime.of(16, 0)),
    TimeTableItem(LocalTime.of(16, 5), LocalTime.of(16, 50)),
    TimeTableItem(LocalTime.of(16, 55), LocalTime.of(17, 40)),
    TimeTableItem(LocalTime.of(18, 30), LocalTime.of(19, 15)),
    TimeTableItem(LocalTime.of(19, 20), LocalTime.of(20, 5)),
    TimeTableItem(LocalTime.of(20, 10), LocalTime.of(20, 55)),
)

/** `9:5` → `09:05`。 */
fun hm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

/** 某一小节的开始时刻；节次越界返回 null。 */
fun sectionStart(table: TimeTable, section: Int): LocalTime? =
    table.getOrNull(section - 1)?.startTime

/** 某一小节的结束时刻；节次越界返回 null。 */
fun sectionEnd(table: TimeTable, section: Int): LocalTime? =
    table.getOrNull(section - 1)?.endTime

/**
 * 连续节次对应的时间段文本，如 `09:55-12:20`。
 *
 * 节次越界（学校新增了节次而时间表没更新）时返回空串 ——
 * 宁可少显示信息，也不要显示一个错的时间。
 */
fun courseTimeText(
    table: TimeTable,
    startSection: Int,
    endSection: Int,
): String {
    val start = sectionStart(table, startSection) ?: return ""
    val end = sectionEnd(table, endSection) ?: return ""
    return "${hm(start)}-${hm(end)}"
}
