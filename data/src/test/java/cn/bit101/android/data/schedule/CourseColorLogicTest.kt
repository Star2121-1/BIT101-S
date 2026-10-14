package cn.bit101.android.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 课程 12 色板的**唯一哈希出处**测试（规范 §8.2，实施计划 P3 / 守卫 S3）。
 *
 * 两类断言：
 * 1. **行为**：规范 §9.1 的「课名 → 色号」对照表逐条固化 —— 14 门课、12 色全覆盖、
 *    周三「数据结构 & 大学英语(三)」同落色号 4 的撞色组。对照表当年是独立重算的，
 *    这里把那份手工结果变成可重跑的 fixture（手抄清单 ≠ 有清单）。
 * 2. **守卫（S3）**：全仓源码扫描 —— `CourseColorLogic` 之外不允许再出现一份
 *    色号哈希实现。组件（RemoteViews）、通知都拿不到 CompositionLocal，
 *    最容易诱使人「就地再写一份哈希」，这条守卫就是防它的。
 */
class CourseColorLogicTest {

    // ── 行为：规范 §9.1 对照表 fixture ──

    /** 规范 §9.1 演示课表（14 门）按色号归组后的期望值（手工独立重算固化于此）。 */
    private val expectedByIndex: Map<Int, List<String>> = mapOf(
        0 to listOf("线性代数"),
        1 to listOf("期末复习·自习"),
        2 to listOf("Java程序设计"),
        3 to listOf("常微分方程"),
        4 to listOf("数据结构", "大学英语(三)", "金工实习(一)"),
        5 to listOf("高等数学A(下)"),
        6 to listOf("中国近现代史纲要"),
        7 to listOf("微机原理与接口技术"),
        8 to listOf("数字电子技术"),
        9 to listOf("离散数学"),
        10 to listOf("复变函数与积分变换"),
        11 to listOf("人工智能导论"),
    )

    private val allNames get() = expectedByIndex.values.flatten()

    @Test
    fun `对照表逐条命中`() {
        expectedByIndex.forEach { (index, names) ->
            names.forEach { name ->
                assertEquals("课名「$name」应落色号 $index", index, CourseColorLogic.of(name))
            }
        }
    }

    @Test
    fun `12 个色号全覆盖`() {
        val covered = allNames.map { CourseColorLogic.of(it) }.toSet()
        assertEquals((0 until CourseColorLogic.PALETTE_SIZE).toSet(), covered)
    }

    @Test
    fun `周三撞色组 - 数据结构与大学英语三同落色号4`() {
        // §8.3 的第二编码兜底（时间线补教师名 / 网格块补教师姓）靠这组演示触发
        assertEquals(
            CourseColorLogic.of("数据结构"),
            CourseColorLogic.of("大学英语(三)"),
        )
        assertEquals(4, CourseColorLogic.of("数据结构"))
    }

    @Test
    fun `确定性 - 同名永远同号且不越界`() {
        allNames.forEach { name ->
            val first = CourseColorLogic.of(name)
            repeat(3) { assertEquals(first, CourseColorLogic.of(name)) }
            assertTrue(first in 0 until CourseColorLogic.PALETTE_SIZE)
        }
    }

    @Test
    fun `空串与空白也是确定值而非异常`() {
        assertEquals(CourseColorLogic.of(""), CourseColorLogic.of(""))
        assertEquals(CourseColorLogic.of("   "), CourseColorLogic.of("   "))
    }

    @Test
    fun `fnv1a32 与规范演示算法的已知值一致`() {
        // 空串的 FNV-1a 32 位是定义值 0x811C9DC5
        assertEquals(0x811C9DC5L, CourseColorLogic.fnv1a32(""))
        // "a" 的 FNV-1a 32 位是公开已知值 0xE40C292C
        assertEquals(0xE40C292CL, CourseColorLogic.fnv1a32("a"))
    }

    // ── 守卫 S3：全仓只许这一份色号哈希 ──

    /** 找到仓库根（测试工作目录是模块目录，向上找带 features/theme 的层级）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val d = dir ?: return@repeat
            if (File(d, "features/theme").isDirectory && File(d, "app").isDirectory) return d
            dir = d.parentFile
        }
        error("找不到仓库根（从 ${System.getProperty("user.dir")} 向上 6 层都没有 features/theme + app）")
    }

    /** 剥掉行注释与块注释 —— KDoc 里提到算法名不算实现。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*+[\\s\\S]*?\\*+/"), "")
            .replace(Regex("//[^\n]*"), "")

    @Test
    fun `守卫S3 - 色号哈希只许在 CourseColorLogic 里实现`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        // 禁止的「实现特征」：FNV 常量字面量、色号取模推导。剥注释后扫，KDoc 不误伤。
        val forbidden = listOf(
            Regex("0x811C9DC5", RegexOption.IGNORE_CASE),
            Regex("0x01000193", RegexOption.IGNORE_CASE),
            Regex("imul\\s*\\("),
            Regex("%\\s*${CourseColorLogic.PALETTE_SIZE}\\b"),
        )
        val scanRoots = listOf("features", "app").map { File(root, it) }
        scanRoots.forEach { base ->
            base.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/build/" !in it.invariantSeparatorsPath }
                .forEach { f ->
                    val code = stripComments(f.readText())
                    if (forbidden.any { it.containsMatchIn(code) }) {
                        offenders += f.relativeTo(root).invariantSeparatorsPath
                    }
                }
        }
        assertTrue(
            "发现色号哈希的第二实现（守卫 S3）——请改调 CourseColorLogic.of()：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `守卫S3 - 引用方必须走 import 而不是复制定义`() {
        val root = repoRoot()
        val bad = mutableListOf<String>()
        listOf("features", "app").map { File(root, it) }.forEach { base ->
            base.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/build/" !in it.invariantSeparatorsPath }
                .forEach { f ->
                    val code = stripComments(f.readText())
                    if ("CourseColorLogic" in code &&
                        "package cn.bit101.android.data.schedule" !in code
                    ) {
                        if (!Regex(
                                "import\\s+cn\\.bit101\\.android\\.data\\.schedule\\.CourseColorLogic",
                            ).containsMatchIn(code)
                        ) {
                            bad += f.relativeTo(root).invariantSeparatorsPath
                        }
                    }
                }
        }
        assertTrue(
            "以下文件提到了 CourseColorLogic 却没有 import（可能是手抄了实现）：\n" +
                bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }
}
