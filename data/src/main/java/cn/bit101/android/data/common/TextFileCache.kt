package cn.bit101.android.data.common

import android.content.Context
import java.io.File

/**
 * 「一个小文件存一份文本」的读写骨架 —— **全 App 只有这一份**。
 *
 * ## 为什么单独抽出来（2026-09-30 重复代码审计）
 *
 * 这段骨架此前在 5 个 Store 里各写了一遍（教学调整 / 成绩检查 / 一卡通余额 /
 * 校园网流量 / 座位违约台账）。真正要紧的不是行数，而是它的**语义** ——
 * 抄 5 遍就容易各自演化：
 * - **读失败返回 null**（文件不存在、内容损坏、IO 异常）⇒ 调用方当作"没有缓存"，
 *   **绝不抛**。缓存读不出来不该让页面挂掉。
 * - **写失败静默**（磁盘满、权限）⇒ 缓存写不进去不该让用户的操作失败。
 *
 * ## 落盘格式（别改）
 *
 * 这些缓存都是**按行、`|` 分隔**的纯文本（`adb shell cat files/…` 能直接看，
 * 坏了删掉重来）。所以任何要落盘的文本都必须先过 [sanitizeField]。
 */
object TextFileCache {

    /** 读：**失败一律返回 null**。 */
    fun read(context: Context, fileName: String): String? =
        runCatching { File(context.filesDir, fileName).readText() }.getOrNull()

    /** 写：**失败静默**。 */
    fun write(context: Context, fileName: String, text: String) {
        runCatching { File(context.filesDir, fileName).writeText(text) }
    }

    /**
     * 落盘前的字段净化：换行 → 空格、`|` → `/`，去掉首尾空白。
     *
     * ⚠️ **别改成"转义"**（如把 `|` 写成 `\|`）：解析侧是 `split('|')`，而且
     * **磁盘上已经有旧格式的数据**，换成转义会让旧数据解析错位。
     * "把危险字符换成别的字符"最简单、也不会跟历史数据打架。
     *
     * ⚠️ 这个函数的必要性：文本里出现一个 `|` 就会让整行**字段错位**，
     * 而且错得很安静（解析出来的字段还在，只是全都串位了）。
     */
    fun sanitizeField(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim()
}
