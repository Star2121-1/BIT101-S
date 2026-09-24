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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.data.school.CampusNetInfo
import cn.bit101.android.data.school.CampusNetLogic
import cn.bit101.android.data.school.CampusCardSnapshot
import cn.bit101.android.data.repo.base.CampusCardRepo
import cn.bit101.android.data.repo.base.CampusNetRepo
import cn.bit101.android.features.common.MainController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「校园服务」详情页的数据：一卡通快照 + 校园网在线信息，一次刷新同时取。
 */
@HiltViewModel
internal class CampusServiceViewModel @Inject constructor(
    private val campusCardRepo: CampusCardRepo,
    private val campusNetRepo: CampusNetRepo,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<CampusCardSnapshot?>(null)
    val snapshot: StateFlow<CampusCardSnapshot?> = _snapshot.asStateFlow()

    private val _netInfo = MutableStateFlow<CampusNetInfo?>(null)
    val netInfo: StateFlow<CampusNetInfo?> = _netInfo.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 已完成过一次刷新（区分「获取中」与「失败」）。 */
    private val _fetched = MutableStateFlow(false)
    val fetched: StateFlow<Boolean> = _fetched.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val card = async { runCatching { campusCardRepo.fetchSnapshot() }.getOrNull() }
                val net = async { runCatching { campusNetRepo.fetchOnlineInfo() }.getOrNull() }
                _snapshot.value = card.await()
                _netInfo.value = net.await()
                _fetched.value = true
            } finally {
                _loading.value = false
            }
        }
    }
}

/**
 * 「校园服务」详情页 —— 一卡通（余额摘要，流水待 cardpay 接口摸清后接入）+
 * 校园网（深澜自助：本次上线时间 / 累计流量 / 累计时长 / IP / 账户余额）。
 *
 * ⚠️ 校园网数据仅校园网环境可取（`10.0.0.55` 是内网地址）——取不到时显示
 * 「需连接校园网」，不报错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusServiceScreen(
    mainController: MainController,
) {
    val vm: CampusServiceViewModel = hiltViewModel()

    val snapshot by vm.snapshot.collectAsState()
    val netInfo by vm.netInfo.collectAsState()
    val loading by vm.loading.collectAsState()
    val fetched by vm.fetched.collectAsState()

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
                val balanceText = when {
                    loading && !fetched -> "获取中…"
                    snapshot == null -> "获取失败"
                    snapshot?.loggedIn == false -> "未登录（点右上角刷新前先在 WebView 登录过一次）"
                    else -> snapshot?.entries?.firstOrNull()?.let { "¥${it.second}" } ?: "未识别到余额"
                }
                InfoRow(label = "账户余额", value = balanceText)
                InfoRow(
                    label = "数据来源",
                    value = "延河一卡通（dkykt.info.bit.edu.cn）",
                    small = true,
                )
            }

            // 校园网
            SectionCard(title = "校园网（Srun 自助）") {
                when {
                    loading && !fetched -> InfoRow(label = "状态", value = "获取中…")
                    netInfo == null -> InfoRow(
                        label = "状态",
                        value = "需连接校园网后获取",
                    )
                    else -> {
                        val info = netInfo!!
                        InfoRow(label = "账号", value = info.userName)
                        InfoRow(label = "已用流量", value = CampusNetLogic.formatTraffic(info.bytesTotal))
                        InfoRow(label = "累计在线时长", value = CampusNetLogic.formatDuration(info.durationSeconds))
                        InfoRow(label = "本次上线", value = CampusNetLogic.formatTime(info.loginEpochSeconds))
                        InfoRow(label = "本机 IP", value = info.ip)
                        InfoRow(label = "账户余额", value = "¥%.2f".format(info.balanceYuan))
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
