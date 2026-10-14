package cn.bit101.android.data.schedule

/**
 * 课程 → 12 色号的**唯一出处**（规范 §8.2：`i = fnv1a32(课名) % 12`）。
 *
 * ## 为什么落 `:data` 而不是 `features/theme`
 * 同一门课的颜色要跨**四个面**一致：课表网格、今天时间线/抽屉、桌面组件（RemoteViews）、
 * 通知。前两个在 Compose 里能拿 `GridTokens`，后两个在宿主进程渲染、拿不到我们的
 * CompositionLocal —— 它们只能吃**可重算的 Int**。所以「名字 → 色号」这个纯函数
 * 必须放在所有人都能依赖、且不依赖任何 UI 的层，就是 `:data`。
 *
 * ⚠️⚠️ **全仓只许这一份哈希实现**（实施计划守卫 S3：三处各写一份哈希是 v1.11.9 栽过的坑）。
 * `CourseColorLogicTest` 里有源码扫描守卫：`features/`、`app/` 下任何文件（注释除外）
 * 出现 FNV 常量或 `% 12` 色号推导都会测试变红。
 *
 * 配色本身（色号 i 显示成什么）见 `features/theme` 的 `CoursePalette` / `GridTokens.course(i)`：
 * 那边**只查表，不含哈希**，两层职责不重叠。
 *
 * ## 与规范演示数据的一致性
 * 规范 §9.1 的「课名 → 色号」对照表是用同一算法在 JS 里算的（`Math.imul` + `>>>0`），
 * 与本实现的 32 位回绕语义逐位一致 —— 单测里固化了那张表（14 门课、12 色全覆盖、
 * 周三「数据结构 & 大学英语(三)」同落色号 4 的撞色组），算法一变立刻红。
 */
object CourseColorLogic {

    /** 色板档数（规范 §8.2 权威 12 支）。 */
    const val PALETTE_SIZE = 12

    /** FNV-1a 32 位初始偏移（0x811C9DC5）。 */
    private const val FNV_OFFSET = 0x811C9DC5L

    /** FNV-1a 32 位质数（0x01000193）。 */
    private const val FNV_PRIME = 0x01000193L

    /**
     * 课名 → 色号（0..11）。
     *
     * - 输入按 UTF-16 码元逐个喂进 FNV-1a（与规范演示数据的 JS 实现 `charCodeAt` 同口径）；
     * - 空串/纯空白也返回确定值（不是 -1）—— 调用方不需要判空，颜色永远可算；
     * - 结果对同一名字**永远相同**：不随设备、时间、语言变（这是「跨端同色」的前提）。
     */
    fun of(name: String): Int = (fnv1a32(name) % PALETTE_SIZE).toInt()

    /** FNV-1a 32 位，返回**无符号** 32 位值（Long 承载）。 */
    fun fnv1a32(input: String): Long {
        var h = FNV_OFFSET
        for (ch in input) {
            // UTF-16 码元（0..65535），零扩展 —— 与 JS `charCodeAt` 一致
            h = h xor (ch.code.toLong())
            // Long 乘完截 32 位 = 32 位回绕乘（JS Math.imul 同语义）
            h = (h * FNV_PRIME) and 0xFFFFFFFFL
        }
        return h
    }
}
