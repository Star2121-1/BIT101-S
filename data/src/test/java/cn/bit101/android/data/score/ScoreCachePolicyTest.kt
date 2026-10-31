package cn.bit101.android.data.score

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScoreCachePolicy] 的契约测试。
 *
 * 盯的是「空表会不会把已有成绩抹掉」这条 —— 真机上发生了用户只会看到
 * 「我的成绩没了」，而日志里一切正常。所以规则在这里用测试钉死。
 */
class ScoreCachePolicyTest {

    private fun table(vararg rows: List<String>): ScoreTable.Table {
        val header = listOf(
            "序号", "开课学期", "课程编号", "课程名称", "成绩", "平均分",
            "学分", "课程性质", "本人成绩在班级中占", "本人成绩在专业中占",
        )
        val all = listOf(header) + rows
        val json = all.joinToString(",", "[", "]") { row ->
            row.joinToString(",", "[", "]") { "\"$it\"" }
        }
        return ScoreTable.parse(JsonParser.parseString(json))!!
    }

    /** 教务没给表时认证主机真实回的东西：两字节的空数组。 */
    private fun emptyTable(): ScoreTable.Table =
        ScoreTable.parse(JsonParser.parseString("[]"))!!

    private val oneCourse = {
        table(listOf("1", "2025-2026-2", "MA10001", "高等数学A", "90", "82", "4", "必修", "10%", "25%"))
    }

    // ---- shouldFetch ----

    @Test
    fun 缓存新鲜期_未过期不打网() {
        val cached = oneCourse() to 1_000L
        assertFalse(
            ScoreCachePolicy.shouldFetch(force = false, cached = cached, nowMillis = 1_000L + 60_000L),
        )
    }

    @Test
    fun 缓存新鲜期_过期要打网() {
        val cached = oneCourse() to 1_000L
        assertTrue(
            ScoreCachePolicy.shouldFetch(
                force = false,
                cached = cached,
                nowMillis = 1_000L + ScoreCachePolicy.FRESH_MS,
            ),
        )
    }

    @Test
    fun 缓存新鲜期_用户主动刷新时即使刚拉过也要打网() {
        val cached = oneCourse() to 1_000L
        assertTrue(ScoreCachePolicy.shouldFetch(force = true, cached = cached, nowMillis = 1_000L))
    }

    @Test
    fun 缓存新鲜期_没有缓存必须打网() {
        assertTrue(ScoreCachePolicy.shouldFetch(force = false, cached = null, nowMillis = 0L))
    }

    // ---- merge ----

    @Test
    fun 合并_新表非空时用新表并写缓存() {
        val fresh = oneCourse()
        val m = ScoreCachePolicy.merge(fresh, cached = null) as ScoreCachePolicy.Merge.UseFresh
        assertEquals(fresh, m.table)
        assertTrue(m.persist)
    }

    @Test
    fun 合并_空表不覆盖非空缓存() {
        val cached = oneCourse() to 1_000L
        val m = ScoreCachePolicy.merge(emptyTable(), cached)

        assertTrue("必须走 KeepCached，否则会把已有成绩抹掉", m is ScoreCachePolicy.Merge.KeepCached)
        assertEquals(ScoreCachePolicy.EMPTY_TABLE_NOTE, (m as ScoreCachePolicy.Merge.KeepCached).note)
    }

    /**
     * 空表且没有旧缓存 ⇒ **必须写盘**。
     *
     * 早先这里是 `persist = false`，理由是「空表会污染下次判断」。那个理由站不住
     * （守旧缓存的条件是 `rows.isNotEmpty()`，空缓存不会被误当成有数据可守），
     * 而代价极大：没出分/没评教的用户每次进成绩页都要走一遍学校 SSO 登录。
     */
    @Test
    fun 合并_空表且没有缓存时也要写盘以压住重复登录() {
        val m = ScoreCachePolicy.merge(emptyTable(), cached = null) as ScoreCachePolicy.Merge.UseFresh
        assertTrue(m.table.rows.isEmpty())
        assertTrue("空表必须落盘，否则每次打开页面都重新登录一次学校", m.persist)
    }

    /** 空表落盘之后，30 分钟内再打开就不该再打网。 */
    @Test
    fun 合并_空表落盘后新鲜期内不再打网() {
        val empty = emptyTable()
        val at = 1_000L
        assertFalse(
            "空结果也有新鲜期，否则每次进页面都重新登录",
            ScoreCachePolicy.shouldFetch(force = false, cached = empty to at, nowMillis = at + 60_000L),
        )
    }

    @Test
    fun 合并_缓存本身也是空表时仍按空表处理并刷新时间戳() {
        val m = ScoreCachePolicy.merge(emptyTable(), emptyTable() to 1_000L)
            as ScoreCachePolicy.Merge.UseFresh
        assertTrue("仍然是空，但时间戳要更新，才算「刚查过」", m.persist)
    }

    @Test
    fun 合并_新表非空时即使缓存非空也换成新表() {
        val cached = oneCourse() to 1_000L
        val fresh = table(
            listOf("1", "2025-2026-2", "MA10001", "高等数学A", "95", "85", "4", "必修", "5%", "10%"),
            listOf("2", "2025-2026-2", "CS20002", "数据结构", "88", "80", "3", "必修", "15%", "30%"),
        )
        val m = ScoreCachePolicy.merge(fresh, cached) as ScoreCachePolicy.Merge.UseFresh
        assertEquals(2, m.table.rows.size)
        assertTrue(m.persist)
    }
}
