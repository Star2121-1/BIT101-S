package cn.bit101.android.features.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.schedule.activity.EclassActivityScreen
import cn.bit101.android.features.schedule.classroom.FreeClassroomSearch
import cn.bit101.android.features.schedule.component.TabPager
import cn.bit101.android.features.schedule.component.TabPagerItem
import cn.bit101.android.features.schedule.course.CourseSchedule
import cn.bit101.android.features.schedule.ddl.DDLSchedule

@Composable
fun ScheduleScreen(mainController: MainController) {
    // 组件点 DDL / 动态条目进来时，带着「停在第几个 tab」的请求
    val focus by GotoRequest.focus.collectAsState()

    // ⚠️ 顺序 = 页签顺序，**必须与 `ScheduleTabs` 里的下标一致**（组件按那套下标跳 tab）
    // 用户 2026-09-23 要求「DDL 与动态挨着」，所以「动态」从最后挪到 DDL 后面。
    // 这里的选中态是 TabPager 内部的 rememberPagerState、**不持久化** → 重排不会串页。
    val items = listOf(TabPagerItem("课表") {
        CourseSchedule(mainController, it)
    }, TabPagerItem("DDL") {
        DDLSchedule(mainController, it)
    }, TabPagerItem("动态") {
        // 延河课堂的课程动态（作业 / 资料 / 公告）—— 与 DDL 页的分工见其类注释
        EclassActivityScreen(mainController)
    }, TabPagerItem("空教室") {
        FreeClassroomSearch(mainController, it)
    })

    Scaffold {
        Box(
            modifier = Modifier.padding(top = it.calculateTopPadding())
        ) {
            TabPager(
                items = items,
                requestedPage = focus?.tab,
                // 只消费「切 tab」那部分：定位键留给 DDL / 动态列表去用
                onRequestHandled = { GotoRequest.consumeTab() },
            )
        }
    }
}