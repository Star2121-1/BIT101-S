package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 座位系统的授权门禁 —— 「预约」页与「座位图」页**共用同一份**。
 *
 * 此前两处各写了一份逐字相同的门禁（含按钮与文案），改一处必漏另一处；
 * 而且都**没有进行中反馈**：点「授权座位系统」后链路要走静默续期、必要时还有一次
 * 完整的 CAS 凭据直登（最长 25s），期间界面毫无变化，用户会以为按钮失灵。
 *
 * 现在：
 * - [authorizing] 为真时显示转圈 + 「正在恢复座位系统授权…」，按钮不再可点（防重复触发）；
 * - 未登录学校账号时只给「登录」；已登录但座位未授权时才给「授权座位系统」。
 */
@Composable
fun SeatAuthGate(
    bit101LoggedIn: Boolean,
    authorizing: Boolean,
    authNotice: String?,
    onLogin: () -> Unit,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            authNotice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            when {
                authorizing -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        "正在恢复座位系统授权…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                !bit101LoggedIn -> {
                    Text(
                        "尚未登录学校账号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onLogin) { Text("登录") }
                }
                else -> {
                    Text(
                        "学校账号已登录，但座位系统尚未授权",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onAuthorize) { Text("授权座位系统") }
                }
            }
        }
    }
}
