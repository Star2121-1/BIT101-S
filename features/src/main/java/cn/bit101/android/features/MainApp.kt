package cn.bit101.android.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.component.dialog.SmsCodeDialogHost
import cn.bit101.android.features.common.component.image.ImageHost
import cn.bit101.android.features.common.component.image.rememberImageHostState
import cn.bit101.android.features.common.component.snackbar.SnackbarHost
import cn.bit101.android.features.common.component.snackbar.rememberSnackbarState
import cn.bit101.android.features.common.helper.getAppVersion
import cn.bit101.android.features.common.nav.NavAnimation
import cn.bit101.android.features.common.nav.NavDestConfig
import cn.bit101.android.features.common.nav.composableEdit
import cn.bit101.android.features.common.nav.composableFollowList
import cn.bit101.android.features.common.nav.composableIndex
import cn.bit101.android.features.common.nav.composableLogin
import cn.bit101.android.features.common.nav.composableMessage
import cn.bit101.android.features.common.nav.composablePost
import cn.bit101.android.features.common.nav.composablePoster
import cn.bit101.android.features.common.nav.composableReport
import cn.bit101.android.features.common.nav.composableSetting
import cn.bit101.android.features.common.nav.composableUser
import cn.bit101.android.features.common.nav.composableWeb
import cn.bit101.android.features.common.nav.enterTransition
import cn.bit101.android.features.common.nav.exitTransition
import cn.bit101.android.features.common.nav.popEnterTransition
import cn.bit101.android.features.common.nav.popExitTransition
import cn.bit101.android.features.common.utils.ColorUtils
import cn.bit101.android.features.index.IndexScreen
import cn.bit101.android.features.login.LoginOrLogoutScreen
import cn.bit101.android.features.message.MessageScreen
import cn.bit101.android.features.postedit.PostEditScreen
import cn.bit101.android.features.poster.PosterScreen
import cn.bit101.android.features.report.ReportScreen
import cn.bit101.android.features.setting.SettingScreen
import cn.bit101.android.features.common.GotoRequest
import cn.bit101.android.features.user.MyFollowListScreen
import cn.bit101.android.features.user.UserScreen
import cn.bit101.android.features.versions.UpdateDialog
import cn.bit101.android.features.versions.VersionDialog
import cn.bit101.android.features.web.WebScreen
import cn.bit101.android.config.setting.base.PageShowOnNav
import cn.bit101.android.config.setting.base.toPageData
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@Composable
internal fun MainApp() {
    val vm: MainViewModel = hiltViewModel()
    val ctx = LocalContext.current

    // 控制器
    val navController = rememberNavController()
    val mainController = MainController(
        scope = rememberCoroutineScope(),
        navController = navController,
        snackbarHostState = rememberSnackbarState(
            scope = rememberCoroutineScope()
        ),
        imageHostState = rememberImageHostState()
    )

    val systemUiController = rememberSystemUiController()

    val backgroundColor = MaterialTheme.colorScheme.surface
    val bottomNavBarColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val darkTheme = !ColorUtils.isLightColor(MaterialTheme.colorScheme.background)
    LaunchedEffect(bottomNavBarColor, darkTheme) {
        systemUiController.setStatusBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme
        )

        systemUiController.setNavigationBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme
        )
    }

    // 版本信息
    val lastVersion = vm.lastVersionFlow.collectAsState(initial = null).value ?: return
    val appVersion = getAppVersion(ctx)

    // 显示当前版本信息
    if(lastVersion < appVersion.versionCode) {
        VersionDialog(
            onConfirm = vm::logout,
            onDismiss = { vm.setLastVersion(appVersion.versionCode) }
        )
    }

    // 自动检测更新
    val autoDetectUpgrade by vm.autoDetectUpgradeFlow.collectAsState(initial = false)
    if (autoDetectUpgrade) {
        UpdateDialog()
    }

    // 桌面小组件的「登录」按钮等入口可能带着**顶层路由**（如 login）进来。
    // bottom-nav 的页面路由（seat 等）由 IndexScreen 处理，这里只管顶层的。
    // ⚠️ IndexScreen 对它不认识的路由不会 consume（见其 LaunchedEffect），
    //    所以两条观察链不会互相抢。
    val pendingGoto by GotoRequest.route.collectAsState()
    LaunchedEffect(pendingGoto) {
        val route = pendingGoto ?: return@LaunchedEffect
        val isPageRoute = PageShowOnNav.allPages.any { it.toPageData().value == route }
        if (isPageRoute) return@LaunchedEffect
        if (NavDestConfig.fromRoute(route) != null) {
            navController.navigate(route)
            GotoRequest.consume()
        }
    }

    NavHost(
        modifier = Modifier.fillMaxSize().background(color = backgroundColor),
        navController = navController,
        startDestination = NavDestConfig.Index.route,
        enterTransition = { enterTransition },
        exitTransition = { exitTransition },
        popEnterTransition = { popEnterTransition },
        popExitTransition = { popExitTransition },
    ) {
        composableIndex(navAnim, navController = navController) {
            IndexScreen(mainController)
        }

        composableLogin(navAnim, navController = navController) {
            LoginOrLogoutScreen(mainController)
        }

        composableWeb(navAnim, navController) { _, url ->
            WebScreen(mainController, url = url)
        }

        composableSetting(navAnim, navController) { _, initialRoute ->
            SettingScreen(mainController, initialRoute)
        }

        composableUser(navAnim, navController) { _, uid ->
            Box(modifier = Modifier.navigationBarsPadding()) {
                UserScreen(mainController, uid)
            }
        }

        composablePoster(navAnim, navController) { _, id ->
            Box(modifier = Modifier.navigationBarsPadding()) {
                PosterScreen(mainController, id)
            }
        }

        composablePost(navAnim, navController) {
            Box(modifier = Modifier.navigationBarsPadding()) {
                PostEditScreen(mainController)
            }
        }

        composableEdit(navAnim, navController) { _, id ->
            Box(modifier = Modifier.navigationBarsPadding()) {
                PostEditScreen(mainController, id)
            }
        }

        composableReport(navAnim, navController) { _, type, id ->
            Box(modifier = Modifier.navigationBarsPadding()) {
                ReportScreen(mainController, type, id,)
            }
        }

        composableMessage(navAnim, navController) {
            Box(modifier = Modifier.navigationBarsPadding()) {
                MessageScreen(mainController)
            }
        }

        composableFollowList(navAnim, navController) { _, type ->
            Box(modifier = Modifier.navigationBarsPadding()) {
                MyFollowListScreen(mainController, type)
            }
        }
    }


    ImageHost(
        modifier = Modifier.fillMaxSize(),
        state = mainController.imageHostState,
        onDownloadImage = { str: String, callback: () -> Unit ->
            vm.imageDownloader.downloadAndAddImage(str, ctx, mainController, callback)
        },
    )

    SnackbarHost(
        state = mainController.snackbarHostState
    )

    SmsCodeDialogHost(
        smsCodeRequestHub = vm.smsCodeRequestHub
    )
}

/**
 * 导航过渡动画
 */
private val navAnim = NavAnimation(
    enterTransition = { enterTransition },
    exitTransition = { exitTransition },
    popEnterTransition = { popEnterTransition },
    popExitTransition = { popExitTransition },
)