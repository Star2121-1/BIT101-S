package cn.bit101.android.features.setting.page

import cn.bit101.android.features.setting.viewmodel.CalendarToggle
import cn.bit101.android.features.setting.viewmodel.GalleryToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：设置页里**一个开关只能改一个 key**。
 *
 * ## 事故经过（2026-10-31 排查「课表上的东西不见了」）
 * 设置页原来每个开关都写成
 * `onClick = { onSettingChange(settingData.copy(x = it)) }`，
 * ViewModel 侧 `setSettingData(data)` 再把**这一类设置的所有 key 全写一遍**。
 *
 * 而页面里 `settingData` 来自 `collectAsState(initial = XxxSettingData.default)`：
 * 从进入页面到 DataStore 真值发射之间有几十~几百毫秒，这段时间里 `settingData`
 * 是那份**占位默认值**。在这窗口点任意一个开关，其余字段就被一起写成默认值：
 *
 * - 课表：`show_current_time`（当前时间线）被写成 false ⇒ 横线自己消失；
 * - 空教室：`currentCampus` 被写成 `CampusInfo("", "")`、阈值重置成 5 ⇒ 默认校区丢了；
 * - 话廊：`allowHorizontalScroll` 被写成默认 true ⇒ 用户关掉的横向滑动又开了。
 *
 * 修法：每个开关只写自己那一个 key（[CalendarToggle] / [GalleryToggle] 分派，
 * 空教室只有一个开关故直接用 `onToggleHideBusyClassroom`）。本测试守三件事：
 * ① 页面层不许再基于整份 `settingData` 写回；② ViewModel 不许再有全量写回入口；
 * ③ 每个开关都必须真的接上线（防止「接线被摘掉」这种静默失效）。
 */
class SettingsToggleWriteGuardTest {

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

    private fun mainSrc(relative: String) = File(repoRoot(), "features/setting/src/main/java/cn/bit101/android/features/setting/$relative")

    /** 去掉 `//` 与 `/* */` 注释，避免说明文字里的反例把守卫误判成红。 */
    private fun stripComments(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return noBlock.lineSequence()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                // 粗略处理：不在引号内的 // 才算注释（本仓库设置页没有含 // 的字符串字面量）
                if (i >= 0) line.substring(0, i) else line
            }
    }

    private val togglePages = listOf(
        "page/CalendarSettingPage.kt",
        "page/GallerySettingPage.kt",
        "page/FreeClassroomSettingPage.kt",
    )

    @Test
    fun 设置页_开关不许基于整份settingData写回() {
        val bad = mutableListOf<String>()
        togglePages.forEach { rel ->
            val file = mainSrc(rel)
            assertTrue("文件不存在：${file.absolutePath}", file.exists())
            val code = stripComments(file.readText())
            if ("settingData.copy(" in code) bad += "$rel → settingData.copy("
            if ("onSettingChange(" in code) bad += "$rel → onSettingChange("
            if ("setSettingData(" in code) bad += "$rel → setSettingData("
        }
        assertTrue(
            "以下设置页又在「整份 settingData 写回」了 —— 首帧占位值会把用户没碰过的开关\n" +
                "一起写掉（课表时间线/空教室校区就是这样丢的）。开关必须走逐项回调：\n" +
                bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun 设置ViewModel_不许有全量写回入口() {
        val bad = mutableListOf<String>()
        listOf("CalendarViewModel.kt", "GalleryViewModel.kt", "FreeClassroomViewModel.kt").forEach { name ->
            val file = mainSrc("viewmodel/$name")
            assertTrue("文件不存在：${file.absolutePath}", file.exists())
            if ("fun setSettingData(" in stripComments(file.readText())) bad += name
        }
        assertTrue(
            "以下 ViewModel 又出现了 fun setSettingData( —— 它一次写整组 key，\n" +
                "首帧默认值会覆盖用户设置。请改成逐项 setter：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun 课表设置_七个开关逐个接线() {
        val code = stripComments(mainSrc("page/CalendarSettingPage.kt").readText())
        listOf("Saturday", "Sunday", "Border", "HighlightToday", "Divider", "CurrentTime", "ExamInfo")
            .forEach { t ->
                assertTrue(
                    "课表显示设置缺少 onToggle(CalendarToggle.$t, it) —— 开关没接上（或又被改回整份写回）",
                    "onToggle(CalendarToggle.$t, it)" in code,
                )
            }
        assertEquals("CalendarToggle 成员数必须等于课表显示开关数", 7, CalendarToggle.entries.size)
    }

    @Test
    fun 话廊设置_四个开关逐个接线() {
        val code = stripComments(mainSrc("page/GallerySettingPage.kt").readText())
        listOf("HideBotPoster", "HideBotPosterInSearch", "HideStrictMode", "AllowHorizontalScroll")
            .forEach { t ->
                assertTrue(
                    "话廊设置缺少 onToggle(GalleryToggle.$t, it) —— 开关没接上（或又被改回整份写回）",
                    "onToggle(GalleryToggle.$t, it)" in code,
                )
            }
        assertEquals("GalleryToggle 成员数必须等于话廊里走映射的开关数", 4, GalleryToggle.entries.size)
    }

    @Test
    fun 空教室设置_开关走单项回调() {
        val code = stripComments(mainSrc("page/FreeClassroomSettingPage.kt").readText())
        assertTrue(
            "空教室设置缺少 onToggleHideBusyClassroom(it) —— 开关没接上",
            "onToggleHideBusyClassroom(it)" in code,
        )
    }
}
