package cn.bit101.android.config.common

/**
 * 「定位到哪一条」的键格式 —— 组件点条目跳进 App 后，用来滚到那一条。
 *
 * ## 为什么要带前缀
 *
 * 键要在**组件**（`features:widget`）里生成、在**App 的列表**里消费。
 * 而课表页里的 DDL 页与动态页**同时存在**（Pager 会预组合相邻页），
 * 两个列表都会收到同一个键 —— 如果键本身不自描述，DDL 列表会「先到先得」
 * 把动态的键消费掉，用户点动态条目就定位不到（甚至跳错）。
 *
 * 所以键带上归属：`ddl:<uid>` / `activity:<id>`，各列表只认自己那一种。
 *
 * ## 为什么放 config
 *
 * 生成方在 `features:widget`、消费方在 `features:schedule`，两边都依赖 `config`。
 * （同 [ScheduleTabs] 的理由。）
 */
object FocusKeys {

    private const val DDL_PREFIX = "ddl:"
    private const val ACTIVITY_PREFIX = "activity:"

    /** DDL 的定位键（uid 即 `DDLScheduleEntity.uid`）。 */
    fun ddl(uid: String): String = DDL_PREFIX + uid

    /** 延河课堂动态的定位键（id 即 `EclassActivity.id`）。 */
    fun activity(id: String): String = ACTIVITY_PREFIX + id

    /** 这条键是不是 DDL 的；是则返回去掉前缀后的 uid。 */
    fun ddlTarget(key: String?): String? =
        key?.takeIf { it.startsWith(DDL_PREFIX) }?.removePrefix(DDL_PREFIX)

    /** 这条键是不是动态的；是则返回去掉前缀后的 id。 */
    fun activityTarget(key: String?): String? =
        key?.takeIf { it.startsWith(ACTIVITY_PREFIX) }?.removePrefix(ACTIVITY_PREFIX)
}
