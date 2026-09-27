package cn.bit101.android.features.schedule.transport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.data.bus.ShuttleLogic
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 「交通」页：良乡校区**摆渡车**时刻表与「下一班」倒计时。
 *
 * ## 为什么在课表页里
 *
 * 课表页早就不只是课表了（课表 / DDL / 动态 / 空教室），本质是**校园学习生活工具的聚合**。
 * 空教室是「去哪儿自习」，这里是「怎么出去」—— 同属一类。
 * ⚠️ 页签**只能追加在最后**（见 `ScheduleTabs`：下标是组件跳转依赖的）。
 *
 * ## 数据与取舍
 *
 * 时刻表来自**站点站牌实拍**（`docs/campus-bus.md`），站牌上就是两套：
 * 工作日 39 班 / 周末及节假日 50 班。口径与坑都写在 [ShuttleLogic] 里。
 *
 * ⚠️ **本页不联网**：全部是本地常量。摆渡车是「到点就等」的免费车，
 * 网上没有实时到站数据，所以这里刻意只做「按表推算」，不假装实时。
 */
@Composable
internal fun TransportScreen(active: Boolean) {
    val vm: TransportViewModel = hiltViewModel()
    val stop by vm.stop.collectAsState()
    val override by vm.dayTypeOverride.collectAsState()

    // 「现在」：倒计时要跟着走。20 秒刷一次（比整分钟细一点，切页回来也不会停太久）
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = LocalDateTime.now()
            delay(20_000)
        }
    }

    val autoDayType = ShuttleLogic.dayTypeOf(now.toLocalDate())
    val dayType = override ?: autoDayType
    val nowTime = now.toLocalTime()
    val times = ShuttleLogic.timetable(stop, dayType)
    val next = ShuttleLogic.nextDeparture(stop, dayType, nowTime)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SectionLabel("发车站点")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShuttleLogic.Stop.entries.forEach { s ->
                FilterChip(
                    selected = s == stop,
                    onClick = { vm.selectStop(s) },
                    label = { Text(s.label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        SectionLabel("时刻表版本（站牌上就是两套）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = override == null,
                onClick = { vm.selectDayType(null) },
                label = { Text("自动 · ${autoDayType.label}") },
            )
            ShuttleLogic.DayType.entries.forEach { d ->
                FilterChip(
                    selected = override == d,
                    onClick = { vm.selectDayType(d) },
                    label = { Text(d.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        NextDepartureCard(next = next, stop = stop)

        Spacer(Modifier.height(14.dp))

        SectionLabel("今日班次（${dayType.label} · 共 ${times.size} 班）")
        // 按小时分组展示 —— 跟站牌上的排法一致，比竖着列 39 行好扫
        times.groupBy { it.hour }.toSortedMap().forEach { (hour, list) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "$hour 时",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                Text(text = minuteSpans(list, nowTime))
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = buildString {
                append("数据来源：站点站牌（2026-09-27 抄录）。工作日 39 班 / 周末及节假日 50 班。\n")
                append("⚠️ 法定节假日与调休 App 判断不了，请手动切上面的版本；")
                append("寒暑假发车时间可能调整，以 i北理 为准。\n")
                append("东校区南门可以中途上车，但那里没有固定发车时间，在门口等即可。\n")
                append("摆渡车刷校园卡免费，不需要预约。")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** 下一班的大卡片。今天已发完时给明确结论，而不是留空让人猜。 */
@Composable
private fun NextDepartureCard(next: ShuttleLogic.Departure?, stop: ShuttleLogic.Stop) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (next == null) {
                Text("今天的车已发完", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "末班 ${if (stop == ShuttleLogic.Stop.METRO) "22:40" else "22:30"} 已过，明天见",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("下一班", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = hm(next.at),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = ShuttleLogic.countdownText(next.inMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "今天还剩 ${next.remaining} 班 · 这是第 ${next.sequence} 班",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 把一小时的分钟列表渲染成一行，**已发车的分钟用浅色**。
 *
 * 用 `AnnotatedString` 而不是「整行判色」：同一小时里常常前几班已发、后几班没发，
 * 整行着色会给出错误暗示。
 */
@Composable
private fun minuteSpans(minutes: List<LocalTime>, now: LocalTime) = buildAnnotatedString {
    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val normal = MaterialTheme.colorScheme.onSurface
    minutes.forEachIndexed { i, t ->
        if (i > 0) append("   ")
        val passed = t.hour * 60 + t.minute < now.hour * 60 + now.minute
        withStyle(SpanStyle(color = if (passed) faded else normal)) {
            append("%02d".format(t.minute))
        }
    }
}
