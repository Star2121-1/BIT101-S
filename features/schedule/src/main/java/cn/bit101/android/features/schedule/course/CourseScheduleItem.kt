package cn.bit101.android.features.schedule.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.bit101.android.data.schedule.CourseColorLogic

/**
 * @author flwfdd
 * @date 15/05/2023 00:35
 * @description 在课程表上显示的单个课程卡片
 * _(:з」∠)_
 *
 * 《一格 GRID》§8.2 / §8.3 落点：
 * - 块底 = 12 色板按课名哈希取的那支 fill，块内文字 = ink，左缘 4px 竖条 = ink；
 * - **同一门课在这里、时间线/抽屉、桌面组件四处同色** ⇒ 不允许任何随机性：
 *   旧版按 `Math.random()` 给每个块一个随机透明度（0.5~1.0），同门课每次进来看
 *   深浅都不一样，跨端同色无从谈起 —— 已删，底色按 fill 原样渲染；
 * - 三类条目（课程/考试/自定义）共用同一哈希 ⇒ 类型靠**第二编码**区分：
 *   考试条目出「考试」角标、自定义条目出「手动」角标（不靠颜色）。
 */
@Composable
internal fun CourseScheduleItem(
    modifier: Modifier,
    week: Int,
    item: ScheduleItem,
    color: ScheduleItemColor
) {
    // 块与块之间的深浅层次。
    //
    // 原版这里是 `Math.random() * 0.5f + 0.5f` —— 所以「统色」族下一片蓝也有层次，
    // 代价是同一门课每次进来深浅都不一样。GRID 重构时我把它整个删了，结果同色号的块
    // 长得一模一样，整屏像贴纸（用户原话：「之前虽然颜色都是蓝的，但还是有差距的」）。
    // 现在改由**课名哈希**取档：同一门课深浅恒定（可跨端重算），不同课之间仍有层次。
    val fillAlpha = remember(item.title) { CourseColorLogic.fillAlphaOf(item.title) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.containerColor.copy(alpha = fillAlpha),
            contentColor = color.contextColor,
        ),
    ) {
        Row {
            // §8.2 第二编码①：4px 左竖条（ink）。同色号的两门课撞在一起时，
            // 颜色帮不上忙，竖条 + 角标 + 教室才是真正在区分的信息。
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color.stripeColor)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 第二编码②：类型角标。普通课程不出角标（没信息量，还占格子）
                if (item.color != ScheduleColorEnum.Course) {
                    Text(
                        text = if (item.color == ScheduleColorEnum.Exam) "考试" else "手动",
                        style = MaterialTheme.typography.labelSmall.copy(
                            lineHeight = MaterialTheme.typography.labelSmall.lineHeight * 0.75,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                // 课程名
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.lineHeight * 0.75),
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 教室
                Box(contentAlignment = Alignment.BottomCenter) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.lineHeight * 0.75),
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            }
        }
    }
}
