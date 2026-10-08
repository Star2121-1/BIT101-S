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
import cn.bit101.android.features.nfc.NfcShortcut
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
     * ## 两条来路
     *
     * 1. **桌面组件 / 通知**：带三个 extra（见下面）。
     * 2. **NFC 贴纸**：不带任何 extra，目标写在 NDEF 记录里（`bit101://nfc/shortcut?to=…`）。
     *
     * ## ⚠️ 为什么贴纸只给 route、不给 tab / focus
     *
     * 贴纸是**用户自己写死**的一句话，里面没有「定位到哪一条 DDL」这种运行时信息 ——
     * 那需要知道 uid，而 uid 在写贴纸的那一刻还不存在。
     * 所以贴纸只能表达「落到某个底栏页」，正好是 [GotoRequest.request] 的单参数形态。
     */
    private fun handleGoto(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_GOTO)

        if (!route.isNullOrBlank()) {
            val tab = intent.getIntExtra(EXTRA_TAB, TAB_NONE)
                .takeIf { ScheduleTabs.isValid(it) }
            val focus = intent.getStringExtra(EXTRA_FOCUS)?.takeIf { it.isNotBlank() }

            GotoRequest.request(route, tab = tab, key = focus)
            return
        }

        // 没有组件 extra ⇒ 试试是不是贴纸唤起的。
        // routeOf 内部会校验白名单，不是我们的贴纸就返回 null，这里便什么都不做。
        val fromTag = NfcShortcut.routeOf(intent)
        if (fromTag != null) GotoRequest.request(fromTag)
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