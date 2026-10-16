package cn.bit101.android.features.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S2 守卫：`features/` 下**白名单外不许再出现硬编码 `Color(0x…)` 字面量**（P5-B）。
 *
 * 背景：三套 palette 的全部面色都收进 `features/theme/palette/` 三张表，
 * 经 [cn.bit101.android.features.theme.palette.Palettes] 分派。若哪个页面又
 * 就地写死一个色值，「同一语义跨 Tab 同色」与「换 palette 全App生效」都会被
 * 悄悄破坏——而且不报错、看不出来，故用测试守门。
 *
 * 白名单（P5-B 实测共 17 处，逐个数过：9+5+2+1）：
 * - `features/theme/` 整个目录（三张 palette 表 + GridTokens 共享段就在这儿）
 * - 4 个既有文件（历史遗留，逐处清理排在后续迭代）：
 *   `SeatColors.kt`(9) / `TaskCards.kt`(5) / `ReservationCards.kt`(2) / `WebScreen.kt`(1)
 *
 * 扫描范围 `features/`，排除 `/build/` 与 `/test/`（测试夹具里的色值不进产品）。
 * 失败信息列出**文件相对路径 + 行号**，照着清理或加白名单（加白名单要单独说明理由）。
 */
class PaletteGuardTest {

    @Test
    fun features白名单外_没有硬编码Color字面量() {
        // 仓库根定位：单测工作目录是模块目录（features/theme），向上找 settings.gradle(.kts)
        var dir: File? = File("").absoluteFile
        while (dir != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile
        }
        // error() 返回 Nothing，之后的 root 智能转换为非空
        val root: File = dir ?: error("找不到仓库根（向上层目录没找到 settings.gradle）——守卫无法执行，宁可红")

        val features = File(root, "features")
        assertTrue("features/ 目录不存在：${features.absolutePath}", features.isDirectory)

        // 白名单 4 文件按**文件名**匹配（实测全仓各只有一个）
        val whitelistNames = setOf(
            "SeatColors.kt",
            "TaskCards.kt",
            "ReservationCards.kt",
            "WebScreen.kt",
        )

        val violations = mutableListOf<String>()
        var scanned = 0
        var themeFiles = 0
        var whitelistSeen = 0

        features.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                // 排除构建产物与测试源集（在相对路径上判，避免绝对路径误伤）
                if (rel.contains("/build/") || rel.contains("/test/")) return@forEach
                scanned++
                if (rel.startsWith("features/theme/")) {
                    themeFiles++
                    return@forEach
                }
                if (f.name in whitelistNames) {
                    whitelistSeen++
                    return@forEach
                }
                f.forEachLineIndexed { lineNo, line ->
                    if (line.contains("Color(0x", ignoreCase = true)) {
                        violations += "$rel:$lineNo  ${line.trim().take(90)}"
                    }
                }
            }

        // 自校验：扫描面与白名单都必须真的走到（防止路径错 / 改名导致空跑假绿）
        assertTrue("白名单 4 文件应全部扫到，实际 $whitelistSeen 个——白名单名单可能过期", whitelistSeen == 4)
        assertTrue("features/theme/ 下一个 .kt 都没扫到——扫描路径可能错了", themeFiles > 0)
        assertTrue("features/ 总共只扫到 $scanned 个 .kt——扫描路径可能错了", scanned > 50)

        assertTrue(
            "白名单外发现硬编码 Color(0x…) 共 ${violations.size} 处" +
                "（清理进 palette 表，或确有遗留理由再单独加白名单）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}

/** 逐行带行号遍历（`forEachLine` 不带给号，行号是失败信息的关键）。 */
private inline fun File.forEachLineIndexed(action: (Int, String) -> Unit) {
    readLines().forEachIndexed { index, line -> action(index + 1, line) }
}
