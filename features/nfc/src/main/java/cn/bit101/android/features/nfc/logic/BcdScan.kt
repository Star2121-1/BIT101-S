package cn.bit101.android.features.nfc.logic

/**
 * 在一段裸字节里找**疑似 BCD 日期时间**。
 *
 * ## 为什么需要它 —— 真卡实测
 *
 * 北理工校园卡 `SELECT 0018` 的记录文件里读回来 23 字节，其中有一段是
 * `20 26 03 22 11 08 43`。这不是乱码，是 **BCD 编码的 2026-03-22 11:08:43** ——
 * 但光看十六进制，用户和我们都不会意识到那是时间。
 *
 * 卡里的业务数据大量用 BCD 存日期（有效期、交易流水），识别出来比让人
 * 自己把 `20 26` 心算成 2026 要强得多。
 *
 * ## ⚠️ 这是**疑似**，不是结论
 *
 * 三个字节里凑出一个合法的 `HH:MM:SS` 并不难（`01 03 05` 就同时像终端号和时间），
 * 所以本对象刻意做了三重收紧，宁可漏也不误报：
 *
 * 1. **年份窗口**（默认 2020~2035）：校园卡上出现的日期要么是近期交易，
 *    要么是 4~6 年后的有效期。像 `00 01 14` 解成 2000-01-14 这种一律不报。
 * 2. **重叠只留一个**：`20 26 03 22` 里第 2~4 字节本身也能解成 `26 03 22`，
 *    两条都报只会让人以为卡里存了两个日期 —— 位置重叠时只留 4 字节那条。
 * 3. **时间只做「后缀」**：不单独报时间（`01 03 05` 这种噪声太大），
 *    只在它**紧挨着**一个已确认的日期时才附上去。且 `00:00:00` 不附 ——
 *    那不是时间，是没写过的空位。
 *
 * 界面展示时必须带「疑似」二字，不能写成卡里的既成事实。
 */
internal object BcdScan {

    /**
     * 一处命中。
     *
     * @param at 起始偏移（从这段数据的第 0 字节算起）。
     * @param length 占几个字节（3 = `YYMMDD`，4 = `YYYYMMDD`；附了时间时按日期部分的长度算）。
     * @param text 给人看的文本，如 `2026-03-22` 或 `2026-03-22 11:08:43`。
     */
    data class Found(
        val at: Int,
        val length: Int,
        val text: String,
    ) {
        /** 带偏移的一行字：`偏移 16：2026-03-22 11:08:43`。 */
        val display: String get() = "偏移 $at：$text"
    }

    /**
     * 年份窗口。定这个区间是因为校园卡上真正有意义的日期都离「现在」不远
     * （交易流水是近期的、有效期是往后几年的），窗口外的一律当噪声丢掉。
     */
    const val DEFAULT_MIN_YEAR = 2020
    const val DEFAULT_MAX_YEAR = 2035

    /**
     * 扫一遍 [bytes]，按偏移从小到大给出所有疑似日期。
     *
     * @return 没有命中时返回**空列表**（不是 `null`）—— UI 直接 `forEach` 就行，
     *   调用方不需要额外判空。
     */
    fun scan(
        bytes: ByteArray,
        minYear: Int = DEFAULT_MIN_YEAR,
        maxYear: Int = DEFAULT_MAX_YEAR,
    ): List<Found> {
        val out = mutableListOf<Found>()

        // ① 四字节 YYYYMMDD（首字节是世纪 19/20）。这种最长也最可靠，先扫。
        for (i in 0..bytes.size - 4) {
            val day = date4(bytes, i, minYear, maxYear) ?: continue
            out += Found(at = i, length = 4, text = day + timeSuffix(bytes, i + 4))
        }

        // ② 三字节 YYMMDD（年份隐含 20xx）。与①位置有重叠的一律跳过 ——
        //    那是同一天的另一种读法，报两条反而像是在说卡里有两个日期。
        for (i in 0..bytes.size - 3) {
            if (out.any { i < it.at + it.length && it.at < i + 3 }) continue
            val day = date3(bytes, i, minYear, maxYear) ?: continue
            out += Found(at = i, length = 3, text = day + timeSuffix(bytes, i + 3))
        }

        return out.sortedBy { it.at }
    }

    /** 紧跟在日期后面的 `HH:MM:SS`；不够长、不合法、或恰好是 `00:00:00` 时返回空串。 */
    private fun timeSuffix(bytes: ByteArray, at: Int): String {
        val t = time3(bytes, at) ?: return ""
        return " $t"
    }

    private fun time3(bytes: ByteArray, at: Int): String? {
        val h = bcd(bytes, at) ?: return null
        val m = bcd(bytes, at + 1) ?: return null
        val s = bcd(bytes, at + 2) ?: return null
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
        // 全零不是「零点」，是文件里没写过的空位 —— 附上去只会误导。
        if (h == 0 && m == 0 && s == 0) return null
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun date4(bytes: ByteArray, at: Int, minYear: Int, maxYear: Int): String? {
        val century = bcd(bytes, at) ?: return null
        if (century != 19 && century != 20) return null
        val yy = bcd(bytes, at + 1) ?: return null
        val mm = bcd(bytes, at + 2) ?: return null
        val dd = bcd(bytes, at + 3) ?: return null
        return format(century * 100 + yy, mm, dd, minYear, maxYear)
    }

    private fun date3(bytes: ByteArray, at: Int, minYear: Int, maxYear: Int): String? {
        val yy = bcd(bytes, at) ?: return null
        val mm = bcd(bytes, at + 1) ?: return null
        val dd = bcd(bytes, at + 2) ?: return null
        return format(2000 + yy, mm, dd, minYear, maxYear)
    }

    private fun format(year: Int, month: Int, day: Int, minYear: Int, maxYear: Int): String? {
        if (year !in minYear..maxYear) return null
        if (month !in 1..12) return null
        if (day !in 1..daysIn(year, month)) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    /** 二月要按闰年算，否则 2028-02-29 这种合法日期会被我们当噪声丢掉。 */
    private fun daysIn(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        else -> 0
    }

    /**
     * 取一个字节的 BCD 值。
     *
     * 任一半字节 > 9 就不是 BCD（那个字节可能是长度、标志位或 ASCII），返回 `null`。
     * 这一条把绝大部分误报挡在外面：真正的二进制字段很少两个半字节都落在 0~9。
     */
    private fun bcd(bytes: ByteArray, at: Int): Int? {
        if (at !in bytes.indices) return null
        val v = bytes[at].toInt() and 0xFF
        val hi = v shr 4
        val lo = v and 0x0F
        if (hi > 9 || lo > 9) return null
        return hi * 10 + lo
    }
}
