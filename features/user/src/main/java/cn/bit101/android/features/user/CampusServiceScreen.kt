package cn.bit101.android.features.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.data.school.CampusCardBalanceLogic
import cn.bit101.android.data.school.CampusCardLogic
import cn.bit101.android.data.school.CampusNetResult
import cn.bit101.android.data.school.CampusNetLogic
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.features.common.MainController

/** 一卡通首页（CAS service 指回这里；登录在 App 内 WebView 完成）。 */
private const val CAMPUS_CARD_LOGIN_URL = "https://dkykt.info.bit.edu.cn/home/openHomePageByCas"

/** 校园网认证门户（深澜 Srun）。点「去认证」在 App 内 WebView 打开。 */
private const val CAMPUS_NET_PORTAL_URL = "http://10.0.0.55/"

/**
 * 「校园服务」详情页 —— 一卡通（余额摘要；流水卡在卡务系统，Web 端不可得）+
 * 校园网（深澜自助：本次上线 / 本月已用流量 / 本月在线时长 / 本次收发 / IP / 余额）。
 *
 * ⚠️ 校园网只有校园网环境可取（`10.0.0.55` 是内网地址）——取不到时提示需连接校园网。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusServiceScreen(
    mainController: MainController,
) {
    val vm: CampusServiceViewModel = hiltViewModel()

    val snapshot by vm.snapshot.collectAsState()
    val netResult by vm.netResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val balanceTrend by vm.balanceTrend.collectAsState()

    // 从「登录一卡通」的 WebView 返回时会重新进入组合，但 VM 保留、init 不会重跑，
    // 所以这里补一次刷新；首次进入时 init 已发起请求，被 VM 里的 _loading 守卫挡掉。
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "校园服务") },
                navigationIcon = {
                    IconButton(onClick = { mainController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 一卡通
            SectionCard(title = "一卡通") {
                val entries = snapshot?.entries.orEmpty()
                if (snapshot?.loggedIn == true && entries.isNotEmpty()) {
                    // 服务端可能下发多条（余额 / 过渡余额 / 芯片余额…），全都列出来 ——
                    // 只显示第一条会把「过渡余额」这类对得上账的信息漏掉。
                    // ⚠️ 解析出来的是**裸数字**（如 `34.05`），金额前缀得自己补：
                    // 少了 ¥ 这一行看着和「学号」「IP」是一类字段
                    entries.forEach { (label, value) -> InfoRow(label = label, value = "¥$value") }
                } else {
                    InfoRow(
                        label = "账户余额",
                        value = CampusCardLogic.balanceText(
                            snapshot, loading, fetched,
                            notLoggedIn = "未登录，点下方「登录一卡通」",
                        ),
                    )
                }
                // 余额变化：**本地按次记录的余额差**（`CampusCardBalanceLogic`）。
                // 一卡通流水拿不到（钉钉客户端专属），这是替代方案 —— 不精确，但能看出花得快不快。
                // 只在已登录时显示：未登录时页面上那几个数字来自登录页，算趋势没有意义
                if (snapshot?.loggedIn == true) {
                    InfoRow(
                        label = "余额变化",
                        value = CampusCardBalanceLogic.trendRowText(balanceTrend),
                    )
                }
                InfoRow(
                    label = "数据来源",
                    value = "延河一卡通（dkykt.info.bit.edu.cn）",
                    small = true,
                )

                // 未登录时给入口：CAS 登录只能在 WebView 里完成（App 内打开，不跳外部浏览器）。
                // ⚠️ 请求失败时**不给**这个按钮 —— 问题不在登录，重试即可（文案已说明）
                if (snapshot?.loggedIn != true && snapshot?.failed != true) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { mainController.openWebPage(CAMPUS_CARD_LOGIN_URL) },
                    ) {
                        Text(text = "登录一卡通")
                    }
                }
            }

            // 校园网
            SectionCard(title = "校园网（Srun 自助）") {
                when (val r = netResult) {
                    null -> InfoRow(label = "状态", value = "获取中…")

                    is CampusNetResult.Online -> {
                        val info = r.info
                        InfoRow(label = "账号", value = info.userName)
                        InfoRow(label = "本月已用流量", value = CampusNetLogic.trafficStatusText(info.bytesTotal))
                        InfoRow(label = "本月在线时长", value = CampusNetLogic.formatDuration(info.durationSeconds))
                        InfoRow(label = "本次上线", value = CampusNetLogic.formatTime(info.loginEpochSeconds))
                        InfoRow(
                            label = "本次收发",
                            value = "↓" + CampusNetLogic.formatTraffic(info.bytesIn) +
                                "  ↑" + CampusNetLogic.formatTraffic(info.bytesOut),
                        )
                        InfoRow(label = "本机 IP", value = info.ip)
                        InfoRow(label = "账户余额", value = "¥%.2f".format(info.balanceYuan))
                    }

                    // 接口通了但本机没有在线会话 —— 这是**能直接处置**的状态，给认证入口
                    CampusNetResult.NotOnline -> {
                        InfoRow(label = "状态", value = "本机未在校园网认证")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { mainController.openWebPage(CAMPUS_NET_PORTAL_URL) },
                        ) {
                            Text(text = "去认证")
                        }
                    }

                    // 连不上：多半不在校园网（内网地址校外不可达），属正常
                    is CampusNetResult.Failed -> {
                        InfoRow(label = "状态", value = "连不上 10.0.0.55（不在校园网时属正常）")
                        InfoRow(label = "诊断", value = r.reason, small = true)
                    }
                }
                InfoRow(
                    label = "数据来源",
                    value = "深澜自助（10.0.0.55，仅校园网环境）",
                    small = true,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (loading && fetched) "刷新中…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, small: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
