package cn.bit101.android.features.nfc.logic

/**
 * 「我的校园卡」—— 一次读卡认出的身份，存在本机，下次打开直接看。
 *
 * ## 为什么必须存下来
 *
 * 读一次卡要贴住手机好几秒，而且必须开着「扫文件区」那一轮。
 * 不存的话，用户每次想看自己的学号都得再贴一次 —— 那这个功能只能算演示，不算能用。
 *
 * ## ⚠️⚠️ 合并时 UID 对不上 ⇒ **整张替换**，不许逐字段拼
 *
 * 学生之间会互相贴卡，也可能换了新卡。把 A 卡的姓名和 B 卡的学号拼在一起
 * 是**张冠李戴** —— 用户会拿着这张名片去相信上面的名字，
 * 而这比「什么都不显示」糟得多（什么都不显示，用户至少知道要重新贴一次）。
 * ⇒ UID 两边都有且不一致时整张换成新读到的；只有某一侧缺 UID，才退化为逐字段补缺。
 *
 * ## ⚠️ 每个字段都可空，但记录本身照旧存在
 *
 * 换个学校的卡、或者这张卡某个文件被清空，认出来的可能只剩卡号。
 * 缺哪一行就省哪一行 —— 但**记录还在**，否则会被当成「没读过卡」，
 * 于是又得出「这功能不行」的结论（与「新区块不要有数据才渲染」同一条纪律）。
 *
 * ## 为什么编解码走 `Map` 而不是直接碰 `SharedPreferences`
 *
 * 这一层是**纯逻辑**：给它一张 Map、它还你一个对象，不依赖 Android。
 * 于是合并规则（这个模块里最容易写错、也最要紧的部分）可以在 JVM 上直接测。
 */
internal object SavedCardLogic {

    const val KEY_NAME = "card_name"
    const val KEY_STUDENT_ID = "card_student_id"
    const val KEY_SID_CONFIRMED = "card_sid_confirmed"
    const val KEY_CARD_NO = "card_card_no"
    const val KEY_UID = "card_uid"
    const val KEY_SAVED_AT = "card_saved_at"

    /**
     * 一张存下来的名片。
     *
     * @param uid 卡的物理编号（十六进制）。**它是「换没换卡」的唯一依据**，
     *   所以哪怕名片上别的字段都空着，也要尽量把它带上。
     * @param savedAt 上次更新这张名片的时间（epoch millis）。
     */
    data class SavedCard(
        val name: String?,
        val studentId: String?,
        /**
         * [studentId] 是不是**核对过**的。
         *
         * `false` = 只是从卡上猜出来的（见 `StudentIdScan.guess`），
         * 界面必须标「疑似」；`true` = 用户填的学号在卡里真命中了。
         *
         * ⚠️ 这两种成色必须分开存：只存一个字符串的话，名片上「学号 1120241355」
         * 到底是核对过的还是猜的，用户和我们事后都说不清。
         * 而按猜错的值去办事，责任就落到我们头上了。
         */
        val sidConfirmed: Boolean = false,
        val cardNo: String?,
        val uid: String?,
        val savedAt: Long = 0L,
    )

    /** 这张名片上有没有任何能展示的东西（全空 ⇒ 等于没读过卡）。 */
    fun isBlank(card: SavedCard?): Boolean {
        if (card == null) return true
        return card.name.isNullOrBlank() &&
            card.studentId.isNullOrBlank() &&
            card.cardNo.isNullOrBlank() &&
            card.uid.isNullOrBlank()
    }

    /**
     * 从持久化的键值里还原名片。
     *
     * @return 还原出来的名片；**四项全空时返回 `null`**（等于「没存过」）。
     *   为什么不返回一个全空的 `SavedCard`：那样 UI 分不清「没读过卡」和
     *   「读过但什么都没认出来」，而这两句提示是**不一样**的。
     */
    fun decode(raw: Map<String, *>): SavedCard? {
        val name = textOf(raw[KEY_NAME])
        val studentId = textOf(raw[KEY_STUDENT_ID])
        val cardNo = textOf(raw[KEY_CARD_NO])
        val uid = textOf(raw[KEY_UID])
        val savedAt = when (val value = raw[KEY_SAVED_AT]) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

        val sidConfirmed = when (val value = raw[KEY_SID_CONFIRMED]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: false
            else -> false
        }

        if (name == null && studentId == null && cardNo == null && uid == null) return null
        return SavedCard(
            name = name,
            studentId = studentId,
            // 老版本（没有这个键）存下来的学号，一律按「疑似」处理 —— 那时根本没有
            // 「是否核对过」这个概念，往宽松处猜（当成确认）会把猜测冒充成事实。
            sidConfirmed = sidConfirmed && studentId != null,
            cardNo = cardNo,
            uid = uid,
            savedAt = savedAt,
        )
    }

    /**
     * 名片 → 待写入的键值。
     *
     * ⚠️ `null` 的字段**不写**（而不是写空串）—— 写空串的话 [decode] 里
     * 「四项全空 ⇒ null」这条判据会失效，UI 就会显示一张全空的名片。
     */
    fun encode(card: SavedCard): Map<String, String> = buildMap {
        card.name?.let { put(KEY_NAME, it) }
        card.studentId?.let { put(KEY_STUDENT_ID, it) }
        // 只在**有学号**时写这个标记：没有学号却标着「已核对」是一种自相矛盾的状态
        if (card.studentId != null) put(KEY_SID_CONFIRMED, card.sidConfirmed.toString())
        card.cardNo?.let { put(KEY_CARD_NO, it) }
        card.uid?.let { put(KEY_UID, it) }
        put(KEY_SAVED_AT, card.savedAt.toString())
    }

    /**
     * 把**刚读到**的 [fresh] 并进已存的 [old]。
     *
     * @param now 当前时间（epoch millis）。由调用方传进来而不是这里取
     *   `System.currentTimeMillis()`：这样这条规则在 JVM 上可测、结果可复现。
     *
     * @return 合并后的名片。
     */
    fun merge(old: SavedCard?, fresh: SavedCard, now: Long): SavedCard {
        // ⚠️ 换卡了：整张替换。逐字段合并会把两张卡的信息拼成一个不存在的人。
        val sameCard = old == null || old.uid == null || fresh.uid == null || old.uid == fresh.uid
        if (!sameCard) return fresh.copy(savedAt = now)

        // 学号有两种成色，「确认过的」赢：
        // - 新值是核对过的那就用新的（用户刚比对过，以最新为准）；
        // - 旧值是核对过的、新值只是猜的 ⇒ **保留旧的**，别让一句猜测顶掉确认值；
        // - 都不是核对过的 ⇒ 有就用。
        val (studentId, sidConfirmed) = when {
            fresh.studentId != null && fresh.sidConfirmed -> fresh.studentId to true
            old?.studentId != null && old.sidConfirmed -> old.studentId to true
            fresh.studentId != null -> fresh.studentId to false
            old?.studentId != null -> old.studentId to false
            else -> null to false
        }

        return SavedCard(
            // 新值有才覆盖：这次没认出姓名，不该把上次认出的清掉
            name = fresh.name ?: old?.name,
            studentId = studentId,
            sidConfirmed = sidConfirmed,
            cardNo = fresh.cardNo ?: old?.cardNo,
            uid = fresh.uid ?: old?.uid,
            savedAt = now,
        )
    }

    /**
     * 两张名片的**内容**是否相同。
     *
     * ⚠️ 刻意**不比较** [SavedCard.savedAt]：那是「什么时候写的」，不是内容。
     * 拿它一起比的话，每次重算都会得出「变了」，于是输入框每敲一个字符就往盘上写一次 ——
     * 白白写盘，而且名片上那句「更新于」也跟着乱跳。
     */
    fun sameContent(a: SavedCard?, b: SavedCard?): Boolean =
        a?.name == b?.name &&
            a?.studentId == b?.studentId &&
            a?.sidConfirmed == b?.sidConfirmed &&
            a?.cardNo == b?.cardNo &&
            a?.uid == b?.uid

    private fun textOf(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }
}
