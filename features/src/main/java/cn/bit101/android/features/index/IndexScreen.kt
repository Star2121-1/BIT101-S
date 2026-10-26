package cn.bit101.android.features.index

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.motion.materialSharedAxisZIn
import cn.bit101.android.features.common.motion.materialSharedAxisZOut
import cn.bit101.android.features.component.WithLoginStatus
import cn.bit101.android.features.gallery.GalleryScreen
import cn.bit101.android.features.map.MapScreen
import cn.bit101.android.features.schedule.ScheduleScreen
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.seat.SeatScreen
import cn.bit101.android.features.user.UserScreen
import cn.bit101.android.features.web.WebScreen


/**
 * 主页底部导航栏
 */
@Composable
private fun IndexScreenNavBar(
    pages: List<IndexPage>,
    selectedIndex: String,
    onSelected: (String) -> Unit,
) {
    NavigationBar {
        pages.forEach { page ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = page.label
                    )
                },
                label = { Text(text = page.label) },
                selected = page.route == selectedIndex,
                onClick = { onSelected(page.route) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IndexScreen(
    mainController: MainController,
) {
    val vm: IndexViewModel = hiltViewModel()
    val indexScreenConfig = vm.indexScreenConfigFlow.collectAsState(initial = null).value ?: return

    val loginStatus by vm.loginStatusFlow.collectAsState(initial = null)
    val bit101Available by vm.bit101AvailableFlow.collectAsState()

    val state = rememberPagerState(
        initialPage = indexScreenConfig.initialPage,
        pageCount = { indexScreenConfig.pages.size }
    )

    val navController = rememberNavController()

    // 桌面组件的「立即预约」等入口可能带着「打开后去哪一页」的请求进来
    val pendingGoto by GotoRequest.route.collectAsState()

    // ⚠️ 起始页用 remember 固化首次取值：pendingGoto 随后会被 consume 成 null，
    //    若直接读它，NavHost 的 startDestination 会跟着变、整张导航图被重建，
    //    用户会看到页面莫名重置。
    val startRoute = remember {
        pendingGoto?.let { PageShowOnNav.getPage(it)?.toString() }
            ?: PageShowOnNav.Schedule.toString()
    }

    // Activity 已存在时（onNewIntent 路径）NavHost 不会重建，这里显式跳过去
    LaunchedEffect(pendingGoto) {
        val route = pendingGoto
            ?.let { PageShowOnNav.getPage(it)?.toString() }
            ?: return@LaunchedEffect
        if (navController.currentDestination?.route != route) {
            navController.navigate(route)
        }
        GotoRequest.consume()
    }

    val navEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navEntry?.destination?.route ?: startRoute

    LaunchedEffect(indexScreenConfig) {
        if (state.currentPage >= indexScreenConfig.pages.size) {
            state.scrollToPage(indexScreenConfig.initialPage)
        }
    }

    Scaffold(
        bottomBar = {
            IndexScreenNavBar(
                pages = indexScreenConfig.pages,
                selectedIndex = currentRoute,
                onSelected = {
                    if (it == currentRoute) return@IndexScreenNavBar
                    navController.navigate(route = it)
                }
            )
        }
    ) { paddingValues ->
        val bottomPadding = paddingValues.calculateBottomPadding()

        Column(modifier = Modifier.fillMaxSize()) {
            // BIT101 服务端不可用时给一条降级提示；正常时**不渲染** ⇒ 零布局影响。
            // 学校/座位功能仍可用，这条只解释「社区/成绩为什么刷不出来」。
            if (!bit101Available) {
                Bit101DegradedBanner()
            }

            NavHost (
                modifier = Modifier.weight(1f).padding(bottom = bottomPadding),
                navController = navController,
                startDestination = startRoute,
                enterTransition = { enterTransition },
                exitTransition = { exitTransition },
                popEnterTransition = { popEnterTransition },
                popExitTransition = { popExitTransition },
            ) {
                composable(route = PageShowOnNav.BIT101Web.toString()) {
                    WebScreen(mainController)
                }
                composable(route = PageShowOnNav.Gallery.toString()) {
                    WithLoginStatus(mainController, loginStatus) {
                        GalleryScreen(mainController)
                    }
                }
                composable(route = PageShowOnNav.Map.toString()) {
                    MapScreen(mainController)
                }
                composable(route = PageShowOnNav.Mine.toString()) {
                    UserScreen(mainController, id = 0)
                }
                composable(route = PageShowOnNav.Schedule.toString()) {
                    WithLoginStatus(mainController, loginStatus) {
                        ScheduleScreen(mainController)
                    }
                }
                composable(route = PageShowOnNav.Seat.toString()) {
                    WithLoginStatus(mainController, loginStatus) {
                        SeatScreen(mainController = mainController)
                    }
                }
            }
        }
    }

}

/** BIT101 服务端不可用时的顶部降级提示（正常时不渲染 ⇒ 零布局影响）。 */
@Composable
private fun Bit101DegradedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Text(
            text = "BIT101 服务暂时不可用（社区/成绩受影响）；座位与学校功能可正常使用",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val enterTransition = materialSharedAxisZIn()
private val exitTransition = materialSharedAxisZOut()
private val popEnterTransition = materialSharedAxisZIn()
private val popExitTransition = materialSharedAxisZOut()