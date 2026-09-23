package cn.bit101.android.features.widget

/**
 * 延河课堂「动态」数据的**短时缓存判定**（纯逻辑，可单测）。
 *
 * ## 为什么需要它
 *
 * 组件的每一次重绘都会走一遍 `WidgetRepository.load()`，而动态数据来自**网络**
 * （1 次课程列表 + 每门课 1 次 activities）。重绘的触发点却很密：
 * 点页签、点 DDL 勾选、拖动改尺寸、系统 onUpdate、周期任务……
 * 于是「切个页签」也要等 N+1 个 HTTP 请求 —— 网络差时组件像卡住，
 * 还白白耗流量（实测前：点一次页签 = 一轮完整请求）。
 *
 * 规则：TTL 之内直接复用上一次结果；`force = true`（用户点「刷新」键、
 * 周期任务开始时）无视 TTL 重新拉。
 *
 * ⚠️ 只判「这份数据还算不算新鲜」，不负责并发 —— 并发由
 * [WidgetRepository] 的锁保证，这里保持纯函数以便单测。
 */
internal class EclassActivityCache(private val ttlMillis: Long) {

    /** 缓存条目：取数时刻 + 取到的值。 */
    data class Entry<T>(val fetchedAtMillis: Long, val value: T)

    /**
     * [entry] 在 [nowMillis] 这一时刻还算新鲜吗。
     *
     * ⚠️ 时钟回拨（用户改系统时间）会让 `now - fetchedAt` 变成负数 ——
     * 那说明时间基准已经不可信，**按过期处理**（多拉一次总比重放一份
     * 时间戳在未来的数据安全）。
     */
    fun isFresh(entry: Entry<*>?, nowMillis: Long): Boolean {
        if (entry == null) return false
        val ageMillis = nowMillis - entry.fetchedAtMillis
        return ageMillis in 0..ttlMillis
    }

    companion object {
        /**
         * 默认 TTL：10 分钟。
         *
         * 取值的依据：动态是「最近发生了什么」，10 分钟的陈旧度用户感知不到；
         * 而周期刷新是 30 分钟一次、用户还能自己点「刷新」键强制更新，
         * 所以这个上限不会让页面长期停在旧数据上。
         */
        const val DEFAULT_TTL_MILLIS = 10 * 60_000L
    }
}
