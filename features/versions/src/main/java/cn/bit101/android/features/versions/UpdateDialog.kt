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
    // （修复点：原先 show 从未被置 true，软更新弹窗永不出现；
    //  真正要不要弹由下方守卫判定 —— 含「服务端号 > 装机号」与强更优先，
    //  真值表见 UpdateLogicTest）
    LaunchedEffect(version.versionCode, ignoreVersion) {
        if (!dismissed && version.versionCode > ignoreVersion!!) {
            show = true
        }
    }

    // 提示判定（缺陷背景：原判据只有「服务端号 > 已忽略值」，从未与装机号比较，
    // 服务端数据陈旧时会对从未忽略的用户弹「降级提示」；见 UpdateLogic 的说明）：
    // - 强更（装机号 < minVersionCode）优先，忽略值与服务端号都不挡；
    // - 常规提示要求「服务端号 > 装机号」且「服务端号 > 已忽略值」，
    //   并且用户本次会话还没关过（show/dismissed 控制会话内不再打扰）。
    val forceUpdate = UpdateLogic.isForceUpdate(
        installedVersionCode = appVersion.versionCode,
        minVersionCode = version.minVersionCode.toLong(),
    )
    val regularUpdate = UpdateLogic.hasRegularUpdate(
        serverVersionCode = version.versionCode.toLong(),
        installedVersionCode = appVersion.versionCode,
        ignoredVersionCode = ignoreVersion!!,
    )
    if (forceUpdate || (show && regularUpdate)) {
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