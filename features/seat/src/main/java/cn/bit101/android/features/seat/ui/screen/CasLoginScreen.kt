package cn.bit101.android.features.seat.ui.screen

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CasLoginScreen(
    mainController: MainController,
    viewModel: SeatViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Log.d(TAG, "pageStarted: $url")
                                errorMessage = null
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "pageFinished: $url")
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                Log.d(TAG, "shouldOverrideUrlLoading: $url")

                                // Extract cas=TICKET from URL (phpCAS callback or session URL)
                                val ticketRegex = Regex("""cas=([a-f0-9]{32})""")
                                val ticket = ticketRegex.find(url)?.groupValues?.getOrNull(1)

                                if (ticket != null) {
                                    Log.d(TAG, "extracted cas ticket: ${ticket.take(8)}...")
                                    // Single entry point: sync cookies + exchange ticket
                                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                                        viewModel.syncAndExchange(ticket)
                                    }
                                    return true
                                }

                                // All other seatlib URLs load normally, then sync+auth on finish
                                if (url.startsWith(SEATLIB_BASE)) {
                                    return false
                                }

                                // External URLs open in browser
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .also { it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    .let { ctx.startActivity(it) }
                                return true
                            }
                        }
                    }
                },
                update = { view ->
                    view.loadUrl("$SEATLIB_BASE/")
                }
            )
        }
    }
}
