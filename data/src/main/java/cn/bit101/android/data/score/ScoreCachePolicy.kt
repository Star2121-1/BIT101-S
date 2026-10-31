package cn.bit101.android.data.score

/**
 * 成绩表缓存的取舍逻辑。
 *
 * ## 为什么单独抽出来
 *
 * 这段判断有两个**不能错**的地方，而它们都藏在 IO 里（`DefaultScoreRepo.load`）——
 * 真机上第一次写错就直接把用户的成绩抹掉了，发现不了。抽成纯函数才能写测试盯着。
 *
 * ## 它防的是什么
 *
 * 教务「成绩查询」页（`cjcx_list`）在**没完成评教**时不给表，认证主机就回一个
 * 两字节的空数组 `[]`。而 `[]` 在 `ScoreTable.parse` 里是**合法的空表**（那是对的，
 * 本学期没出分时它也是 `[]`）。
 *
 * 于是有一条很隐蔽的路径：用户历史上有 50 门成绩 → 缓存里存着 → 某次评教没做，
 * 拉到 `[]` → 当成「最新事实」写进缓存 → **50 门成绩从页面上消失**，而且下次
 * 还是空。用户看到的是「我的成绩没了」，实际什么都没坏。
 *
 * ⇒ 空表**不能覆盖**非空缓存，而且**自己也不进缓存**（进了会让下次判断把「空」
 * 当基线，污染得更彻底）。
 */
internal object ScoreCachePolicy {

    /** 成绩表缓存的新鲜期：30 分钟内不重复登录取数。 */
    const val FRESH_MS = 30 * 60 * 1000L

    /** 空表出现时给用户看的话 —— 不带分数，用户自定的隐私边界。 */
    const val EMPTY_TABLE_NOTE =
        "教务这次一门成绩都没返回（多半是还没评教），没覆盖掉上次取到的"

    /**
     * 要不要真的打网。
     *
     * [force] 为 true（用户主动刷新 / 点「重新获取」）时**必须**打网 —— 否则
     * 用户点了半天没有任何反应，还以为是按钮坏了。
     */
    fun shouldFetch(force: Boolean, cached: Pair<ScoreTable.Table, Long>?, nowMillis: Long): Boolean {
        if (force) return true
        if (cached == null) return true
        return nowMillis - cached.second >= FRESH_MS
    }

    /** 拿到新表之后怎么落地。 */
    sealed interface Merge {
        /**
         * 用新表。
         *
         * @param persist 要不要写进缓存。[false] 只发生在「新表是空的、手里也没有
         *   非空旧表」时 —— 空表不进缓存。
         */
        data class UseFresh(val table: ScoreTable.Table, val persist: Boolean) : Merge

        /**
         * 新表是空的，而手里有非空旧表 ⇒ **用旧的**，缓存保持原样（不覆盖）。
         *
         * @param note 为什么没用新数据（给用户的说明）
         */
        data class KeepCached(val note: String) : Merge
    }

    fun merge(fresh: ScoreTable.Table, cached: Pair<ScoreTable.Table, Long>?): Merge {
        if (fresh.rows.isNotEmpty()) return Merge.UseFresh(fresh, persist = true)
        // 新表为空：手里有非空的旧表就守住它
        val cachedTable = cached?.first
        if (cachedTable != null && cachedTable.rows.isNotEmpty()) {
            return Merge.KeepCached(EMPTY_TABLE_NOTE)
        }
        // 都没有 ⇒ 就是空的，但别把空表写进缓存
        return Merge.UseFresh(fresh, persist = false)
    }
}
