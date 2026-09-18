package cn.bit101.android.features.seat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.seat.ui.screen.CasLoginScreen
import cn.bit101.android.features.seat.ui.screen.NewTaskScreen
import cn.bit101.android.features.seat.ui.screen.SeatMapScreen
import cn.bit101.android.features.seat.ui.screen.TaskListScreen

@Composable
fun SeatScreen(mainController: MainController, viewModel: SeatViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val navigateToMap by viewModel.navigateToSeatMap.collectAsState()
    val showCasLogin by viewModel.casLoginFlow.collectAsState()

    navigateToMap?.let { (areaId, day) ->
        viewModel.clearNavigation()
        navController.navigate("seat_map/$areaId/$day")
    }

    if (showCasLogin) {
        CasLoginScreen(
            mainController = mainController,
            viewModel = viewModel,
            onBack = { viewModel.setCasLoginFlow(false) }
        )
        return
    }

    val navEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navEntry?.destination?.route ?: "new_task"
    // 座位图是详情页：不显示「预约 / 列表」切换（它自带返回），避免三层栏叠在一起
    val isDetailPage = currentRoute.startsWith("seat_map")

    Scaffold { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (!isDetailPage) {
                SeatSectionTabs(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        if (currentRoute != route) {
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    }
                )
            }
            NavHost(
                modifier = Modifier.fillMaxSize(),
                navController = navController,
                startDestination = "new_task"
            ) {
                composable("new_task") {
                    NewTaskScreen(
                        mainController = mainController,
                        viewModel = viewModel,
                        onTaskCreated = { navController.navigate("tasks") { launchSingleTop = true } }
                    )
                }
                composable("tasks") { TaskListScreen(mainController = mainController, viewModel = viewModel) }
                composable("seat_map/{areaId}/{day}") { backStackEntry ->
                    val areaId = backStackEntry.arguments?.getString("areaId") ?: ""
                    val day = backStackEntry.arguments?.getString("day") ?: ""
                    SeatMapScreen(
                        mainController = mainController,
                        areaId = areaId, day = day, viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToTasks = { navController.navigate("tasks") { popUpTo("seat_map") } }
                    )
                }
            }
        }
    }
}

/**
 * 「预约 / 列表」切换。
 *
 * 早期用的是 `NavigationBar` + 带图标的 `NavigationBarItem`（约 80dp），
 * 叠在 App 全局底栏之上，手机上会形成「内容 / 操作条 / 模块栏 / 全局栏」四层，
 * 高度浪费明显。现在压缩成 32dp 的胶囊分段控件，放在内容最上方。
 */
@Composable
private fun SeatSectionTabs(currentRoute: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(2.dp)
        ) {
            listOf("new_task" to "预约", "tasks" to "列表").forEach { (route, label) ->
                val selected = currentRoute == route
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(route) }
                        .height(28.dp)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
