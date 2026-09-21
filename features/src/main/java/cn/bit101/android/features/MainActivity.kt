package cn.bit101.android.features

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.bit101.android.config.setting.base.ThemeSettings
import cn.bit101.android.features.theme.BIT101Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeSettings: ThemeSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 桌面组件的「立即预约」会带 extra 进来，交给 GotoRequest 让 IndexScreen 跳转
        GotoRequest.request(intent?.getStringExtra(EXTRA_GOTO))

        var loading = true

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loading = false
            }
        }

        splashScreen.setKeepOnScreenCondition { loading }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            BIT101Theme {
                MainApp()
            }
        }

        // 设置屏幕旋转
        MainScope().launch {
            themeSettings.autoRotate.flow.collect {
                requestedOrientation = if (it) {
                    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
    }

    /**
     * Activity 已存在时（用户点组件的「立即预约」而 App 还在后台）走这里。
     *
     * 必须自己处理：`onCreate` 不会再跑，`GotoRequest` 就不会被写入，
     * 表现成「点了按钮只把 App 唤到前台，却没跳到座位页」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GotoRequest.request(intent.getStringExtra(EXTRA_GOTO))
    }

    companion object {
        /**
         * 「打开 App 后跳到哪一页」的 extra key。
         *
         * 值用 `PageShowOnNav.toPageData().value`（如 `"seat"`），
         * 组件侧写、这里读，两边都别硬编码字符串常量。
         */
        const val EXTRA_GOTO = "bit101_goto"
    }
}