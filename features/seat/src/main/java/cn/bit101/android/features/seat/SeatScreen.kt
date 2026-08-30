package cn.bit101.android.features.seat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    val currentRoute = navEntry?.destination?.route ?: "tasks"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.EventSeat, "预约") },
                    label = { Text("预约") },
                    selected = currentRoute == "new_task",
                    onClick = { if (currentRoute != "new_task") navController.navigate("new_task") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.EventSeat, "列表") },
                    label = { Text("列表") },
                    selected = currentRoute == "tasks",
                    onClick = { if (currentRoute != "tasks") navController.navigate("tasks") }
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            navController = navController,
            startDestination = "new_task"
        ) {
            composable("new_task") { NewTaskScreen(mainController = mainController, viewModel = viewModel) }
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
