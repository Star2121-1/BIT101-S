package cn.bit101.android.features.nfc.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SavedCardLogic] 的单测。
 *
 * 这里真正要守住的是 [SavedCardLogic.merge]：**换卡必须整张替换**。
 * 逐字段合并会把 A 卡的姓名和 B 卡的学号拼成一个不存在的人，
 * 而用户会拿着这张名片去相信上面的名字 —— 这比什么都不显示糟得多。
 */
class SavedCardLogicTest {

    private fun card(
        name: String? = "高天翔",
        sid: String? = "1120241355",
        confirmed: Boolean = true,
        cardNo: String? = "7775F07B",
        uid: String? = "7775F07B",
        at: Long = 1000L,
    ) = SavedCardLogic.SavedCard(
        name = name,
        studentId = sid,
        sidConfirmed = confirmed,
        cardNo = cardNo,
        uid = uid,
        savedAt = at,
    )

    /** 存进去再读出来，得是同一张名片。 */
    @Test
    fun `编码再解码 内容与原来一致`() {
        val origin = card()
        assertEquals(origin, SavedCardLogic.decode(SavedCardLogic.encode(origin)))
    }

    /**
     * 四项全空 ⇒ `null`（等于没存过）。
     *
     * 为什么不能返回一个全空对象：那样 UI 分不清「没读过卡」与
     * 「读过但什么都没认出来」，而这两句提示对用户是**不一样的话**。
     */
    @Test
    fun `四项全空时 decode 返回 null`() {
        assertNull(SavedCardLogic.decode(emptyMap<String, String>()))
        assertNull(SavedCardLogic.decode(mapOf(SavedCardLogic.KEY_SAVED_AT to "123")))
        // 空串、纯空格都按「没有」处理，别写成一张全空的名片
        assertNull(
            SavedCardLogic.decode(
                mapOf(
                    SavedCardLogic.KEY_NAME to "",
                    SavedCardLogic.KEY_UID to "   ",
                )
            )
        )
    }

    /** `null` 字段不落盘：写空串会让上面那条「全空 ⇒ null」的判据失效。 */
    @Test
    fun `null 字段不写进存储`() {
        val encoded = SavedCardLogic.encode(card(name = null, cardNo = null))

        assertFalse(encoded.containsKey(SavedCardLogic.KEY_NAME))
        assertFalse(encoded.containsKey(SavedCardLogic.KEY_CARD_NO))
        assertTrue(encoded.containsKey(SavedCardLogic.KEY_UID))
        assertTrue(encoded.containsKey(SavedCardLogic.KEY_SAVED_AT))
    }

    /** `savedAt` 在 SharedPreferences 里是字符串，要能读回数字。 */
    @Test
    fun `savedAt 存成字符串也能读回来`() {
        val back = SavedCardLogic.decode(
            mapOf(
                SavedCardLogic.KEY_UID to "7775F07B",
                SavedCardLogic.KEY_SAVED_AT to "1700000000000",
            )
        )

        assertEquals(1700000000000L, back!!.savedAt)
    }

    /** 这次没认出来的字段，不能把上次认出来的清掉。 */
    @Test
    fun `这次没认出的字段 不覆盖上次认出的`() {
        val old = card(name = "高天翔", sid = "1120241355", at = 100)
        val fresh = SavedCardLogic.SavedCard(
            name = null,
            studentId = null,
            cardNo = "7775F07B",
            uid = "7775F07B",
        )

        val merged = SavedCardLogic.merge(old, fresh, now = 200)

        assertEquals("高天翔", merged.name)
        assertEquals("1120241355", merged.studentId)
        assertEquals(200L, merged.savedAt)
    }

    /** ⚠️ 这一条最要紧：换了卡就整张换，绝不拼两张卡。 */
    @Test
    fun `UID 不一致时整张替换 不拼两张卡`() {
        val old = card(name = "高天翔", sid = "1120241355", uid = "7775F07B")
        val fresh = SavedCardLogic.SavedCard(
            name = "李四",
            studentId = null,
            cardNo = "AAAAAAAA",
            uid = "AAAAAAAA",
        )

        val merged = SavedCardLogic.merge(old, fresh, now = 300)

        assertEquals("李四", merged.name)
        // 新卡没认出学号 ⇒ 学号就是没有，绝不能沿用上一张卡的
        assertNull(merged.studentId)
        assertEquals("AAAAAAAA", merged.uid)
    }

    /** 只有某一侧缺 UID 时，才退化成逐字段补缺。 */
    @Test
    fun `某一侧缺 UID 才退化为逐字段补缺`() {
        val old = card(name = "高天翔", sid = null, uid = null)
        val fresh = SavedCardLogic.SavedCard(
            name = null,
            studentId = "1120241355",
            cardNo = null,
            uid = "7775F07B",
        )

        val merged = SavedCardLogic.merge(old, fresh, now = 400)

        assertEquals("高天翔", merged.name)
        assertEquals("1120241355", merged.studentId)
        assertEquals("7775F07B", merged.uid)
    }

    /** 第一次贴卡：没有旧记录，直接用新读到的。 */
    @Test
    fun `没有旧记录时直接用新的`() {
        val merged = SavedCardLogic.merge(old = null, card(at = 0), now = 500)

        assertEquals(500L, merged.savedAt)
        assertEquals("7775F07B", merged.uid)
    }

    /** `isBlank` 只看四个展示字段 —— 它决定「要不要落盘」。 */
    @Test
    fun `isBlank 只看四个展示字段`() {
        assertTrue(SavedCardLogic.isBlank(null))
        assertTrue(
            SavedCardLogic.isBlank(
                SavedCardLogic.SavedCard(name = null, studentId = null, cardNo = null, uid = null)
            )
        )
        // 只剩 UID 也算有内容：至少知道「认出过这一张卡」
        assertFalse(
            SavedCardLogic.isBlank(
                SavedCardLogic.SavedCard(name = null, studentId = null, cardNo = null, uid = "7775F07B")
            )
        )
    }

    /**
     * ⚠️ **猜出来的学号不许顶掉核对过的那个**。
     *
     * 用户上次填了学号、真命中了（确认），这次没填、只有猜测 ——
     * 若让猜测覆盖上去，名片上的学号就从「确认」悄悄降级成「疑似」，
     * 而用户完全看不出来发生了什么。
     */
    @Test
    fun `疑似学号不覆盖已核对的学号`() {
        val old = card(sid = "1120241355", confirmed = true)
        val fresh = SavedCardLogic.SavedCard(
            name = null,
            studentId = "1120241399",
            sidConfirmed = false,
            cardNo = null,
            uid = "7775F07B",
        )

        val merged = SavedCardLogic.merge(old, fresh, now = 600)

        assertEquals("1120241355", merged.studentId)
        assertTrue(merged.sidConfirmed)
    }

    /** 反过来：用户这次真的核对上了，就该把猜测升格成确认。 */
    @Test
    fun `核对的学号可以覆盖之前的疑似`() {
        val old = card(sid = "1120241355", confirmed = false)
        val fresh = card(sid = "1120241355", confirmed = true)

        val merged = SavedCardLogic.merge(old, fresh, now = 700)

        assertEquals("1120241355", merged.studentId)
        assertTrue(merged.sidConfirmed)
    }

    /**
     * 「内容相同」**不看保存时间**。
     *
     * 这条守的是写盘次数：页面上的核对是「输入框一变就重算」，
     * 若把 `savedAt` 也算进内容比较，每敲一个字符都会得出「变了」并写一次盘。
     */
    @Test
    fun `sameContent 忽略保存时间 但看得出内容差异`() {
        assertTrue(SavedCardLogic.sameContent(card(at = 100), card(at = 999)))
        assertFalse(SavedCardLogic.sameContent(card(), card(name = "李四")))
        // 「是否核对过」也算内容：它决定了界面上写「学号」还是「疑似学号」
        assertFalse(SavedCardLogic.sameContent(card(confirmed = true), card(confirmed = false)))
        assertFalse(SavedCardLogic.sameContent(card(), null))
        assertTrue(SavedCardLogic.sameContent(null, null))
    }

    /** 「核对了哪个」这个事实也要能存下来、读回来。 */
    @Test
    fun `是否核对过会一起存下来`() {
        val suspicious = card(confirmed = false)
        val back = SavedCardLogic.decode(SavedCardLogic.encode(suspicious))

        assertEquals(suspicious, back)
        assertFalse(back!!.sidConfirmed)
    }
}
