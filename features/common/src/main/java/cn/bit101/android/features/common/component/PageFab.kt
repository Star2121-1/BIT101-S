package cn.bit101.android.features.common.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** [PageFab] 的默认尺寸。要引用规格时用它，**别在页面里写死 42.dp**。 */
val PAGE_FAB_SIZE: Dp = 42.dp

/** [PageFab] 的默认底色透明度（与主题 `primaryContainer` 相乘）。 */
const val PAGE_FAB_CONTAINER_ALPHA: Float = 0.8f

/**
 * 页面右下角那组悬浮按钮（FAB）的**统一样式** —— 全 App 只有这一份。
 *
 * ## 为什么要抽（2026-09-30 重复代码审计）
 *
 * 这套样式（42dp、`primaryContainer` 八成透明、`primary` 前景、零海拔）
 * 此前在 **14 个 FAB 上逐字重复了 5 遍**：课表 5 个 / 话廊 3 个 / DDL 3 个 /
 * 空教室 2 个 / 动态 1 个。想调一次样式（比如底色再淡一点）要改 5 个文件，
 * 而且**已经开始走样**：动态页那份是直接 `size(42.dp)`，没走页内的 `fabSize` 变量。
 *
 * ## 用法
 *
 * 页面里**不要再手写 `FloatingActionButton` + 那四行颜色配置** ——
 * 就地写的那几份正是这次审计要清掉的东西。确实需要不一样的外观时，
 * 在这里加参数，而不是在页面里另写一份。
 */
@Composable
fun PageFab(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PAGE_FAB_SIZE,
) {
    FloatingActionButton(
        modifier = modifier.size(size),
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(PAGE_FAB_CONTAINER_ALPHA),
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * 「回到列表顶部」的悬浮按钮：[PageFab] + 「滚过一段才出现」的淡入淡出。
 *
 * ⚠️ [onClick] **刻意放在最后一个参数**（而不是紧跟必需参数）：Kotlin 的尾随 lambda
 * 只绑定最后一个参数，放最后才能让调用点写成 `BackToTopFab(visible = show) { … }`。
 *
 * ⚠️ 出现的判据（[visible]）**也刻意交给调用方**，不在这里统一：
 * 话廊是 `firstVisibleItemIndex > 1`、空教室是 `> 0` —— 两个列表首屏高矮不同，
 * 硬统一会让其中一页的按钮出现得太早或太晚。
 */
@Composable
fun BackToTopFab(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        PageFab(
            icon = Icons.Rounded.ArrowUpward,
            description = "回到顶部",
            onClick = onClick,
        )
    }
}
