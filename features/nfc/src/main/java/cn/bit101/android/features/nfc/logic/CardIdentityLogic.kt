package cn.bit101.android.features.nfc.logic

/**
 * 把散落在几百行探测结果里的**身份信息**汇总成一张「这张卡是谁」的卡片。
 *
 * ## 它解决什么
 *
 * 学号、姓名原本只在 `ProbeLine` 里各占一行 —— 用户贴一次卡，得在几百行
 * 「找不到这个文件」的流水里自己翻找那一条 `★ 命中学号`。
 * 找到了也不好复制、不好用。所以要把它们**提到最上面**。
 *
 * ## ⚠️ 为什么日期只叫「卡上日期」，不叫「有效期」
 *
 * 真卡上出现过两类日期，目前**没有可靠办法区分**：
 *
 * | 出处 | 值 | 更像什么 |
 * |---|---|---|
 * | 文件 `0015` | `2028-08-30` | 卡的有效期 |
 * | 文件 `0018` 交易记录 | `2026-03-22 11:08:43` | 一笔消费的时间 |
 * | 目录内 `0005` / `0007` | `2026-03-22` | 未知 |
 *
 * 挑「最晚的那个」当有效期**只是猜测**：一张用了几年、最近才刷过的卡，
 * 交易时间完全可以晚于发卡批次里某个日期。
 * ⇒ 所以这里**原样列出、一律带「疑似」**，把判断权留给人。
 * 把推论写进用户可见的文案里，是最难被发现也最难改掉的一类错误。
 *
 * ## ⚠️ 为什么每个字段都可空、且卡片本身照常渲染
 *
 * 缺哪一行就不显示哪一行，但**卡片不能整个消失** ——
 * 换个学校的卡、或者这张卡里某个文件被清了，用户看到的应该是
 * 「这张卡只认出了卡号」，而不是「什么都没有」。
 */
internal object CardIdentityLogic {

    /**
     * 汇总出的一张「名片」。
     *
     * @param name 卡里读到的中文姓名（真卡在文件 `0016`，GBK）。
     * @param studentId 命中的学号；没填学号去比对、或卡里没有，就是 `null`。
     * @param studentIdNote 学号是**怎么**认出来的（含编码形式与偏移），给人核对用。
     * @param dates 卡上所有疑似日期（**原样**，不做筛选、不命名）。
     * @param cardNo 最主要的那个卡号候选。
     * @param cardNoNote 其余候选的说明（真卡上有 6 种解读方式，不能只报一种）。
     */
    data class CardIdentity(
        val name: String?,
        val studentId: String?,
        val studentIdNote: String?,
        val dates: List<String>,
        val cardNo: String?,
        val cardNoNote: String?,
    )

    /**
     * 从一次探测结果里汇总身份信息。
     *
     * @param probeLines 探测流水（每条可能带文本 / 学号命中 / 疑似日期）。
     * @param candidates 卡号候选（见 [NfcCardLogic.candidates]）。
     * @param expectedStudentId 用户填的学号 —— **只用来展示**，命中判定在探测时已经做过。
     */
    fun summarize(
        probeLines: List<ProbeLineLike>?,
        candidates: List<CardNoCandidate>,
        expectedStudentId: String? = null,
    ): CardIdentity {
        val lines = probeLines.orEmpty()

        // 姓名：卡里以 GBK 文本存的那一段。有多段就都列出来，不去猜哪段是姓名。
        val names = lines.mapNotNull { it.text?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct()

        // 学号：探测时已经比对过，这里只把「命中说明」带出来。
        val hit = lines.firstOrNull { !it.studentIdHit.isNullOrBlank() }
        val studentId = hit?.studentIdHit?.let { expectedStudentId ?: it }

        // 日期：全部照收，不排序、不挑最晚、不命名。
        val dates = lines.flatMap { it.dates }.distinct()

        val cardNo = candidates.firstOrNull()
        val cardNoNote = if (candidates.size > 1) {
            "共 ${candidates.size} 种解读，如「${candidates[1].label}」= ${candidates[1].value}"
        } else {
            null
        }

        return CardIdentity(
            name = names.firstOrNull(),
            studentId = studentId,
            studentIdNote = hit?.studentIdHit,
            dates = dates,
            cardNo = cardNo?.value,
            cardNoNote = cardNoNote?.let { "$it；主候选是「${cardNo?.label}」" } ?: cardNo?.label,
        )
    }

    /**
     * 探测流水的**最小接口**。
     *
     * 为什么不让 UI 直接给 `ProbeLine`：`ProbeLine` 定义在 Controller 里（带 APDU、
     * 状态字等一堆展示字段），而汇总只需要三样。抽一个窄接口出来，
     * 这一层的单测就不用为了造一条数据去填 APDU 和状态字。
     */
    interface ProbeLineLike {
        val text: String?
        val studentIdHit: String?
        val dates: List<String>
    }
}
