package cn.bit101.android.features.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.helper.SimpleState

// 主界面 根据登陆状态显示登陆界面或者退出登陆界面
@Composable
fun LoginOrLogoutScreen(
    mainController: MainController,
) {
    val vm: LoginOrLogoutViewModel = hiltViewModel()

    // 检查登录状态
    val checkLoginStatusState by vm.checkLoginStateLiveData.observeAsState()
    val postLoginState by vm.postLoginStateLiveData.observeAsState()

    val sid by vm.sidFlow.collectAsState("")

    // 根据检查登录的情况显示不同的内容
    when (checkLoginStatusState) {
        // 检查失败，显示登录页面
        is SimpleState.Fail -> {
            // 两个分页：校园账号（座位/课表等直连学校的功能）与 BIT101（社区/成绩）。
            // 拆开的原因：BIT101 那边可以用**社区自己的密码**登录，服务端只校验密码、
            // 不代做学校 CAS —— 而 CAS 集发正是 2026-10-30 被学校 WAF 封 IP 的原因。
            var communityOnly by rememberSaveable { mutableStateOf(false) }
            val loading = postLoginState is SimpleState.Loading

            // safeDrawingPadding 必须有：否则 TabRow 会顶进状态栏（实测 BIT101 分页的
            // 点击热区落在 y≈48px，等于被状态栏吃掉一半）
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                TabRow(selectedTabIndex = if (communityOnly) 1 else 0) {
                    listOf("校园账号", "BIT101 社区").forEachIndexed { index, title ->
                        Tab(
                            selected = communityOnly == (index == 1),
                            enabled = !loading,
                            onClick = {
                                // 不清结果的话，上一个 tab 的 Success/Fail 会让新 tab 立刻跳走
                                vm.clearLoginResult()
                                communityOnly = index == 1
                            },
                            text = { Text(title) },
                        )
                    }
                }

                key(communityOnly) {
                    LoginPage(
                        loginState = postLoginState,
                        title = if (communityOnly) "登录 BIT101 社区" else "登录校园账号",
                        description = if (communityOnly) {
                            "「统一身份认证」用学校账号密码连接 BIT101；" +
                                "「密码登录」用已有 BIT101 账号的独立密码，不经过学校认证。"
                        } else {
                            "使用学校统一身份认证账号密码登录。成功后会自动尝试连接 BIT101，" +
                                "连接失败不影响校园功能；首次连接会自动创建 BIT101 账号。"
                        },
                        initialSid = sid,
                        usernameEnabled = !communityOnly || sid.isBlank(),
                        actions = if (communityOnly) listOf(
                            LoginAction("统一身份认证") { username, password ->
                                vm.loginCommunity(username, password, useSchoolIdentity = true)
                            },
                            LoginAction("密码登录") { username, password ->
                                vm.loginCommunity(username, password, useSchoolIdentity = false)
                            },
                        ) else listOf(
                            LoginAction("登录") { username, password ->
                                vm.login(username, password)
                            },
                        ),
                        onLoginSuccess = {
                            mainController.popBackStack()
                            mainController.snackbar(if (communityOnly) "BIT101 社区已连接OvO" else "校园账号已连接OvO")
                        },
                        onLoginFailed = {
                            mainController.snackbar(if (communityOnly) "BIT101 社区连接失败Orz" else "校园账号连接失败Orz")
                        },
                        // 放在 Column 里要给 weight，否则 fillMaxSize 会和上面的 TabRow 抢高度
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 检查成功，显示登出页面
        is SimpleState.Success -> {
            LogoutPage(
                sid = sid,
                onLogout = vm::logout,
                onBack = mainController::popBackStack
            )
        }

        // 检查中，显示加载动画
        is SimpleState.Loading -> {
            // 这里应该有一个加载动画
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center)
                    .width(64.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(64.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 如果还没有检查过，那么就进行检查
        null -> {
            vm.checkLoginState()
        }
    }
}
