package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.StudySpotLogic
import java.time.LocalDateTime

/**
 * 「现在去哪儿自习」的取数入口。
 *
 * 职责：把**课表 / 教学调整 / 空教室**三份数据拼成一条建议（判断本身在 [StudySpotLogic]）。
 *
 * ⚠️ **取不到就返回 null** —— 一条瞎猜的建议比没有更糟。
 */
interface StudySpotRepository {

    /**
     * 完整建议（**排好序的候选列表**，界面可本地切换「换一个」）。
     *
     * ⚠️ 候选列表在**这一次取数里就全算好了** —— 「换一个」绝不能再打网络，
     *    否则点一下要等十几秒，比不做这个功能还糟。
     *
     * @return `null` = 连时间表都拿不到（没法判断「现在第几节」）；
     *   拿到了但没内容时返回的 [StudySpotLogic.Plan] 的 [StudySpotLogic.Plan.hasContent] 为 `false`。
     * @param now 便于单测注入固定时刻。
     */
    suspend fun plan(now: LocalDateTime = LocalDateTime.now()): StudySpotLogic.Plan?

    /**
     * 旧的一条建议（桌面组件走这条；它只用到 [StudySpotLogic.coarseTitle]）。
     *
     * @param now 便于单测注入固定时刻。
     */
    suspend fun suggest(now: LocalDateTime = LocalDateTime.now()): StudySpotLogic.Suggestion?
}
