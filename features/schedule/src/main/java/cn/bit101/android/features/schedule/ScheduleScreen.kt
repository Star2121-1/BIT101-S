package cn.bit101.android.features.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.schedule.activity.EclassActivityScreen
import cn.bit101.android.features.schedule.classroom.FreeClassroomSearch
import cn.bit101.android.features.schedule.component.TabPager
import cn.bit101.android.features.schedule.component.TabPagerItem
import cn.bit101.android.features.schedule.course.CourseSchedule
import cn.bit101.android.features.schedule.ddl.DDLSchedule

@Composable
fun ScheduleScreen(mainController: MainController) {
    val items = listOf(TabPagerItem("课表") {
        CourseSchedule(mainController, it)
    }, TabPagerItem("DDL") {
        DDLSchedule(mainController, it)
    }, TabPagerItem("空教室") {
        FreeClassroomSearch(mainController, it)
    }, TabPagerItem("动态") {
        // 延河课堂的课程动态（作业 / 资料 / 公告）—— 与 DDL 页的分工见其类注释
        EclassActivityScreen(mainController)
    })

    Scaffold {
        Box(
            modifier = Modifier.padding(top = it.calculateTopPadding())
        ) {
            TabPager(items)
        }
    }
}