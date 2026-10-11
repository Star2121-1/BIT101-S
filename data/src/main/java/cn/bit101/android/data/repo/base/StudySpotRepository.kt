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

    /** @param now 便于单测注入固定时刻。 */
    suspend fun suggest(now: LocalDateTime = LocalDateTime.now()): StudySpotLogic.Suggestion?
}
