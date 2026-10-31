package cn.bit101.android.features.user

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.helper.ScoreUrl
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.user.page.ScorePage

/**
 * 成绩页入口。
 *
 * 2026-10-31 之前这里挂的是 `WebScreen(mainController, url = ScoreUrl)` —— 即把教务
 * 网页塞进 WebView。现在换成原生页，**网页版仍保留一个入口**（右上角）：
 * 教务有时会加新列（比如评教提示），原生页读不出来的东西，网页永远是兜底。
 */
@Composable
fun ScoreScreen(mainController: MainController) {
    val vm: ScoreViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    ScorePage(
        state = state,
        onRefresh = { vm.load(force = true) },
        onKeyword = vm::setKeyword,
        onToggleTerm = vm::toggleTerm,
        onToggleType = vm::toggleType,
        onClearFilters = vm::clearFilters,
        onDismiss = { mainController.popBackStack() },
        onOpenAccount = { mainController.navigate(NavDest.Setting("account")) },
        onOpenWeb = { mainController.openWebPage(ScoreUrl) },
    )
}
