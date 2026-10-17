package cn.bit101.android.features.schedule.classroom

import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.data.school.StudySpotLogic
import java.time.LocalTime

/**
 * 空教室页「下一个变忙 / 下一个恢复空闲」时刻计算（纯函数，单测见 `ClassroomBusyTimeLogicTest`）。
 *
 * ⚠️ **两套基准**：忙节列表（`busyTimeStr` 解析结果）是 **1-based 节次号**，
 * 而 [TimeTable] 是 **0-based 列表** —— 本文件里的 `- 1` / `+ 1` 全是基准换算，不是笔误。
 * 「现在在第几节 / 是否正在上课」一律经 [StudySpotLogic.sectionFor]（同一口径的唯一实现）取得，
 * 不再在本地重算一份 —— 口径若变，两处一起变。
 */
internal object ClassroomBusyTimeLogic {

    /**
     * 下一个开始不空闲的时间。
     *
     * - [LocalTime.MIN]：计算时就已经不空闲（正在上「忙」课）；
     * - [LocalTime.MAX]：直到今天结束都保持空闲。
     *
     * @param busySections1Based 升序的 1-based 忙节号（全天空闲 = 空列表）
     */
    fun nextBusyTimeOf(
        busySections1Based: List<Int>,
        table: TimeTable,
        now: LocalTime,
    ): LocalTime {
        // 同一口径复用：正在上课→本节 / 课间或首节前→下一节 / 全天结束→null
        val section = StudySpotLogic.sectionFor(table, now) ?: return LocalTime.MAX

        // sectionFor 只在 [start, end) 内返回「本节」；课间 / 首节前返回的是下一节（now < start）
        val inClass = !now.isBefore(table[section - 1].startTime)

        if (inClass && busySections1Based.contains(section)) return LocalTime.MIN

        // 正在上课：从「本节之后」找下一忙节；课间 / 首节前：即将开始的这一节也可算
        // （section 是 1-based，table 是 0-based，取值时还要 -1）
        val base = if (inClass) section + 1 else section
        val nextBusy = busySections1Based.firstOrNull { it >= base } ?: return LocalTime.MAX
        return table[nextBusy - 1].startTime
    }

    /**
     * 下一个（间隔长于阈值的）恢复空闲时间；仅在 [nextBusyTimeOf] 返回 [LocalTime.MIN] 时有意义。
     *
     * @param busySections1Based 升序的 1-based 忙节号
     * @param thresholdMinutes 「空闲」所需的最短间隔（分钟），来自设置页
     */
    fun nextFreeTimeOf(
        busySections1Based: List<Int>,
        table: TimeTable,
        now: LocalTime,
        thresholdMinutes: Long,
    ): LocalTime {
        // 理论不可达：进入条件（nextBusyTime == MIN）已要求「当前节在忙节列表内」⇒ 列表非空。
        // 守卫仅防未来改动破坏该前提（旧实现在这里会对空列表 `.last()` 直接崩）。
        if (busySections1Based.isEmpty()) return LocalTime.MAX

        val indices0Based = busySections1Based.map { it - 1 }
        for (i in 0 until indices0Based.size - 1) {
            val nowIndex = indices0Based[i]
            val nextIndex = indices0Based[i + 1]
            if (table[nowIndex].endTime >= now
                && table[nextIndex].startTime.toSecondOfDay() - table[nowIndex].endTime.toSecondOfDay()
                > thresholdMinutes * 60
            ) {
                return table[nowIndex].endTime
            }
        }
        // 无论如何, 所有课上完后教室一定会空闲下来
        return table[indices0Based.last()].endTime
    }
}
