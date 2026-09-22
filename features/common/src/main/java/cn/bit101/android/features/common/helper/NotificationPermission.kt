package cn.bit101.android.features.common.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 通知权限（`POST_NOTIFICATIONS`）的状态与申请入口。
 *
 * 放在 `features:common` 而不是某个业务模块里 —— 现在至少有三处要用：
 * 座位预约（前台服务常驻通知 + 抢座结果）、提醒中心（上课 / 作业截止）、设置页（权限状态）。
 *
 * 通知被系统隐藏时**功能不受影响**（前台服务照跑、提醒照算），但用户看不到任何反馈，
 * 所以要有地方把「未授权」明确告诉用户并给一键申请的入口。
 */
@Stable
class NotificationPermissionState(
    /** 是否已授权。Android 13 以下无需该权限，恒为 true。 */
    val granted: Boolean,
    private val onRequest: () -> Unit,
) {
    /** 申请权限。Android 13 以下为空操作。 */
    fun request() = onRequest()
}

/** Android 13 (API 33) 起 POST_NOTIFICATIONS 才需要运行时申请，低版本视为已授权。 */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

/** 供 UI 使用：读取当前授权状态并在需要时发起申请。 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    // 用户可能去系统设置里手动开启再回来，每次回到前台重新核对
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasNotificationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(granted) {
        NotificationPermissionState(granted = granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
