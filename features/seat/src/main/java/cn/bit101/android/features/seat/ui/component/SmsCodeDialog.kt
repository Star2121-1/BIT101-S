package cn.bit101.android.features.seat.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.seat.api.SeatSmsChallenge

/**
 * 短信验证码输入框。
 *
 * ⚠️⚠️ **这是全校唯一的验证码入口，挂在 `SeatScreen` 顶层**（不是在 CAS 登录页里）——
 * 学校风控一旦触发，短信是**学校直接发出去的**，而触发它的可能是任何一条路径
 * （自动续期 / 401 重试 / 用户点授权）。之前这个框只存在于「账号密码直登」表单内，
 * 于是从其它路径触发的验证码**没有任何界面能输入**，短信白发了、用户还莫名其妙。
 * 现在只要 [challenge] 非空就立刻弹出来。
 *
 * ⚠️ 刻意不响应「点外部 / 返回键」：登录流程正挂起等这个验证码，误触（例如按返回键收键盘）
 * 会直接取消整次登录。要放弃必须显式点「取消」。
 */
@Composable
fun SmsCodeDialog(
    challenge: SeatSmsChallenge,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(challenge) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("输入短信验证码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(challenge.hint, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(8) },
                    label = { Text("验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = code.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
