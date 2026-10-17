package cn.bit101.android.features.versions

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import cn.bit101.android.config.setting.base.AboutSettings
import cn.bit101.android.data.repo.base.VersionRepo
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.getAppVersion
import cn.bit101.android.features.common.helper.withScope
import cn.bit101.android.features.common.helper.withSimpleDataStateFlow
import cn.bit101.api.model.http.app.GetVersionDataModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@Composable
fun UpdateDialog(
    version: GetVersionDataModel.Response,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
) {
    val context = LocalContext.current
    val appVersion = getAppVersion(context)
    AlertDialog(
        onDismissRequest = {
            if(appVersion.versionCode >= version.minVersionCode) {
                onDismiss()
            }
        },
        title = {
            Column {
                Text("海日生残夜")
                Text(
                    text = "当前版本：${appVersion.versionCode}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "最新版本：${version.versionName}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "最低版本：${version.minVersionName}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Text(
                text = version.msg,
                modifier = Modifier.verticalScroll(scrollState)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(version.url.replaceFirst("http://", "https://")))
                    context.startActivity(intent)
                }
            ) {
                Text("前往下载")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (appVersion.versionCode < version.minVersionCode) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(version.url.replaceFirst("http://", "https://")))
                        context.startActivity(intent)
                    } else {
                        onIgnore()
                        onDismiss()
                    }

                }
            ) {
                if (appVersion.versionCode < version.minVersionCode) {
                    Text("强制更新")
                } else {
                    Text("忽略该版本")
                }
            }
        },
    )
}


@Composable
fun UpdateDialog() {
    val vm: UpdateDialogViewModel = hiltViewModel()

    val versionState by vm.getVersionStateFlow.collectAsState()
    val context = LocalContext.current
    val appVersion = getAppVersion(context)

    LaunchedEffect(Unit) {
        if(versionState == null) {
            vm.getVersion()
        }
    }

    val version = (versionState as? SimpleDataState.Success)?.data ?: return
    val ignoreVersion by vm.ignoreVersionFlow.collectAsState(initial = null)
    if(ignoreVersion == null) return

    var show by rememberSaveable { mutableStateOf(false) }
    // 本次会话内用户是否已关掉过弹窗（旋转/重建后不重复弹；下次启动会再提醒）
    var dismissed by rememberSaveable { mutableStateOf(false) }

    // 数据就绪且发现比「已忽略版本」更新的版本时自动弹出
    // （修复点：原先 show 从未被置 true，软更新弹窗永不出现）
    LaunchedEffect(version.versionCode, ignoreVersion) {
        if (!dismissed && version.versionCode > ignoreVersion!!) {
            show = true
        }
    }

    if((show && version.versionCode > ignoreVersion!!) || appVersion.versionCode < version.minVersionCode) {
        UpdateDialog(
            version = version,
            onDismiss = {
                show = false
                dismissed = true
            },
            onIgnore = { vm.ignore(version) }
        )
    }
}

@HiltViewModel
internal class UpdateDialogViewModel @Inject constructor(
    private val aboutSettings: AboutSettings,
    private val versionRepo: VersionRepo
) : ViewModel() {
    private val _getVersionStateFlow = MutableStateFlow<SimpleDataState<GetVersionDataModel.Response>?>(null)
    val getVersionStateFlow = _getVersionStateFlow.asStateFlow()

    val ignoreVersionFlow = aboutSettings.ignoredVersion.flow


    fun getVersion() = withSimpleDataStateFlow(_getVersionStateFlow) {
        versionRepo.getVersionInfo()
    }

    fun ignore(version: GetVersionDataModel.Response) = withScope {
        aboutSettings.ignoredVersion.set(version.versionCode.toLong())
    }
}