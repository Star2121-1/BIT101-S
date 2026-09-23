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
import cn.bit101.android.config.setting.base.ScheduleTabs
import cn.bit101.android.config.setting.base.ThemeSettings
import cn.bit101.android.features.common.GotoRequest
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

        // 桌面组件的「立即预约 / 点条目」会带 extra 进来，交给 GotoRequest 让界面跳转
        handleGoto(intent)

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
        handleGoto(intent)
    }

    /**
     * 把 Intent 里的跳转请求交给 [GotoRequest]。
     *
     * 三个 extra 都可以单独出现：
     * - `EXTRA_GOTO`：底栏页（必给的）
     * - `EXTRA_TAB`：课表页里停在第几个 tab（组件点 DDL / 动态条目时给）
     * - `EXTRA_FOCUS`：定位到哪一条（DDL uid / 动态 id）
     */
    private fun handleGoto(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_GOTO) ?: return
        val tab = intent.getIntExtra(EXTRA_TAB, TAB_NONE)
            .takeIf { ScheduleTabs.isValid(it) }
        val focus = intent.getStringExtra(EXTRA_FOCUS)?.takeIf { it.isNotBlank() }

        GotoRequest.request(route, tab = tab, key = focus)
    }

    companion object {
        /**
         * 「打开 App 后跳到哪一页」的 extra key。
         *
         * 值用 `PageShowOnNav.toPageData().value`（如 `"seat"`），
         * 组件侧写、这里读，两边都别硬编码字符串常量。
         */
        const val EXTRA_GOTO = "bit101_goto"

        /**
         * 「停在第几个 tab」的 extra key（见 `ScheduleTabs`）。
         *
         * ⚠️ 与 `WidgetViews.TAB_EXTRA` 保持一致（跨模块，注释互相指向）。
         */
        const val EXTRA_TAB = "bit101_tab"

        /**
         * 「定位到哪一条」的 extra key：DDL 用 uid、动态用 `eclass:{id}`。
         *
         * ⚠️ 与 `WidgetViews.FOCUS_EXTRA` 保持一致。
         */
        const val EXTRA_FOCUS = "bit101_focus"

        /** [EXTRA_TAB] 缺省值：不指定 tab（停在课表页）。 */
        private const val TAB_NONE = -1
    }
}