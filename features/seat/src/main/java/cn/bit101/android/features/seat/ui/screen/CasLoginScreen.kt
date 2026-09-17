package cn.bit101.android.features.seat.ui.screen

import android.graphics.Bitmap
import cn.bit101.android.features.seat.SeatLog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.seat.SeatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

private const val TAG = "CasLoginScreen"
private const val SEATLIB_BASE = "https://seatlib.bit.edu.cn"
private const val SEATLIB_HOST = "seatlib.bit.edu.cn"

/**
 * CAS 登录过程中会被重定向到的域名，**必须留在 WebView 内加载**。
 *
 * ⚠️ 这里曾经只放行 `seatlib.bit.edu.cn`，其余一律交给外部浏览器 ——
 * 而 phpCAS 的登录链路必然要跳到学校统一身份认证 `login.bit.edu.cn`，
 * 于是导航被拦截、WebView 停在空白页，CAS 会话落进外部浏览器而 App 读不到 cookie，
 * **整条「App 内 WebView 完成 CAS」的链路实际是断的**（2026-09-18 模拟器实测发现）。
 *
 * 现在的判据是：学校自己的域名（`*.bit.edu.cn`）都留在 WebView 内，
 * 只有真正的外链才交给浏览器。
 */
private fun isSchoolHost(host: String?): Boolean =
    host != null && (host == "bit.edu.cn" || host.endsWith(".bit.edu.cn"))

/**
 * 从回调 URL 里取 CAS ticket。
 *
 * phpCAS 的 ticket 形态并不统一（可能是 `?cas=<hex>`，也可能是 `?ticket=ST-...`），
 * 早期用 `Regex("cas=([a-f0-9]{32})")` 硬匹配，形态一变就永远抓不到。
 * 改为按查询参数取，不做格式假设。
 */
private fun extractTicket(url: String): String? {
    val uri = android.net.Uri.parse(url)
    return uri.getQueryParameter("cas")?.takeIf { it.isNotBlank() }
        ?: uri.getQueryParameter("ticket")?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CasLoginScreen(
    mainController: MainController,
    viewModel: SeatViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 页面只加载一次：等布局完成后再 loadUrl（见 update 里的说明）
    var urlLoaded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("seatlib 登录", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            errorMessage?.let { msg ->
                Text("登录失败: $msg", color = Color.Red, modifier = Modifier.padding(16.dp))
            }
            AndroidView(
                // 必须显式占满剩余空间：不给约束时 WebView 首次测量高度为 0，
                // 布局监听里的「宽高非 0 才加载」条件就永远不成立
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    // 避免 SPA 反复触发 onPageFinished 导致重复认证
                    var lastSyncedAt = 0L
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // ⚠️ 曾尝试桌面 UA（期望 SSO 走不同分支），实测未解决空白页，
                        // 且会改变学校服务端选择的资源包（cas-login → cas-login-new），
                        // 属于未验证的行为差异，故保持 WebView 默认 UA。
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                SeatLog.d(TAG, "pageStarted: $url")
                                errorMessage = null
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                SeatLog.d(TAG, "pageFinished: $url")
                                // 回到 seatlib 就尝试收尾：同步 cookie + 静默认证。
                                // 原先这里只打日志（注释却写着 "sync+auth on finish"），
                                // 于是即使 phpCAS 会话已建立，App 也永远不会去取 JWT。
                                val host = url?.let { android.net.Uri.parse(it).host }
                                if (host == SEATLIB_HOST && !viewModel.isLoggedIn.value) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastSyncedAt > 2_000L) {
                                        lastSyncedAt = now
                                        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                                            viewModel.syncAndExchange(ticket = null)
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                val host = request.url.host
                                SeatLog.d(TAG, "shouldOverrideUrlLoading: $url")

                                // 学校域名一律留在 WebView 内，否则 CAS 链路会断（见 isSchoolHost 注释）
                                if (isSchoolHost(host)) {
                                    // phpCAS 回调带上 ticket 时直接换取 JWT
                                    val ticket = extractTicket(url)
                                    if (ticket != null) {
                                        SeatLog.d(TAG, "extracted cas ticket: ${SeatLog.mask(ticket)}")
                                        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                                            viewModel.syncAndExchange(ticket)
                                        }
                                        return true
                                    }
                                    return false
                                }

                                // 真正的外链才交给浏览器
                                SeatLog.d(TAG, "external host=$host, opening in browser")
                                runCatching {
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    ).also { it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        .let { ctx.startActivity(it) }
                                }.onFailure { errorMessage = it.message }
                                return true
                            }
                        }
                        // ⚠️ 必须等 WebView 完成首次布局（宽高非 0）再加载页面。
                        //
                        // 学校 SSO 页面的 <head> 里有一段内联脚本：
                        //   var h = window.innerHeight || documentElement.clientHeight || body.clientHeight
                        // 在 WebView 尚未布局时 innerHeight 为 0（假值），前两个兜底都取不到，
                        // 于是去读 document.body.clientHeight —— 而此时 body 还是 null，
                        // 脚本抛 TypeError 中断，后续定义（含指纹对象）全部缺失，
                        // 登录表单因此不渲染（2026-09-18 模拟器实测）。
                        //
                        // 也不能把 loadUrl 放在 AndroidView 的 update 里：
                        // update 只在重组时执行，WebView 布局完成后若没有新的重组，
                        // 它就再也不会被调用 —— 页面根本不会加载（实测踩中）。
                        // doOnLayout 保证「布局完成的那一刻」恰好加载一次。
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            SeatLog.d(TAG, "layout change: ${v.width}x${v.height}, urlLoaded=$urlLoaded")
                            if (!urlLoaded && v.width > 0 && v.height > 0) {
                                urlLoaded = true
                                SeatLog.d(TAG, "layout ready, loading seatlib")
                                loadUrl("$SEATLIB_BASE/")
                            }
                        }
                    }
                }
            )
        }
    }
}
