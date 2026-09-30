package cn.bit101.android.features.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.nfc.logic.CardKind
import cn.bit101.android.features.nfc.logic.NdefShortcutLogic

/**
 * 设置里的「NFC」页。
 *
 * ## 三个区块为什么永远都在
 *
 * 本项目有一条反复用真金白银换来的纪律：**新区块不要「有数据才渲染」**。
 * 之前违约卡、列表空态、研讨间三次栽在同一个地方 —— 没数据时它就是一个空白区，
 * 用户根本不知道有这个功能存在。所以这里即便是「本设备不支持 NFC」的机器，
 * 三个区块照样显示，只是把**为什么用不了**说清楚。
 *
 * ## 入口来自何处
 *
 * 由 `features:setting` 的 `SettingScreen` 以 `"nfc"` 路由挂进来。
 * ⚠️ 本模块**不得**反向依赖 `features:setting`（否则与 setting→nfc 成环），
 * 所以页 Self 自带标题/卡片样式，不去复用 setting 的 `SettingsColumn` 那些组件。
 */
@Composable
fun NfcSettingPage(
    mainController: MainController,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val activity = remember(context) { context.findActivity() }
    val capability = remember(context) { NfcCapability.check(context) }

    var busy by remember { mutableStateOf<NfcController.Busy>(NfcController.Busy.Idle) }
    var scan by remember { mutableStateOf<NfcScan?>(null) }
    var route by remember { mutableStateOf<String?>(null) }
    var deepProbe by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<NfcController?>(null) }

    DisposableEffect(activity, capability) {
        if (activity == null || capability == NfcCapability.Unsupported) {
            onDispose { }
        } else {
            val created = NfcController(
                activity = activity,
                onResult = { scan = it },
                onStateChange = { busy = it },
            )
            controller = created
            created.start()
            onDispose {
                controller = null
                created.shutdown()
            }
        }
    }

    // 开关一变就同步给控制器；控制器在别的线程上读它。
    DisposableEffect(controller, deepProbe) {
        controller?.withSfiScan = deepProbe
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CapabilityCard(capability = capability, context = context)

        if (capability != NfcCapability.Unsupported) {
            ReadCardSection(
                capability = capability,
                busy = busy,
                scan = scan,
                deepProbe = deepProbe,
                onDeepProbeChange = { deepProbe = it },
                onCopy = { mainController.copyText(clipboard, it) },
            )
        }

        ShortcutSection(
            capability = capability,
            route = route,
            onRouteChange = { route = it },
            busy = busy,
            scan = scan,
            onWrite = { target ->
                val payload = NdefShortcutLogic.encode(target) ?: return@ShortcutSection
                controller?.pendingWrite = payload
                busy = NfcController.Busy.Writing
            },
        )
    }
}

// ---------------------------------------------------------------- 能力自检

@Composable
private fun CapabilityCard(
    capability: NfcCapability,
    context: Context,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (capability == NfcCapability.Enabled)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(capability.title(), style = MaterialTheme.typography.titleMedium)
            Text(capability.hint(), style = MaterialTheme.typography.bodySmall)

            if (capability == NfcCapability.Disabled) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                }) {
                    Text("去系统设置打开 NFC")
                }
            }
        }
    }
}

// ------------------------------------------------------------ 校园卡读取

@Composable
private fun ReadCardSection(
    capability: NfcCapability,
    busy: NfcController.Busy,
    scan: NfcScan?,
    deepProbe: Boolean,
    onDeepProbeChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("校园卡读取（诊断）")

        if (busy == NfcController.Busy.Reading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text("正在读卡…")
            }
        } else if (capability == NfcCapability.Disabled) {
            Text("先把 NFC 打开才能读卡。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "把校园卡贴在手机背面。读到什么就列什么，不加我们自己的推断。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = deepProbe, onCheckedChange = onDeepProbeChange, enabled = capability != NfcCapability.Disabled)
            Column(Modifier.weight(1f)) {
                Text("顺便扫一遍文件区（更慢）")
                Text(
                    "CPU 卡才有用：挨个短文件标识试 READ BINARY，把命中的罗出来。全部只读。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val s = scan
        if (s == null) {
            Text("还没有读到卡。", style = MaterialTheme.typography.bodySmall)
        } else {
            ReadResultCard(scan = s, onCopy = onCopy)
        }
    }
}

@Composable
private fun ReadResultCard(
    scan: NfcScan,
    onCopy: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scan.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            scan.writeOutcome?.let { Text(it) }

            KeyValue("UID", scan.uidHex)
            KeyValue("技术列表", scan.techShortNames.joinToString(" / "))
            scan.kind?.let { KeyValue("卡类型", kindLabel(it)) }

            scan.shortcut?.let { text ->
                HorizontalDivider()
                Text("这张标签里写着一条快捷入口：", style = MaterialTheme.typography.bodySmall)
                MonoText(text)
                val target = cn.bit101.android.features.nfc.logic.NdefShortcutLogic.decode(text)
                Text(
                    if (target != null) "认得出来，目标是 ${labelOf(target)}"
                    else "认不出来：不是我们的格式，或目标已不存在",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (scan.cardNoCandidates.isNotEmpty()) {
                HorizontalDivider()
                Text("卡面号候选 —— 请和你卡上印的那串数字对一下：", style = MaterialTheme.typography.bodySmall)
                scan.cardNoCandidates.forEach { KeyValue(it.label, it.value) }
            }

            scan.probe?.let { lines ->
                HorizontalDivider()
                Text("只读探测结果：", style = MaterialTheme.typography.bodySmall)
                lines.forEach { line ->
                    Text(line.label, style = MaterialTheme.typography.bodyMedium)
                    MonoText("APDU ${line.apdu}")
                    Text(
                        "→ ${line.sw.ifEmpty { "（没发出去）" }} ${line.swText ?: "未知状态字"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!line.data.isNullOrBlank()) MonoText(line.data)
                }
            }

            TextButton(onClick = { onCopy(dumpOf(scan)) }) { Text("复制诊断文本") }
        }
    }
}

// ------------------------------------------------------------ 贴纸快捷入口

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutSection(
    capability: NfcCapability,
    route: String?,
    onRouteChange: (String) -> Unit,
    busy: NfcController.Busy,
    scan: NfcScan?,
    onWrite: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("NFC 贴纸快捷入口")

        Text(
            "买张 NFC 贴纸，写一条入口进去，贴在你想要的地方：书桌、实验室门边、图书馆某面墙。" +
                "之后手机一贴就跳到那一页。这项不碰校园卡，也不需要学校配合。",
            style = MaterialTheme.typography.bodySmall,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NdefShortcutLogic.targets().forEach { target ->
                FilterChip(
                    selected = route == target.route,
                    onClick = { onRouteChange(target.route) },
                    label = { Text(target.label) },
                )
            }
        }

        Button(
            enabled = route != null && capability != NfcCapability.Unsupported,
            onClick = { route?.let(onWrite) },
        ) {
            Text("写入标签")
        }

        when (busy) {
            NfcController.Busy.Writing -> Text("请把贴纸贴在手机背面…")
            NfcController.Busy.Reading -> Unit
            NfcController.Busy.Idle -> scan?.writeOutcome?.let { Text(it) }
        }

        Text(
            "⚠️ 写入会覆盖标签上原有的内容，且不可撤销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ---------------------------------------------------------------- 小零件

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        MonoText(value)
    }
}

@Composable
private fun MonoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

private fun kindLabel(kind: CardKind): String = when (kind) {
    CardKind.MIFARE_CLASSIC -> "MIFARE Classic（读扇区要密钥）"
    CardKind.CPU_CARD -> "CPU 卡（能发 APDU）"
    CardKind.NFC_FORUM_TAG -> "NFC 贴纸 / Type 4 标签"
    CardKind.NFC_V -> "ISO 15693 标签"
    CardKind.UNKNOWN -> "认不出来"
}

private fun labelOf(route: String): String =
    NdefShortcutLogic.targets().firstOrNull { it.route == route }?.let { "${it.label}（${it.route}）" } ?: route

/** 导出一段可以直接发给开发者的诊断文本：所有已知信息，不掺推断。 */
private fun dumpOf(scan: NfcScan): String = buildString {
    appendLine("UID: ${scan.uidHex}")
    appendLine("技术: ${scan.techShortNames.joinToString(", ")}")
    appendLine("卡类型: ${scan.kind?.let(::kindLabel) ?: "未判定"}")
    scan.shortcut?.let { appendLine("标签载荷: $it") }
    if (scan.cardNoCandidates.isNotEmpty()) {
        appendLine("卡面号候选:")
        scan.cardNoCandidates.forEach { appendLine("  ${it.label} = ${it.value}") }
    }
    scan.probe?.let { lines ->
        appendLine("只读探测:")
        lines.forEach { line ->
            appendLine("  [${line.label}] ${line.apdu} -> ${line.sw} ${line.swText ?: "未知状态字"}")
            line.data?.takeIf { it.isNotBlank() }?.let { appendLine("      data: $it") }
        }
    }
    scan.error?.let { appendLine("错误: $it") }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
