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
import androidx.compose.material3.OutlinedTextField
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
import cn.bit101.android.features.nfc.logic.CardIdentityLogic
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
    // 深度扫描默认开：真卡实测它是**唯一**挖出卡内文件的那一轮
    // （SELECT 0010~001B 命中 8 个文件，其中 0016 里就是姓名）。
    // 关掉它，贴一次卡就只能看到「这个文件不存在」。
    var deepProbe by remember { mutableStateOf(true) }
    var classicScan by remember { mutableStateOf(true) }
    var studentId by remember { mutableStateOf("") }
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

    // 学号一变就同步给控制器；控制器在别的线程上读它。
    DisposableEffect(controller, studentId) {
        controller?.studentId = studentId.trim().takeIf { it.isNotEmpty() }
        onDispose { }
    }

    // 开关一变就同步给控制器；控制器在别的线程上读它。
    DisposableEffect(controller, deepProbe) {
        controller?.withSfiScan = deepProbe
        onDispose { }
    }

    DisposableEffect(controller, classicScan) {
        controller?.withClassicScan = classicScan
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

        // ⚠️ 这里**不做** `if (capability != Unsupported)`：哪怕本机不支持，读卡区块也照渲染。
        // 「有数据才渲染」这条纪律是这个项目踩过三次换来的 —— 把区块藏起来的话，
        // 用户根本不知道有这个功能，换到有 NFC 的机器上也想不起来回来看。
        ReadCardSection(
            capability = capability,
            busy = busy,
            scan = scan,
            deepProbe = deepProbe,
            classicScan = classicScan,
            studentId = studentId,
            onStudentIdChange = { studentId = it },
            onDeepProbeChange = { deepProbe = it },
            onClassicScanChange = { classicScan = it },
            onCopy = { mainController.copyText(clipboard, it) },
        )

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
    classicScan: Boolean,
    studentId: String,
    onStudentIdChange: (String) -> Unit,
    onDeepProbeChange: (Boolean) -> Unit,
    onClassicScanChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("校园卡读取（诊断）")

        val prompt = when {
            busy == NfcController.Busy.Reading -> null
            capability == NfcCapability.Unsupported ->
                "这台机器读不了卡（见上面那张卡）。换一台带 NFC 的安卓机，这个区块就活了。"
            capability == NfcCapability.Disabled -> "先把 NFC 打开才能读卡。"
            else -> "把校园卡贴在手机背面。读到什么就列什么，不加我们自己的推断。"
        }
        if (busy == NfcController.Busy.Reading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text("正在读卡…")
            }
        } else {
            prompt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        OutlinedTextField(
            value = studentId,
            onValueChange = onStudentIdChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("学号（可选）") },
            placeholder = { Text("填了才会在探测结果里找学号") },
        )
        Text(
            "填了学号之后，每一条探测返回都会拿它去比，四种形式都试：" +
                "BCD（两位十进制压一个字节）、ASCII、反序 BCD，" +
                "以及**分段形式** —— 北理工校园卡上的学号被两个控制字节（0x06 / 0x04）" +
                "分成三段存放，只有按分段拼才认得出来。命中就标出来。" +
                "学号只在手机上比对，不发到任何地方。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = classicScan,
                onCheckedChange = onClassicScanChange,
                enabled = capability == NfcCapability.Enabled,
            )
            Column(Modifier.weight(1f)) {
                Text("试常见默认密钥读 Classic 扇区")
                Text(
                    "双界面卡才有用：拿出厂默认那批密钥去认证扇区，过了就只读块、不写任何东西。" +
                        "学号经常明文躺在这些块里 —— 这是眼下最有可能挖出来的一条路。" +
                        "认证失败就停，不会去破密钥。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = deepProbe, onCheckedChange = onDeepProbeChange, enabled = capability == NfcCapability.Enabled)
            Column(Modifier.weight(1f)) {
                Text("扫文件区（慢，要贴住几秒；卡里的数据全是这一轮挖出来的）")
                Text(
                    "CPU 卡才有用：先挨个短标识（SFI 1~30）读，再挨个文件标识试 SELECT。" +
                        "命中就沿文件往下读三段。卡说「你 Le 写错了」（6Cxx，如 6C1E = 只要 30 字节）" +
                        "会按它说的重发；文件不吃 READ BINARY（6981，记录文件）会改读记录；" +
                        "选中的是目录（6986）会进去再扫一层。共 400 条左右只读命令，" +
                        "中途掉卡也保留已经拿到的部分。",
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
    // 深度扫描会有三四百条记录，默认只列有数据的。状态提在这里而不是 `let` 里面，
    // 免得 `probe` 一会儿有一会儿没有时把 remember 的调用顺序打乱。
    var showAllProbe by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 复制按钮放**最上面**：一次深度扫描的结果有几百行，放末尾等于要人先滑到底
            // 再往回找 —— 而「复制诊断文本」正是这个页面上最常用的那一个动作。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onCopy(dumpOf(scan)) }) { Text("复制诊断文本") }
            }

            // 「这张卡是谁」放在结果最上面：学号和姓名原本各占几百行流水里的一行，
            // 不提出来等于让人自己在「找不到这个文件」堆里翻找。
            IdentityCard(scan)

            scan.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            scan.writeOutcome?.let { Text(it) }

            KeyValue("UID", scan.uidHex)
            KeyValue("技术列表", scan.techShortNames.joinToString(" / "))
            scan.kind?.let { KeyValue("卡类型", kindLabel(it)) }
            if (scan.classicCompat) {
                Text(
                    "双界面卡：同时提供 ISO 14443-4 通道与 MIFARE Classic 兼容层。" +
                        "正因为有前一条，下面的 APDU 探测才跑得起来；也因为有后一条，这张卡**不能写入**。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

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

            scan.classic?.let { sectors ->
                HorizontalDivider()
                Text(
                    "Classic 扇区（默认密钥 + 只读块）：",
                    style = MaterialTheme.typography.bodySmall,
                )
                sectors.forEach { sector ->
                    Text(
                        "扇区 ${sector.sector} · ${sector.key ?: "认证失败"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    sector.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                    val nonBlank = sector.blocks.filter { !it.blank }
                    val blanks = sector.blocks.filter { it.blank }
                    nonBlank.forEach { block ->
                        MonoText("  块 ${block.block} ${block.hex}")
                        block.bcdDigits?.let { MonoText("    BCD 数字 $it") }
                        block.ascii?.let { MonoText("    ASCII \"$it\"") }
                        block.studentIdHit?.let { hit ->
                            Text(
                                "    ★ 在这里找到了学号（$hit）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (blanks.isNotEmpty()) {
                        Text(
                            "  空块 ${blanks.size} 个：${blanks.joinToString(",") { it.block.toString() }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            scan.probe?.let { lines ->
                HorizontalDivider()
                val hits = lines.filter { it.hasData }
                Text(
                    "只读探测：共 ${lines.size} 条命令，其中 ${hits.size} 条拿到了数据。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hits.size < lines.size) {
                    TextButton(onClick = { showAllProbe = !showAllProbe }) {
                        Text(if (showAllProbe) "只看有数据的 ${hits.size} 条" else "展开全部 ${lines.size} 条")
                    }
                }
                (if (showAllProbe) lines else hits).forEach { line ->
                    Text(line.label, style = MaterialTheme.typography.bodyMedium)
                    MonoText("APDU ${line.apdu}")
                    Text(
                        "→ ${line.sw.ifEmpty { "（没发出去）" }} ${line.swText ?: "未知状态字"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    line.note?.let {
                        Text("（$it）", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!line.data.isNullOrBlank()) MonoText(line.data)
                    line.tlvs.forEach { MonoText("  $it") }
                    line.text?.let { text ->
                        Text(
                            "文本：$text",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    line.studentIdHit?.let { hit ->
                        Text(
                            "★ 在这里找到了学号（$hit）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 疑似 BCD 日期：卡里存时间就是这样的裸字节，不点出来没人认得。
                    // ⚠️ 措辞带「疑似」—— 三字节凑一个合法时刻太容易，这是线索不是结论。
                    line.dates.forEach { d ->
                        Text(
                            "疑似日期 $d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
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

/**
 * 「这张卡是谁」的摘要卡。
 *
 * ## ⚠️ 为什么缺哪一行就省哪一行、但卡片本身照常渲染
 *
 * 换个学校的卡、或者这张卡某个文件被清空，用户看到的应该是
 * 「这张卡只认出了卡号」，而不是整块空白 —— 后者会被当成「什么都没读到」，
 * 然后得出「这个功能不行」的结论。判定「有数据才渲染」在这儿会吞掉整段。
 *
 * ## ⚠️ 日期一律写「卡上日期」，不写「有效期」
 *
 * 真卡上同时有 `2028-08-30`（像有效期）与 `2026-03-22 11:08:43`（交易时间），
 * 代码没有依据判断哪个是哪个 —— 挑最晚的那个只是猜测。
 * 把猜测写成确定语气是最难被发现的一类错误，所以这里只列、不命名。
 */
@Composable
private fun IdentityCard(scan: NfcScan) {
    val identity = remember(scan.probe, scan.cardNoCandidates) {
        CardIdentityLogic.summarize(
            probeLines = scan.probe,
            candidates = scan.cardNoCandidates,
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "这张卡",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            identity.name?.let {
                KeyValue("姓名", it)
            }
            identity.studentId?.let { sid ->
                KeyValue("学号", sid)
                identity.studentIdNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            identity.dates.forEach { date ->
                KeyValue("卡上日期（疑似）", date)
            }
            identity.cardNo?.let {
                KeyValue("卡号", it)
                identity.cardNoNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 三项全空时给一句实话，别让卡片看起来像渲染坏了
            if (identity.name == null && identity.studentId == null && identity.cardNo == null) {
                Text(
                    "这张卡里没认出姓名 / 学号 / 卡号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
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
    if (scan.classicCompat) appendLine("双界面: 同时有 MIFARE Classic 兼容层（禁止写入）")
    scan.shortcut?.let { appendLine("标签载荷: $it") }
    if (scan.cardNoCandidates.isNotEmpty()) {
        appendLine("卡面号候选:")
        scan.cardNoCandidates.forEach { appendLine("  ${it.label} = ${it.value}") }
    }
    scan.classic?.let { sectors ->
        appendLine("Classic 扇区（默认密钥 + 只读块）:")
        sectors.forEach { sector ->
            appendLine("  扇区 ${sector.sector} · ${sector.key ?: "认证失败"}")
            sector.note?.let { appendLine("      $it") }
            sector.blocks.forEach { block ->
                if (block.blank) {
                    appendLine("      块 ${block.block}: （全零）")
                } else {
                    appendLine("      块 ${block.block}: ${block.hex}")
                    block.bcdDigits?.let { appendLine("          BCD 数字: $it") }
                    block.ascii?.let { appendLine("          ASCII: $it") }
                    block.studentIdHit?.let { appendLine("          ★ 命中学号：$it") }
                }
            }
        }
    }
    scan.probe?.let { lines ->
        appendLine("只读探测:")
        lines.forEach { line ->
            appendLine("  [${line.label}] ${line.apdu} -> ${line.sw} ${line.swText ?: "未知状态字"}")
            line.note?.let { appendLine("      过程: $it") }
            line.data?.takeIf { it.isNotBlank() }?.let { appendLine("      data: $it") }
            line.tlvs.forEach { appendLine("      tlv: $it") }
            line.text?.let { appendLine("      文本: $it") }
            line.studentIdHit?.let { appendLine("      ★ 命中学号：$it") }
            line.dates.forEach { appendLine("      疑似日期: $it") }
        }
    }
    scan.error?.let { appendLine("错误: $it") }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
