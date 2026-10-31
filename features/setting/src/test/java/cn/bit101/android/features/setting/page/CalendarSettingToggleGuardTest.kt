package cn.bit101.android.features.setting.page

import cn.bit101.android.features.setting.viewmodel.CalendarToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：课表显示设置里**一个开关只能改一个 key**。
 *
 * ## 事故经过（2026-10-31 排查「课表上的东西不见了」）
 * 原实现：每个开关 `onToggle` 都调 `onSettingChange(settingData.copy(x = it))`，
 * ViewModel 侧 `setSettingData(...)` 把 **7 个 key 全写一遍**。
 *
 * 而页面里 `settingData` 是 `collectAsState(initial = SettingData.default)`，
 * 首帧那份 default **全是 false**（`showCurrentTime` / `showDivider` / `showExamInfo`
 * 都在内）。从进入页面到 DataStore 真值发射之间有几十~几百毫秒，在这段窗口里点任意
 * 一个开关，其余 6 个就会被一起写成 false —— 用户看到的现象正是「我没动它，
 * 课表上的当前时间线自己没了」。
 *
 * 修法：每个开关只写自己那一个 key（[CalendarToggle] 分派）。本测试守两件事：
 * ① 页面层不许再基于整份 `settingData` 写回；② ViewModel 不许再有全量写回的入口。
 */
class CalendarSettingToggleGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile
        }
        return dir ?: error("找不到仓库根（向上层目录没找到 settings.gradle）——守卫无法执行，宁可红")
    }

    private val pageFile
        get() = File(
            repoRoot(),
            "features/setting/src/main/java/cn/bit101/android/features/setting/page/CalendarSettingPage.kt",
        )

    private val viewModelFile
        get() = File(
            repoRoot(),
            "features/setting/src/main/java/cn/bit101/android/features/setting/viewmodel/CalendarViewModel.kt",
        )

    @Test
    fun 课表设置页_开关不许基于整份settingData写回() {
        val code = pageFile.readText()
        assertTrue("文件不存在：${pageFile.absolutePath}", pageFile.exists())

        assertTrue(
            "CalendarSettingPage 里又出现了 `settingData.copy(` —— 这就是「点一个开关，" +
                "别的开关被写成首帧默认值」的来源。开关必须走 onToggle(CalendarToggle.X, it)。",
            "settingData.copy(" !in code,
        )
        assertTrue(
            "CalendarSettingPage 里又出现了 onSettingChange（整份写回的老入口）",
            "onSettingChange(" !in code,
        )

        // 七个开关一个都不能少：少一个就说明有人把它改回了别的写法
        listOf("Saturday", "Sunday", "Border", "HighlightToday", "Divider", "CurrentTime", "ExamInfo")
            .forEach { t ->
                assertTrue(
                    "显示设置里缺少 onToggle(CalendarToggle.$t, it) —— 开关没接上（或又被改回整份写回）",
                    "onToggle(CalendarToggle.$t, it)" in code,
                )
            }
    }

    @Test
    fun 课表设置ViewModel_不许有全量写回入口() {
        val code = viewModelFile.readText()
        assertTrue(
            "CalendarViewModel 里又出现了 setSettingData —— 它会一次写 7 个 key，" +
                "首帧占位值会把用户没碰过的开关关掉。请用 setToggle(toggle, value)。",
            "fun setSettingData(" !in code,
        )
        assertTrue(
            "CalendarViewModel 缺少 setToggle（逐项写入的唯一入口）",
            "fun setToggle(" in code,
        )
    }

    @Test
    fun 枚举项与设置项一一对应() {
        // 7 个开关：周六 / 周日 / 边框 / 高亮今日 / 分割线 / 当前时间线 / 考试安排
        assertEquals(
            "CalendarToggle 的成员数必须等于设置页开关数（改设置项时同步改枚举）",
            7,
            CalendarToggle.entries.size,
        )
    }
}
