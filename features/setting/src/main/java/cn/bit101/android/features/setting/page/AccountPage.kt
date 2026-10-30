package cn.bit101.android.features.setting.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.component.Avatar
import cn.bit101.android.features.common.component.GeneratedAvatar
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.helper.rememberImagePicker
import cn.bit101.android.features.setting.component.SettingItemData
import cn.bit101.android.features.setting.component.SettingsColumn
import cn.bit101.android.features.setting.component.SettingsGroup
import cn.bit101.android.features.setting.viewmodel.AccountViewModel
import cn.bit101.api.model.common.User
import kotlin.random.Random


@Composable
private fun AccountPageContent(
    ifLogin: Boolean,
    checkingLogin: Boolean,
    updatingUserInfo: Boolean,
    user: User?,
    sid: String?,
    onOpenEditAvatarDialog: () -> Unit,
    onOpenEditNicknameDialog: () -> Unit,
    onOpenEditMottoDialog: () -> Unit,
    onCheckLogin: () -> Unit,
    onLogout: () -> Unit,
    onLogin: () -> Unit,
    schoolConnected: Boolean,
    communityStatusText: String,
    localAvatarSeed: Int,
    onOpenRandomAvatarDialog: () -> Unit,
) {
    val infoItems = listOf(
        SettingItemData.Custom(
            title = "头像",
            onClick = onOpenEditAvatarDialog,
            enable = !updatingUserInfo,
            suffix = {
                Avatar(
                    user = user,
                    low = false,
                    showIdentity = false,
                    localSeed = localAvatarSeed.takeIf { it >= 0 },
                )
            }
        ),
        SettingItemData.Button(
            title = "随机头像",
            subTitle = "本机生成（不上传），可换底色与动物",
            enable = !updatingUserInfo,
            onClick = onOpenRandomAvatarDialog,
        ),
        SettingItemData.Button(
            title = "昵称",
            text = user?.nickname ?: "",
            enable = !updatingUserInfo,
            onClick = onOpenEditNicknameDialog,
        ),
        SettingItemData.Button(
            title = "个性签名",
            text = user?.motto ?: "",
            enable = !updatingUserInfo,
            onClick = onOpenEditMottoDialog,
        ),
        SettingItemData.Card(
            title = "学号",
            text = sid ?: "",
        ),
        SettingItemData.Card(
            title = "UID",
            text = user?.id?.toString() ?: "",
        ),
    )

    val loginItems = listOf(
        // 两路独立连接状态（与新的双 Tab 登录页概念一致）
        SettingItemData.Card(
            title = "校园账号",
            subTitle = "学校统一身份认证会话（座位、课表等校内功能需要）",
            text = if (schoolConnected) "已连接" else "未连接",
        ),
        SettingItemData.Card(
            title = "BIT101 社区",
            subTitle = "社区独立账号（成绩、社区动态等需要；与校园账号相互独立）",
            text = communityStatusText,
        ),

        SettingItemData.Button(
            title = "登录状态检查",
            subTitle = "点击检查并自动恢复两路连接。APP 会使用已保存的凭据分别尝试恢复「校园账号」与「BIT101 社区」，恢复后上方状态会实时更新。",
            onClick = onCheckLogin,
            enable = !checkingLogin,
        ),

        if(ifLogin) SettingItemData.Button(
            title = "退出登录",
            subTitle = "清除所有登录状态和保存的账号密码",
            onClick = onLogout,
        ) else SettingItemData.Button(
            title = "登录",
            onClick = onLogin,
        ),
    )

    SettingsColumn{
        SettingsGroup(
            title = "个人信息",
            items = infoItems,
            visible = ifLogin && user != null,
        )
        SettingsGroup(
            title = "登录状态",
            items = loginItems,
        )
    }
}

@Composable
private fun EditDialog(
    title: String,
    text: String,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                shape = RoundedCornerShape(8.dp),
                value = text,
                onValueChange = onTextChanged
            )
        }
    )
}

@Composable
internal fun AccountPage(
    onLogin: () -> Unit,
    onSnackBar: (String) -> Unit,
) {
    val vm: AccountViewModel = hiltViewModel()

    val getUserInfoState by vm.getUserInfoStateFlow.collectAsState()

    val ifLogin by vm.loginStatusFlow.collectAsState(initial = false)

    val checkingLoginState by vm.checkLoginStateFlow.collectAsState()

    val sid by vm.sidFlow.collectAsState(initial = null)

    val communityDigest by vm.communityDigestFlow.collectAsState(initial = "")

    val bit101Available by vm.bit101AvailableFlow.collectAsState(initial = true)

    val updateAvatarState by vm.updateAvatarStateFlow.collectAsState()

    val updateUserInfoState by vm.updateUserInfoStateFlow.collectAsState()


    LaunchedEffect(ifLogin, checkingLoginState) {
        if (ifLogin && (getUserInfoState == null || getUserInfoState is SimpleDataState.Fail) && checkingLoginState !is SimpleState.Loading) {
            vm.getUserInfo()
        }
    }

    LaunchedEffect(checkingLoginState) {
        if (checkingLoginState is SimpleState.Fail) {
            onSnackBar("登录状态检查失败")
        } else if (checkingLoginState is SimpleState.Success) {
            onSnackBar("登录状态检查成功")
        }
    }

    LaunchedEffect(updateAvatarState, updateUserInfoState) {
        if (updateAvatarState is SimpleState.Fail || updateUserInfoState is SimpleState.Fail) {
            onSnackBar("更新失败")
        } else if (updateAvatarState is SimpleState.Success || updateUserInfoState is SimpleState.Success) {
            onSnackBar("更新成功")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.clearStates()
        }
    }

    var showNicknameEditDialog by rememberSaveable { mutableStateOf(false) }
    var showMottoEditDialog by rememberSaveable { mutableStateOf(false) }
    var localAvatarSeed by rememberSaveable { mutableStateOf(-1) }
    var showRandomAvatarDialog by rememberSaveable { mutableStateOf(false) }

    val imagePicker = rememberImagePicker(
        vm::updateAvatar
    )

    val user = (getUserInfoState as? SimpleDataState.Success)?.data?.user

    val communityConnected = communityDigest.isNotBlank()
    val communityStatusText = when {
        !communityConnected -> "未连接"
        !bit101Available -> "已连接 · 服务端暂不可用"
        else -> "已连接"
    }

    AccountPageContent(
        user = user,
        sid = sid,
        ifLogin = ifLogin,
        updatingUserInfo = updateAvatarState == SimpleState.Loading || updateUserInfoState == SimpleState.Loading,
        checkingLogin = checkingLoginState == SimpleState.Loading,
        onOpenEditAvatarDialog = imagePicker::pickImage,
        onOpenEditNicknameDialog = { showNicknameEditDialog = true },
        onOpenEditMottoDialog = { showMottoEditDialog = true },
        onCheckLogin = vm::checkLogin,
        onLogout = vm::logout,
        onLogin = onLogin,
        schoolConnected = ifLogin,
        communityStatusText = communityStatusText,
        localAvatarSeed = localAvatarSeed,
        onOpenRandomAvatarDialog = { showRandomAvatarDialog = true },
    )

    if(showNicknameEditDialog && user != null) {
        var editingNickname by rememberSaveable(user.nickname) { mutableStateOf(user.nickname) }
        EditDialog(
            title = "修改昵称",
            text = editingNickname,
            onDismiss = { showNicknameEditDialog = false },
            onTextChanged = { editingNickname = it },
            onConfirm = { vm.updateUserInfo(nickname = editingNickname) }
        )
    }

    if(showMottoEditDialog && user != null) {
        var editingMotto by rememberSaveable(user.motto) { mutableStateOf(user.motto) }
        EditDialog(
            title = "修改个性签名",
            text = editingMotto,
            onDismiss = { showMottoEditDialog = false },
            onTextChanged = { editingMotto = it },
            onConfirm = { vm.updateUserInfo(motto = editingMotto) }
        )
    }

    if(showRandomAvatarDialog) {
        RandomAvatarDialog(
            currentSeed = localAvatarSeed,
            onApply = {
                localAvatarSeed = it
                showRandomAvatarDialog = false
            },
            onDismiss = { showRandomAvatarDialog = false },
        )
    }
}

// 随机头像对话框（本机生成，不上传）
@Composable
private fun RandomAvatarDialog(
    currentSeed: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var seed by rememberSaveable {
        mutableStateOf(if (currentSeed >= 0) currentSeed else Random.nextInt(0, 100000))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("随机头像（本机）") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GeneratedAvatar(seed = seed, size = 96.dp)
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = { seed = Random.nextInt(0, 100000) }) { Text("换一个") }
                    TextButton(onClick = { onApply(seed) }) { Text("使用") }
                }
                TextButton(onClick = { onApply(-1) }) { Text("恢复原头像") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}