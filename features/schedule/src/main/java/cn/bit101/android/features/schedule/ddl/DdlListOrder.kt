package cn.bit101.android.features.schedule.ddl

import cn.bit101.android.data.database.entity.DDLScheduleEntity

/**
 * DDL 列表的**分区顺序**（纯逻辑，单测锁）。
 *
 * 存在的理由有两个：
 * 1. 界面按「未完成在上、已完成在下」分区渲染 —— 顺序定义在这里，渲染方直接调，
 *    **别在两处各写一遍 `filter/sortedBy`**（早晚会改歪一处）
 * 2. 组件点条目跳进来时要**滚到那一条**，而 `LazyColumn` 的下标是
 *    「分区标题 + 条目」混在一起的 —— 「第几条」不等于「数组下标」。
 *    [flatIndexOf] 把这个换算收在一处并配单测（这是最容易错的一步：
 *    忘了 +1 的标题行，就会滚到上一条或下一条）。
 */
object DdlListOrder {

    /** 未完成区：按截止时间**升序**（最急的在上）。 */
    fun pending(events: List<DDLScheduleEntity>): List<DDLScheduleEntity> =
        events.filter { !it.done }.sortedBy { it.time }

    /** 已完成区：按时间**倒序**（最近做完的在上）。 */
    fun done(events: List<DDLScheduleEntity>): List<DDLScheduleEntity> =
        events.filter { it.done }.sortedByDescending { it.time }

    /**
     * [uid] 在 `LazyColumn` 里的下标（**含分区标题行**）；找不到返回 -1。
     *
     * 与渲染保持一致：未完成区非空则先占 1 行标题，再排它的条目；已完成区同理。
     */
    fun flatIndexOf(events: List<DDLScheduleEntity>, uid: String): Int {
        var index = 0

        val pendingItems = pending(events)
        if (pendingItems.isNotEmpty()) {
            val i = pendingItems.indexOfFirst { it.uid == uid }
            if (i >= 0) return index + 1 + i
            index += 1 + pendingItems.size
        }

        val doneItems = done(events)
        if (doneItems.isNotEmpty()) {
            val i = doneItems.indexOfFirst { it.uid == uid }
            if (i >= 0) return index + 1 + i
        }

        return -1
    }
}
