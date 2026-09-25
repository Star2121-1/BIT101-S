package cn.bit101.android.features.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.theme.LocalThemeIsDark
import com.google.accompanist.web.AccompanistWebChromeClient
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberSaveableWebViewState
import com.google.accompanist.web.rememberWebViewNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Composable
internal fun WebContent(
    mainController: MainController,
    url: String? = null,
) {
    val vm: WebViewModel = hiltViewModel()

    val context = LocalContext.current
    val state = rememberSaveableWebViewState()
    val navigator = rememberWebViewNavigator()

    var progress by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(navigator) {
        val bundle = state.viewState
        if (bundle == null) {
            if(url != null) {
                navigator.loadUrl(url)
            } else {
                navigator.loadUrl(vm.BASE_URL)
            }
        }
    }

    var fileChooserValueCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooseResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            fileChooserValueCallback?.onReceiveValue(arrayOf(Uri.parse(it.data?.dataString)))
        } else {
            fileChooserValueCallback?.onReceiveValue(null)
        }
    }
    val themeIsDark = LocalThemeIsDark.current
    Log.i("WebScreen", "themeIsDark: $themeIsDark")

    WebView(
        state = state,
        navigator = navigator,
        modifier = Modifier.fillMaxSize(),
        captureBackPresses = url == null,
        onCreated = {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true //localStorage
        },
        client = object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                Log.i("WebScreen", "onPageStarted: $url")

                scope.launch {
                    // 切换主题模式
                    val themeMode=if(themeIsDark) "dark" else "light"
                    view.evaluateJavascript(
                        """
                        if (!window.localStorage.getItem("store")) {
                            window.localStorage.setItem("store", '{"theme_mode":"$themeMode"}')
                        } else {
                            store_tmp = JSON.parse(window.localStorage.store);
                            store_tmp.theme_mode = "$themeMode";
                            window.localStorage.setItem("store", JSON.stringify(store_tmp));
                        }
                    """.trimIndent(), null
                    )
                    // 注入fake-cookie
                    val fakeCookie = vm.fakeCookie.get()
                    Log.d("WebScreen", fakeCookie)
                    if (fakeCookie.isNotEmpty()) {
                        view.evaluateJavascript(
                            """
                            if (!window.localStorage.getItem("store")) {
                                window.localStorage.setItem("store", '{"fake_cookie":"$fakeCookie"}')
                            } else {
                                store_tmp = JSON.parse(window.localStorage.store);
                                store_tmp.fake_cookie = "$fakeCookie";
                                window.localStorage.setItem("store", JSON.stringify(store_tmp));
                            }
                        """.trimIndent(), null
                        )
                    }
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 外部链接交给系统浏览器；**学校域名（*.bit.edu.cn）必须留在 WebView 内**
                // —— 统一身份认证（CAS）登录只有留在 App 内，会话 cookie 才会落进
                // App 的 CookieManager；丢给外部浏览器等于白登（2026-09-25 实测：
                // 一卡通登录被踢去 nubia 浏览器，App 里始终显示未登录）
                if (!isInternalUrl(request?.url?.toString())) {
                    // 设备上没有能接的浏览器也不能崩
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, request?.url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)

                val sid = runBlocking {
                    vm.sid.get()
                }

                val password = runBlocking {
                    vm.password.get()
                }

                // 自动填充成绩查询学号密码
                if ((url == "${vm.BASE_URL}/score/" || url == "${vm.BASE_URL}/score") && sid.isNotEmpty() && password.isNotEmpty()) {
                    val script = """
                    document.getElementById("sid").value = "$sid";
                    document.getElementById("sid").dispatchEvent(new Event('input'));
                    document.getElementById("password").value = "$password";
                    document.getElementById("password").dispatchEvent(new Event('input'));
                """.trimIndent()
                    view.evaluateJavascript(script, null)

                }
            }
        },
        chromeClient = object : AccompanistWebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progress = newProgress / 100f
            }

            // 显示文件选择器
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserValueCallback = filePathCallback
                try {
                    fileChooseResultLauncher.launch(fileChooserParams?.createIntent())
                } catch (e: ActivityNotFoundException) {
                    mainController.snackbar("未找到可以打开的应用！")
                }
                return true
            }
        },
    )

    //进度条
    AnimatedVisibility(visible = (progress > 0f && progress < 1f)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
fun WebScreen(
    mainController: MainController,
    url: String? = null,
) {
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val statusBarHeight = systemBarsPadding.calculateTopPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarHeight),
            color = Color(0xFFFF9A57),
        ) {}
        WebContent(mainController, url)
    }

}

/** 是否应留在 App 内 WebView：BIT101 自己的站 + 学校域名（含各子系统）。 */
private fun isInternalUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    // 域名大小写不敏感；含非法字符的 URL 会让 URI 抛异常 —— 按外部链接处理
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "bit101.cn" || host.endsWith(".bit101.cn") ||
        host == "bit.edu.cn" || host.endsWith(".bit.edu.cn")
}
