package cn.bit101.android.features.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.bit101.android.data.school.StudySpotLogic
import cn.bit101.android.features.common.MainController

/**
 * 「现在去哪儿自习」详情页 —— 从「我」页那张卡点进来，把**整排候选**摊开看。
 *
 * ## 为什么要有这一页
 *
 * 卡片上只能显示一条（位置很金贵），而「换一个」是顺序切换 —— 想同时比较几处、
 * 或者想看清「各层空几间 / 能待到几点」，卡片那两行装不下。
 *
 * ## ⚠️ 与卡片**不共用** [StudySpotLogic.Plan]（但共用同一份缓存）
 *
 * 这一页**不重算**任何东西：文案、排序、数字都是取数时那一份（见 [StudySpotLogic.detailOf]）。
 * 自己再算一遍必然会和卡片上的数对不上 —— 而「同一个功能两个地方说法不同」是最难解释的 bug。
 *
 * ⚠️⚠️ 但有一点要说准（2026-10-13 核实后改的）：本页注册在**外层 NavHost**
 *    （`composableStudySpot`），而卡片那一节在 `IndexScreen` **内层** NavHost 的
 *    `Mine` 路由里 ⇒ 两者是**两个不同的 [CampusServiceViewModel] 实例**，
 *    **各自算各自的 Plan**。
 *    ⇐ 于是「两处显示的时刻可能不一样」是**结构性的**，不是偶发：
 *    所以两处都要在回到前台时重算（见下面那个 ON_RESUME 观察者）。
 *
 * ⚠️ 真正共享的是**单例仓库**（`@Singleton DefaultStudySpotRepository`）：它缓存的只是
 *    「各楼原始教室列表」，所以从卡片点进来通常**不会再打网络**（除非距上次取数超过缓存时长）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySpotDetailScreen(
    mainController: MainController,
) {
    val vm: CampusServiceViewModel = hiltViewModel()

    val plan by vm.studySpotPlan.collectAsState()
    val loading by vm.studySpotLoading.collectAsState()

    // 从别的地方（空教室页等）回来时会重新进入组合，但 VM 保留、init 不会重跑 ⇒ 补一次刷新。
    // 首次进入时 init 已发起请求，会被 VM 里的守卫挡掉（不会打两轮）。
    LaunchedEffect(Unit) { vm.refresh() }

    // ⚠️ 与「我」页那张卡同一个道理：这一排「空到 20:55」也是**取数那一刻**渲染好的字符串，
    //    **「切后台一会儿再回来」不会重新进组合**（上面那句 `LaunchedEffect(Unit)` 不重跑）
    //    ⇒ 回到前台补一次重算（10 分钟 TTL 内不打网络）。
    //    ⚠️ 只重算自习建议，**不调 refresh()** —— 回个前台就去重拉一卡通 / 校园网没必要。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshStudySpot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val detail = plan?.let { StudySpotLogic.detailOf(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "现在去哪儿自习") },
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
        // ⚠️ 三种状态都要有交代，不能出现「一片空白」：
        //    取数中 → 转圈；取到了 → 内容；取不到 → 说清取不到（**不给瞎建议**）
        when {
            detail == null && loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            detail == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂时看不了空教室，下拉或点右上角重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(modifier = Modifier.height(2.dp))

                // 顶部：一句事实 + 一句交代（这一页在说什么）
                StatusCard(headline = detail.headline, subline = detail.subline)

                // ⚠️ 先落到局部变量再判空：`emptyHint` 是**另一个模块**的 public 属性，
                //    Kotlin 不做跨模块 smart cast（`if (detail.emptyHint != null)` 里
                //    它仍然是可空的，编译不过）
                val emptyHint = detail.emptyHint
                if (emptyHint != null) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }

                detail.rows.forEach { row -> SpotRowCard(row) }

                Spacer(modifier = Modifier.height(2.dp))

                // 想去看看具体是哪几间教室 → 走与卡片同一个跳转实现。
                // ⚠️ 这里必须用 `...FromDetail`：详情页是**顶层路由**，而 GotoRequest
                //    的消费方是 IndexScreen（详情页在前台时它没在组合）——
                //    直接发请求点了会**毫无反应**（真机实测）。它会先退回去再发。
                Button(
                    onClick = { openStudySpotTargetFromDetail(mainController, detail.kind) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = studySpotActionLabel(detail.kind))
                }

                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}

/** 顶部的状态卡：正在上什么 / 有预约 / 现在没课，外加一句这一页的说明。 */
@Composable
private fun StatusCard(headline: String, subline: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subline.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一行候选。
 *
 * ⚠️ 数字与地点**一起**来自 [StudySpotLogic.SpotDetailRow]（同范围）——
 *    界面不自己拼「10 间」这种数，否则迟早出现「文萃楼I 3 楼有 20 间」那种错位。
 */
@Composable
private fun SpotRowCard(row: StudySpotLogic.SpotDetailRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 只有排在最前的那个才标「推荐」—— 标一排等于没标
                if (row.top) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "推荐",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = row.place,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.untilText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            row.detail?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
