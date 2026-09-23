package cn.bit101.android.config.setting.base

/**
 * App 课表页（「卷」）里各 tab 的**下标**。
 *
 * ## 为什么要有这个常量
 *
 * 桌面组件点条目时，要让 App 打开后**停在对的那个 tab**（DDL 行 → DDL tab、
 * 动态行 → 动态 tab）。tab 下标由 `ScheduleScreen` 里那个 `items` 列表的顺序决定，
 * 而组件模块（`features:widget`）**不依赖** `features:schedule`，拿不到那份列表 ——
 * 所以顺序约定放在 `config` 里，两边都引用它，避免各自写魔法数字后悄悄错位。
 *
 * ⚠️ **改 `ScheduleScreen` 的 tab 顺序时，必须同步改这里**（两边都在注释里互相指认）。
 * 顺序本来就变过一次：动态从最后挪到 DDL 后面（2026-09-23 用户要求）。
 */
object ScheduleTabs {

    /** 课表（第一页，默认） */
    const val COURSE = 0

    /** DDL / 待办 */
    const val DDL = 1

    /** 延河课堂动态 */
    const val ACTIVITY = 2

    /** 空教室查询 */
    const val FREE_CLASSROOM = 3

    /** tab 总数（越界校验用）。 */
    val COUNT = 4

    /** 是合法下标吗。 */
    fun isValid(index: Int?): Boolean = index != null && index in 0 until COUNT
}
