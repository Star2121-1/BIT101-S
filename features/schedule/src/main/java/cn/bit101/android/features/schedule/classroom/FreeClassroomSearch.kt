package cn.bit101.android.features.schedule.classroom

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.bit101.android.features.common.MainController
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.nav.NavDest
import cn.bit101.android.features.common.utils.getCurrentTime
import cn.bit101.android.features.common.utils.mixColor
import cn.bit101.android.features.schedule.classroom.FreeClassroomSearchViewModel.ClassroomBusyData
import cn.bit101.api.model.common.BuildingInfo
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.*

private fun formatSecondToString(second: Int): String {
    return when (second) {
        in 0..<60 -> "< 1 分钟"
        in 60..<60 * 60 -> "${second / 60} 分钟"
        in 60 * 60..Int.MAX_VALUE ->
            "${
                second / 60 / 60
            } 小时${
                if((second / 60) % 60 != 0)
                    " ${(second / 60) % 60} 分钟"
                else
                    ""
            }"
        else -> "-${formatSecondToString(-second)}" // 按理来说不会发生
    }
}

@Composable
internal fun ClassroomList(
    currentClassrooms: List<ClassroomBusyData>,
    nowTime: LocalTime,
    isFreeNow: (ClassroomBusyData) -> Boolean
) {
    currentClassrooms
        .forEach { item ->
            val restSeconds = (item.nextBusyTime.toSecondOfDay() - nowTime.toSecondOfDay())

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp, 5.dp)
                    .clip(MaterialTheme.shapes.medium),
                color =
                if(isFreeNow(item))
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    mixColor(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                        0.5f
                    ),
                contentColor =
                if(isFreeNow(item))
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    mixColor(
                        MaterialTheme.colorScheme.onErrorContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        0.5f
                    ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(15.dp, 15.dp, 5.dp, 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        Text(
                            text = item.classroom.classroomName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "空闲时段: ${item.prettyFreeTimes}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                    ){
                        Text(
                            style = MaterialTheme.typography.bodyMedium,
                            text = when (item.nextBusyTime) {
                                LocalTime.MAX -> "空闲到明天"
                                else -> {
                                    if(restSeconds > 0)
                                        "还会空闲 ${formatSecondToString(restSeconds)}"
                                    else if(item.nextFreeTime != null)
                                        "${
                                            formatSecondToString(
                                                item.nextFreeTime.toSecondOfDay() - nowTime.toSecondOfDay()
                                            )
                                        } 后空闲"
                                    else
                                        "使用中"
                                }
                            }
                        )
                        if(item.nextBusyTime!=LocalTime.MAX)
                            Text(
                                style = MaterialTheme.typography.bodySmall,
                                text =
                                if(restSeconds > 0)
                                    "(直到 ${item.nextBusyTime})"
                                else if(item.nextFreeTime!=null)
                                    "(${item.nextFreeTime})"
                                else ""
                            )
                    }
                }
            }
        }
}

/**
 * 三级折叠的**中间层：教学楼**。
 *
 * @param subtitle 副标题（一般是校区名）。**分组之后传 null** —— 上面那层
 *   校区分组已经写着校区名了，再来一行是纯噪音
 */
@Composable
internal fun BuildingItem(
    buildingInfo: BuildingInfo,
    expanded: Boolean,
    onSwitchActive: () -> Unit,
    subtitle: String? = buildingInfo.campusName,
) {
    // 这里如果用 PrimaryContainer 系颜色的话, 就会和右下角的按钮完美地糊在一块, 用 SecondaryContainer 系的又会和教室完美地糊在一块
    // 于是调了半天调出了个勉强不会糊在一块的颜色
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp, 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSwitchActive() },
        color =
        mixColor(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primaryContainer,
            0.25f
        ),
        contentColor =
        mixColor(
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.onPrimaryContainer,
            0.25f
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        val arrowRotateDegrees: Float by animateFloatAsState(if (expanded) 90f else 0f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp, 15.dp, 5.dp, 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    text = buildingInfo.buildingName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .scale(1.25f)
                    .padding(horizontal = 5.dp)
                    .rotate(arrowRotateDegrees)
            )
        }
    }
}

/**
 * 三级折叠的**最外层：校区**。
 *
 * 颜色刻意用中性的 `surfaceContainerHigh`：PrimaryContainer 系会和右下角那组 FAB
 * 糊在一起、SecondaryContainer 系又和教室卡片糊在一起（见 [BuildingItem] 的注释）。
 * 层级靠「色块明度 + 缩进 + 字号」区分，而不是靠再加一种主题色。
 */
@Composable
internal fun CampusItem(
    campusName: String,
    buildingCount: Int,
    isCurrent: Boolean,
    expanded: Boolean,
    onSwitchActive: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp, 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSwitchActive() },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        val arrowRotateDegrees: Float by animateFloatAsState(if (expanded) 90f else 0f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp, 15.dp, 5.dp, 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Place,
                contentDescription = null,
                modifier = Modifier
                    .scale(1.15f)
                    .padding(end = 10.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    text = campusName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = buildString {
                        append("$buildingCount 栋教学楼")
                        if (isCurrent) append(" · 默认")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .scale(1.25f)
                    .padding(horizontal = 5.dp)
                    .rotate(arrowRotateDegrees)
            )
        }
    }
}

@Composable
internal fun LoadingTip(
    tipText: String,
) {
    Box{
        Text(
            text = tipText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp, 5.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(10.dp, 5.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp, 5.dp),
        )
    }
}

@Composable
internal fun FreeClassroomSearch(
    mainController: MainController,
    active: Boolean,
    vm: FreeClassroomSearchViewModel = hiltViewModel()
) {
    val getBuildingTypeStatus by vm.getBuildingTypeStatusLiveData.observeAsState()

    // 接口一次把**所有校区**的楼都拿回来，这里按校区分组 → 三级折叠的最外层
    val allBuildings = (getBuildingTypeStatus as? SimpleDataState.Success)?.data ?: emptyList()

    val currentCampusCode by vm.nowCampusFlow.collectAsState(initial = null)

    val currentCampusName by vm.nowCampusNameFlow.collectAsState(initial = null)

    val campusGroups = remember(allBuildings, currentCampusCode, currentCampusName) {
        CampusGrouping.group(
            buildings = allBuildings,
            currentCampusCode = currentCampusCode.orEmpty(),
            currentCampusName = currentCampusName.orEmpty(),
        )
    }

    // 进来先展开「默认校区」：改版前这里直接就是教学楼列表，多一层之后不自动展开
    // 会让人觉得白白多点一次（只在用户没动过展开状态时做一次）
    LaunchedEffect(campusGroups) {
        campusGroups.firstOrNull { it.isCurrent }?.let { vm.autoExpandCampus(it.campusCode) }
    }

    val getClassroomStatusMap = vm.getClassroomsStatesMap

    val currentClassroomData = vm.classroomDataMap

    val hideBusyClassroom = vm.hideBusyClassroomFlow.collectAsState(initial = false)

    val freeMinutesThreshold by vm.freeMinutesThresholdFlow.collectAsState(initial = null)

    // ⚠️ 用 SnapshotStateList 本身做状态源（读它的地方会自动重组），
    // 别把它拷进普通 List —— 拷了之后展开/收起就不会触发重组了
    val expandedCampuses = vm.expandedCampuses

    val expandedBuildings = vm.expandedBuildings

    val listState = rememberLazyListState()

    val coroutineScope = rememberCoroutineScope()

    // 非常 dirty 的解决方式
    var lastCampus by rememberSaveable { mutableStateOf(currentCampusCode) }
    LaunchedEffect(currentCampusCode) {
        // nowCampus 不可能为空, 所以为 null 的情况只能是 initial, 此时不用管
        if(currentCampusCode != null && currentCampusCode != lastCampus) {
            // 否则说明校区发生了切换
            lastCampus = currentCampusCode

            vm.loadBuildingTypes()

            vm.clearExpanded()
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(getBuildingTypeStatus) {
        if (getBuildingTypeStatus is SimpleDataState.Fail) {
            mainController.snackbar("拉取教学楼信息失败 Orz...")
        }
    }

    val getClassroomLastStatus = vm.getClassroomLastStatusLiveData.value
    LaunchedEffect(getClassroomLastStatus) {
        if (getClassroomLastStatus is SimpleState.Fail) {
            mainController.snackbar("拉取教室信息失败 Orz...")
        }
    }

    DisposableEffect(Unit) {
        if(getBuildingTypeStatus !is SimpleDataState.Success) {
            vm.loadBuildingTypes()
        }

        onDispose {
            if(getBuildingTypeStatus !is SimpleDataState.Success) {
                vm.getBuildingTypeStatusLiveData.value = null
            }
            if(getClassroomLastStatus !is SimpleState.Success) {
                vm.getClassroomLastStatusLiveData.value = null
            }
        }
    }

    var lastFreeMinutesThreshold by rememberSaveable{ mutableStateOf(freeMinutesThreshold) }
    LaunchedEffect(freeMinutesThreshold) {
        if(freeMinutesThreshold != null && freeMinutesThreshold != lastFreeMinutesThreshold) {
            lastFreeMinutesThreshold = freeMinutesThreshold
            vm.refreshAllClassroomInfo()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        val nowTime = getCurrentTime()

        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            if (getBuildingTypeStatus is SimpleDataState.Loading) {
                item { LoadingTip("正在拉取教学楼列表...") }
            } else if (campusGroups.isEmpty()) {
                item {
                    // 空态说得保守一点：以前会点名「没有获取到 XX 校区的教学楼」，
                    // 但现在列表是**所有校区**的，再点名某个校区就不准确了
                    Text(
                        text = "没有获取到教学楼QwQ\n(确认已登录学校账号，或到设置里切换默认校区)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp, 5.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(10.dp, 5.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                itemsIndexed(
                    campusGroups,
                    // 用校区代码做 key：分组顺序变化（比如换了默认校区）时不会张冠李戴
                    key = { _, group -> "campus:${group.campusCode}" },
                ) { _, group ->
                    val campusExpanded by remember {
                        derivedStateOf { expandedCampuses.contains(group.campusCode) }
                    }

                    CampusItem(
                        campusName = group.campusName,
                        buildingCount = group.buildings.size,
                        isCurrent = group.isCurrent,
                        expanded = campusExpanded,
                        onSwitchActive = { vm.switchCampus(group.campusCode) },
                    )

                    AnimatedVisibility(
                        visible = campusExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 25.dp)
                        ) {
                            group.buildings.forEach { building ->
                                val buildingExpanded by remember(building.buildingIndex) {
                                    derivedStateOf {
                                        expandedBuildings.contains(building.buildingIndex)
                                    }
                                }

                                BuildingItem(
                                    buildingInfo = building,
                                    expanded = buildingExpanded,
                                    // 上面那层校区分组已经写着校区名了，副标题再来一遍是噪音
                                    subtitle = null,
                                    onSwitchActive = {
                                        if (!buildingExpanded) {
                                            vm.loadClassroomInfos(building.buildingIndex)
                                        }

                                        vm.switchBuilding(building.buildingIndex)
                                    }
                                )

                                AnimatedVisibility(
                                    visible = buildingExpanded,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = 25.dp)
                                    ) {
                                        if (getClassroomStatusMap[building.buildingIndex] is SimpleState.Loading) {
                                            LoadingTip("正在获取教室列表...")
                                        } else {
                                            if (currentClassroomData.containsKey(building.buildingIndex)) {
                                                vm.refreshClassroomInfoIfInvalid(building.buildingIndex)
                                            }

                                            val currentClassrooms =
                                                currentClassroomData[building.buildingIndex]
                                                    .orEmpty()
                                                    .filter {
                                                        !hideBusyClassroom.value || vm.isFreeNow(
                                                            it,
                                                            nowTime,
                                                            freeMinutesThreshold ?: 0
                                                        )
                                                    }

                                            if (currentClassrooms.isEmpty()) {
                                                Text(
                                                    text = "未找到教室 :(",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(10.dp, 5.dp)
                                                        .clip(MaterialTheme.shapes.medium)
                                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                                        .padding(10.dp, 5.dp),
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            } else {
                                                ClassroomList(
                                                    currentClassrooms = currentClassrooms,
                                                    nowTime = nowTime,
                                                    isFreeNow = { vm.isFreeNow(it, nowTime, freeMinutesThreshold ?: 0) },
                                                )
                                            }
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }

        // 悬浮按钮组
        val fabSize = 42.dp
        Column(
            modifier = Modifier
                .padding(10.dp, 20.dp)
        ) {
            // 从话廊那毛过来的回到顶部按钮
            val show by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            AnimatedVisibility(
                visible = show,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FloatingActionButton(
                    modifier = Modifier
                        .size(fabSize),
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = "回到顶部"
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            // 设置按钮
            FloatingActionButton(
                modifier = Modifier
                    .size(fabSize),
                onClick = { mainController.navigate(NavDest.Setting("freeClassroom")) },
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.8f),
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "settings",
                )
            }
        }
    }
}