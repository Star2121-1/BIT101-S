package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardIdentityLogic] 的单测。
 *
 * 重点盯两件容易错的事：
 * ① **缺字段时卡片不能整个消失**（只省掉那一行）
 * ② **日期只照抄、不命名** —— 卡上同时有有效期与交易时间，代码无权替用户判断哪个是哪个。
 */
class CardIdentityLogicTest {

    private fun line(
        text: String? = null,
        hit: String? = null,
        dates: List<String> = emptyList(),
    ) = object : CardIdentityLogic.ProbeLineLike {
        override val text = text
        override val studentIdHit = hit
        override val dates = dates
    }

    // ⚠️ CardNoCandidate 是 **top-level** 声明，不在 NfcCardLogic 里 ——
    // 写成 NfcCardLogic.CardNoCandidate 会报「Unresolved reference」。
    private val cards = listOf(
        CardNoCandidate("正序（十六进制）", "7775F07B"),
        CardNoCandidate("反序（十进制拼接）", "123240117119"),
    )

    /** 真卡那一次贴卡：姓名 + 学号 + 卡号 + 两类日期，都要汇出来。 */
    @Test
    fun `真卡上身份三项齐全`() {
        val id = CardIdentityLogic.summarize(
            probeLines = listOf(
                line(text = "高天翔"),
                line(dates = listOf("偏移 24：2028-08-30")),
                line(hit = "分段形式（3 段，2 个分隔符），偏移 10"),
            ),
            candidates = cards,
            expectedStudentId = "1120241355",
        )

        assertEquals("高天翔", id.name)
        assertEquals("1120241355", id.studentId)
        assertEquals("7775F07B", id.cardNo)
        assertEquals(1, id.dates.size)
    }

    /**
     * ⚠️ 这是最要紧的一条：**字段缺失时卡片本身必须还在**。
     *
     * 换成别的学校的卡、或者这张卡某个文件被清空，用户看到的应该是
     * 「这张卡只认出了卡号」，而不是整块空白 —— 后者会被当成「没读到」。
     */
    @Test
    fun `只有卡号时 其余各行留空但卡片仍在`() {
        val id = CardIdentityLogic.summarize(
            probeLines = emptyList(),
            candidates = cards,
        )

        assertNull(id.name)
        assertNull(id.studentId)
        assertTrue(id.dates.isEmpty())
        assertEquals("7775F07B", id.cardNo)
    }

    /** 探测失败（probe 为 null）也不能崩。 */
    @Test
    fun `探测没跑起来也不崩`() {
        val id = CardIdentityLogic.summarize(
            probeLines = null,
            candidates = emptyList(),
        )

        assertNull(id.name)
        assertNull(id.cardNo)
    }

    /**
     * ⚠️ 日期一律照抄、不做筛选也不排序。
     *
     * 真卡上 `2028-08-30`（更像有效期）与 `2026-03-22`（交易时间）同时存在，
     * 「取最晚的当有效期」只是猜测。这里守住：进几条就出几条，顺序不变。
     */
    @Test
    fun `两类日期都照收 不替用户判断哪个是有效期`() {
        val id = CardIdentityLogic.summarize(
            probeLines = listOf(
                line(dates = listOf("偏移 24：2028-08-30")),
                line(dates = listOf("偏移 16：2026-03-22 11:08:43")),
            ),
            candidates = cards,
        )

        assertEquals(2, id.dates.size)
        assertTrue(id.dates.any { it.contains("2028-08-30") })
        assertTrue(id.dates.any { it.contains("11:08:43") })
    }

    /** 同一段文本重复出现时只报一次（探测会连读几段，缓冲区重叠很常见）。 */
    @Test
    fun `重复的姓名只算一次`() {
        val id = CardIdentityLogic.summarize(
            probeLines = listOf(line(text = "高天翔"), line(text = "高天翔")),
            candidates = cards,
        )

        assertEquals("高天翔", id.name)
    }

    /**
     * ⚠️ `studentId` **只能是学号数字**，不能是「怎么认出来的」说明文字。
     *
     * 原写法是 `expectedStudentId ?: it` —— 没填学号时这个字段会变成
     * 「分段形式（3 段，2 个分隔符），偏移 10」这样一句描述。
     * 而「我的校园卡」要把这个字段**存进本机**，存成一句描述就全错了：
     * 报给人看 / 存下来的值，必须和它的字面含义对齐。
     */
    @Test
    fun `没填学号时 学号字段就是 null 而不是命中说明`() {
        val id = CardIdentityLogic.summarize(
            probeLines = listOf(line(hit = "分段形式（3 段，2 个分隔符），偏移 10")),
            candidates = cards,
        )

        assertNull(id.studentId)
        assertEquals("分段形式（3 段，2 个分隔符），偏移 10", id.studentIdNote)
    }

    /** 卡号有多种解读时要说明，不能只报一种让人误以为那就是卡面号。 */
    @Test
    fun `卡号有多种解读要注明`() {
        val id = CardIdentityLogic.summarize(
            probeLines = emptyList(),
            candidates = cards,
        )

        assertTrue(id.cardNoNote!!.contains("2 种解读"))
    }
}
