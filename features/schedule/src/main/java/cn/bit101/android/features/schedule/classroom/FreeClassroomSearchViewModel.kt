package cn.bit101.android.features.schedule.classroom

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.CourseScheduleSettings
import cn.bit101.android.config.setting.base.FreeClassroomSettings
import cn.bit101.android.config.setting.base.TimeTable
import cn.bit101.android.config.setting.base.TimeTableItem
import cn.bit101.android.data.repo.base.FreeClassroomRepo
import cn.bit101.android.data.repo.base.LoginRepo
import cn.bit101.android.features.common.helper.SimpleDataState
import cn.bit101.android.features.common.helper.SimpleState
import cn.bit101.android.features.common.helper.withSimpleDataStateLiveData
import cn.bit101.api.model.common.BuildingInfo
import cn.bit101.api.model.common.ClassroomInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
internal class FreeClassroomSearchViewModel @Inject constructor(
    private val freeClassroomRepo: FreeClassroomRepo,
    private val loginRepo: LoginRepo,
    private val settingData: FreeClassroomSettings,
    private val scheduleSettings: CourseScheduleSettings,
) : ViewModel() {
    val nowCampusFlow = freeClassroomRepo.getCurrentCampusCode()
    val nowCampusNameFlow = freeClassroomRepo.getCurrentCampusName()

    val hideBusyClassroomFlow = settingData.hideBusyClassroom.flow

    val freeMinutesThresholdFlow = settingData.freeMinutesThreshold.flow

    /**
     * 教学楼列表状态 —— **一次拿全部校区的楼**，前端再用 [CampusGrouping] 分组成
     * 「校区 → 教学楼 → 教室」三级。
     */
    val getBuildingTypeStatusLiveData = MutableLiveData<SimpleDataState<List<BuildingInfo>>?>(null)

    /** 已展开的校区（存校区代码；用 code 而不是下标，分组顺序变了也不会串页）。 */
    val expandedCampuses = mutableStateListOf<String>()

    /** 已展开的教学楼（存教学楼代码，即 `buildingIndex`）。 */
    val expandedBuildings = mutableStateListOf<String>()

    /** 自动展开「当前校区」是否已经做过（见 [autoExpandCampus]）。 */
    private var autoExpandDone = false

    data class ClassroomBusyData(
        val classroom: ClassroomInfo,
        val prettyFreeTimes: String,
        val nextBusyTime: LocalTime,
        val nextFreeTime: LocalTime? = null,    // 只在 busy 时有值, 记录还有多久才会迎来下一个 (长于阈值的) 空闲时间
    )

    /**
     * 拉教学楼列表。
     *
     * ⚠️ **不带 campusCode**：SDK 在不给校区时压根不加过滤参数
     * （见 `Campus.getBuildingList`），服务端会把**所有校区**的楼一起返回 ——
     * 正好用来做「先按校区分组」。每栋楼自带 `XXXQDM` / `XXXQDM_DISPLAY`，
     * 所以不必再为每个校区单独发一次请求。
     *
     * ⚠️ 万一服务端在这种调用下返回空 / 报错，就**退回「只取当前校区」**——
     * 也就是这个功能支持分校区之前的老行为。宁可少一层分组，也不能比原来更差。
     */
    private suspend fun getBuildingTypesWithoutState(): List<BuildingInfo> {
        val currentCode = nowCampusFlow.first()

        val all = runCatching { freeClassroomRepo.getBuildingInfos() }
        if (all.getOrNull()?.isNotEmpty() == true) return all.getOrThrow()

        // 没有退路（连当前校区都没设过）→ 如实抛出失败，让 UI 提示"拉取失败"
        if (currentCode.isEmpty()) return all.getOrElse { throw it }

        return freeClassroomRepo.getBuildingInfos(currentCode)
    }

    fun loadBuildingTypes() = withSimpleDataStateLiveData(getBuildingTypeStatusLiveData) {
        loginRepo.doOperationRequiresLogin(this::getBuildingTypesWithoutState)
    }

    val getClassroomsStatesMap = mutableStateMapOf<String,SimpleState>()
    val getClassroomLastStatusLiveData = mutableStateOf<SimpleState?>(null)

    internal data class ClassroomDataCache(
        val data: List<ClassroomBusyData>,
        val cacheValidUntil: LocalDateTime
    )

    val classroomDataMap = mutableStateMapOf<String, List<ClassroomBusyData>>()
    private var classroomDataCache = hashMapOf<String, ClassroomDataCache>()

    // 获取时间表上的下一个时间节点
    private fun getNextTimeTableTime(timeTable: TimeTable) : LocalTime {
        val nextTimeTableItem = timeTable
            .find { it.startTime > LocalTime.now() || it.endTime > LocalTime.now() }
            ?: TimeTableItem(LocalTime.MAX, LocalTime.MAX)

        return if(nextTimeTableItem.startTime > LocalTime.now())
            nextTimeTableItem.startTime
        else
            nextTimeTableItem.endTime
    }
    // 根据非空闲时段获取格式化后的空闲时段字符串, 合并相邻的时段, 并按时间顺序排序
    private fun getPrettyFreeTimeStr(sortedBusyTimes: List<Int>, timeCount: Int): String {
        var prettyFreeTime = ""

        val sortedFreeTimes = ((1..timeCount) - sortedBusyTimes.toSet()).toList()

        if (sortedFreeTimes.isNotEmpty()) {
            var lastGroupIndex = 0

            prettyFreeTime += sortedFreeTimes.first()

            for (i in 1..<sortedFreeTimes.size) {
                if (sortedFreeTimes[i] - i != sortedFreeTimes[lastGroupIndex] - lastGroupIndex) {
                    if (i - lastGroupIndex > 1) {
                        prettyFreeTime += "~${sortedFreeTimes[i - 1]}, ${sortedFreeTimes[i]}"
                    } else {
                        prettyFreeTime += ", ${sortedFreeTimes[i]}"
                    }
                    lastGroupIndex = i
                } else if(i == sortedFreeTimes.size - 1) {
                    // 一个区间的收尾是由下一个区间 / 单个值的开始负责的 (比如 1~3, 7~9 里, 1~3 的那个 3 是由 7 负责输出的)
                    // 所以最后一个要特殊处理
                    prettyFreeTime += "~${sortedFreeTimes[i]}"
                }
            }
        } else {
            prettyFreeTime = "无"
        }

        return prettyFreeTime
    }
    private suspend fun loadClassroomInfosWithoutState(buildingId: String) {
        getClassroomsStatesMap[buildingId] = SimpleState.Loading
        getClassroomLastStatusLiveData.value = SimpleState.Loading

        runCatching {
            val allClassrooms: List<ClassroomInfo>

            // 从 Web 上拉取下来的值理论上在同一天内都应该相同, 所以缓存失效了也能复用 (不如说本来最需要缓存的就是 Web 请求)
            if (classroomDataCache.containsKey(buildingId)) {
                val cacheValidUntil = classroomDataCache[buildingId]!!.cacheValidUntil

                if (cacheValidUntil < LocalDateTime.now()) {
                    // 换日期了就没办法了
                    if (cacheValidUntil.toLocalDate() != LocalDate.now()) {
                        allClassrooms = freeClassroomRepo.getClassroomInfos(buildingId = buildingId)
                    } else {
                        allClassrooms = classroomDataCache[buildingId]!!.data.map { it.classroom }
                    }
                    classroomDataCache.remove(buildingId)
                } else {
                    allClassrooms = emptyList()     // 这个情况下用不到 allClassrooms, 随便给个值就行
                }
            } else {
                allClassrooms = freeClassroomRepo.getClassroomInfos(buildingId = buildingId)
            }

            if (!classroomDataCache.containsKey(buildingId)) {
                classroomDataMap.remove(buildingId)

                val timeTable = scheduleSettings.timeTable.get()

                // timeTable 中下一节课对应的下标
                var nextClassIndex = 0

                if (LocalTime.now() < timeTable.first().startTime)
                    nextClassIndex = 0
                else if (LocalTime.now() > timeTable.last().endTime)
                    nextClassIndex = timeTable.size
                else {
                    for (i in timeTable.indices.reversed()) {
                        if (LocalTime.now() >= timeTable[i].startTime) {
                            nextClassIndex = i + 1
                            break
                        }
                    }
                }

                val ret = allClassrooms.map { classroom ->
                    val sortedBusyTimes =
                        classroom.busyTimeStr
                            ?.split(',')
                            .orEmpty()
                            .map { it.toInt() }
                            .sorted()

                    val nextBusyTime: LocalTime

                    // 先计算下一个开始不空闲的时间, 为 LocalTime.MIN 说明在计算时就已经不空闲了, 为 LocalTime.MAX 则说明直到今天结束都保持空闲
                    if (nextClassIndex < timeTable.size) {
                        val nextBusyTimeIndex = sortedBusyTimes.find { it - 1 >= nextClassIndex }?.minus(1)

                        nextBusyTime =
                            if (nextBusyTimeIndex == null)   // 没找到说明后面没课了
                                LocalTime.MAX
                            else
                                if (sortedBusyTimes.contains(nextBusyTimeIndex)
                                    && timeTable[nextBusyTimeIndex - 1].endTime >= LocalTime.now()
                                )     // 当前正在上课
                                    LocalTime.MIN
                                else
                                    timeTable[nextBusyTimeIndex].startTime
                    } else {
                        nextBusyTime =
                            if (sortedBusyTimes.isNotEmpty() && timeTable[sortedBusyTimes.last() - 1].endTime >= LocalTime.now())
                                LocalTime.MIN
                            else
                                LocalTime.MAX
                    }

                    // 再 (在当前不空闲的情况下) 计算下一个恢复空闲的时间
                    // 会考虑阈值
                    var nextFreeTime: LocalTime? = null

                    if (nextBusyTime == LocalTime.MIN) {
                        var findIndex = 0
                        val sortedBusyTimeIndices = sortedBusyTimes.map { it - 1 }

                        while (findIndex < sortedBusyTimeIndices.size - 1) {
                            val nowIndex = sortedBusyTimeIndices[findIndex]
                            val nextIndex = sortedBusyTimeIndices[findIndex + 1]

                            if (timeTable[nowIndex].endTime >= LocalTime.now()
                                && timeTable[nextIndex].startTime.toSecondOfDay()
                                - timeTable[nowIndex].endTime.toSecondOfDay()
                                > settingData.freeMinutesThreshold.get() * 60
                            ) {
                                nextFreeTime = timeTable[nowIndex].endTime
                                break
                            }
                            findIndex++
                        }
                        // 无论如何, 所有课上完后教室一定会空闲下来
                        if (nextFreeTime == null)
                            nextFreeTime = timeTable[sortedBusyTimeIndices.last()].endTime
                    }

                    ClassroomBusyData(
                        classroom = classroom,
                        prettyFreeTimes = getPrettyFreeTimeStr(sortedBusyTimes, timeTable.size),
                        nextBusyTime = nextBusyTime,
                        nextFreeTime = nextFreeTime,
                    )
                }.sortedWith(
                    compareByDescending<ClassroomBusyData> {
                        if (it.nextBusyTime == LocalTime.MIN)
                            -it.nextFreeTime!!.toSecondOfDay()  // 负数既可以反转排序顺序, 也能保证目前空闲的教室一定排在目前不空闲的前面
                        else
                            it.nextBusyTime.toSecondOfDay()
                    }.thenBy { it.classroom.classroomName }
                )

                classroomDataCache[buildingId] = ClassroomDataCache(
                    data = ret,
                    cacheValidUntil = getNextTimeTableTime(timeTable).atDate(LocalDate.now())
                )
            }

            if (classroomDataCache[buildingId]!!.data != classroomDataMap[buildingId])
                classroomDataMap[buildingId] = classroomDataCache[buildingId]!!.data

            getClassroomsStatesMap[buildingId] = SimpleState.Success
            getClassroomLastStatusLiveData.value = SimpleState.Success
        }.onFailure {
            it.printStackTrace()

            getClassroomsStatesMap[buildingId] = SimpleState.Fail
            getClassroomLastStatusLiveData.value = SimpleState.Fail
        }
    }

    // 获取对应教学楼的全部教室及其空闲情况
    fun loadClassroomInfos(buildingId: String) = viewModelScope.launch(Dispatchers.IO) {
        loginRepo.doOperationRequiresLogin { loadClassroomInfosWithoutState(buildingId) }
    }
    fun refreshAllClassroomInfo() {
        classroomDataCache.forEach { classroomDataCache[it.key] = it.value.copy(cacheValidUntil = LocalDateTime.now()) }
        val tempKeys = classroomDataCache.map { it.key }
        tempKeys.forEach {loadClassroomInfos(it)}
    }
    fun refreshClassroomInfoIfInvalid(buildingId:String) {
        val cacheValidUntil = classroomDataCache[buildingId]?.cacheValidUntil
        if(cacheValidUntil == null || cacheValidUntil < LocalDateTime.now())
            loadClassroomInfos(buildingId)
    }

    fun isFreeNow(classroomBusyData: ClassroomBusyData,
                  nowTime: LocalTime,
                  freeMinutesThreshold: Long) : Boolean {
        return classroomBusyData.nextBusyTime == LocalTime.MAX
                || classroomBusyData.nextBusyTime.toSecondOfDay() >= nowTime.toSecondOfDay() + freeMinutesThreshold * 60
    }

    // ------------------------------------------------------------------ 展开状态

    /** 展开 / 收起一个校区。 */
    fun switchCampus(campusCode: String) = toggle(expandedCampuses, campusCode)

    /** 展开 / 收起一栋教学楼（展开时要由调用方先触发 [loadClassroomInfos]）。 */
    fun switchBuilding(buildingId: String) = toggle(expandedBuildings, buildingId)

    /**
     * 加载完之后自动展开**当前校区**（只做一次）。
     *
     * 改版前空教室页进来直接就是教学楼列表；现在多了一层「校区」，
     * 不自动展开的话老用户会觉得白白多了一次点击。
     */
    fun autoExpandCampus(campusCode: String) {
        if (autoExpandDone || campusCode.isEmpty()) return
        autoExpandDone = true
        if (!expandedCampuses.contains(campusCode)) expandedCampuses.add(campusCode)
    }

    /** 校区切换 / 下拉刷新时清空展开状态（下次会重新自动展开当前校区）。 */
    fun clearExpanded() {
        expandedCampuses.clear()
        expandedBuildings.clear()
        autoExpandDone = false
    }

    private fun toggle(list: MutableList<String>, key: String) {
        if (list.contains(key)) list.remove(key) else list.add(key)
    }
}