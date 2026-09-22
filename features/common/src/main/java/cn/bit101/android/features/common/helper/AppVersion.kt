package cn.bit101.android.features.common.helper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * 本机安装的版本信息。
 *
 * ⚠️ 字段原名 `versionNumber`，与服务端返回的 `versionCode` / `minVersionCode`
 * 混在一起看极易判断错强制更新 —— 已统一叫 [versionCode]（就是 build.gradle 里的 versionCode）。
 */
data class AppVersion(
    /** 版本名，如 `1.6.9`。 */
    val versionName: String,
    /** 版本号（`versionCode`），与服务端 `version.versionCode` 同量纲。 */
    val versionCode: Long,
)


fun getAppVersion(context: Context): AppVersion {
    val packageManager = context.packageManager
    val packageName = context.packageName
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
    return AppVersion(
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
    )
}
