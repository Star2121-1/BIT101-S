package cn.bit101.android.features.versions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.user.base.LoginStatus
import cn.bit101.android.features.common.helper.getAppVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 「从 1.2.0 升级上来」的一次性迁移弹窗。
 *
 * ⚠️ 看起来像死代码（只在 versionCode==4 时触发），但**不能删**：
 * 上游 1.2.0 的 versionCode 就是 4，用户若从那个版本升级上来，
 * 需要被强制走一次重新登录（会话结构变了）。对全新安装的用户它自然不出现。
 */
@Composable
fun VersionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val vm: VersionDialogViewModel = hiltViewModel()

    val context = LocalContext.current
    val appVersion = getAppVersion(context)

    if(appVersion.versionCode == 4L) {
        val status by vm.statusFlow.collectAsState(initial = null)

        if(status == null) return
        else if(status == false) {
            onDismiss()
            return
        }

        Version4Dialog(
            onConfirm = {
                onConfirm()
                onDismiss()
            }
        )
    }
}

@HiltViewModel
internal class VersionDialogViewModel @Inject constructor(
    private val loginStatus: LoginStatus
) : ViewModel() {
    val statusFlow = loginStatus.status.flow
}