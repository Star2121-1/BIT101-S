package cn.bit101.android.features.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态缓存判定（[EclassActivityCache]）的单测。
 *
 * 锁的是「什么时候可以省掉那次网络请求」——判错的代价是二选一：
 * 判得太宽松 → 用户看不到新动态；判得太严 → 每次点页签都打一轮请求（原来的毛病）。
 * 另外**时钟回拨**必须按过期处理：那种情况下时间基准已经不可信。
 */
class EclassActivityCacheTest {

    private val ttl = 10 * 60_000L
    private val cache = EclassActivityCache(ttl)

    private fun entryAt(millis: Long) = EclassActivityCache.Entry(millis, "动态")

    @Test
    fun `没有缓存时不算新鲜`() {
        assertFalse(cache.isFresh(null, nowMillis = 1_000_000))
    }

    @Test
    fun `刚取到的算新鲜`() {
        assertTrue(cache.isFresh(entryAt(1_000_000), nowMillis = 1_000_000))
        assertTrue(cache.isFresh(entryAt(1_000_000), nowMillis = 1_000_001))
    }

    @Test
    fun `正好到 TTL 仍算新鲜，超过 1 毫秒就不算`() {
        assertTrue(cache.isFresh(entryAt(1_000_000), nowMillis = 1_000_000 + ttl))
        assertFalse(cache.isFresh(entryAt(1_000_000), nowMillis = 1_000_000 + ttl + 1))
    }

    /** ⚠️ 用户把系统时间往回改之后，`now - fetchedAt` 会是负数 → 必须当作过期。 */
    @Test
    fun `时钟回拨按过期处理`() {
        assertFalse(cache.isFresh(entryAt(2_000_000), nowMillis = 1_000_000))
        assertFalse(cache.isFresh(entryAt(1_000_000), nowMillis = 999_999))
    }

    @Test
    fun `默认 TTL 是十分钟`() {
        assertEquals(10 * 60_000L, EclassActivityCache.DEFAULT_TTL_MILLIS)
    }
}
