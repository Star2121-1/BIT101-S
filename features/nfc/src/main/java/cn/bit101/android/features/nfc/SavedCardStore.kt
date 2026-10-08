package cn.bit101.android.features.nfc

import android.content.Context
import cn.bit101.android.features.nfc.logic.SavedCardLogic
import cn.bit101.android.features.nfc.logic.SavedCardLogic.SavedCard

/**
 * 「我的校园卡」名片的本机读写。读写规则全在 [SavedCardLogic]，这里只负责碰文件。
 *
 * ## 为什么用 `SharedPreferences` 而不是 `:config` 的 DataStore
 *
 * 本模块不引 Hilt 注入链，页面是纯 Compose 直读 Context；而这里要存的只是
 * 五个小字符串，用不上 DataStore 那一套 Flow。与 `features:widget` 的
 * `WidgetPageStore` 保持同一种写法，比在同一个 App 里并存两套存储机制要好。
 *
 * ## ⚠️ 为什么不用加密存储
 *
 * 这里存的是姓名与学号 —— 它们本来就会**显示在设置页上**，App 内任何地方都能看到，
 * 不是凭据（没有密码、没有 token）。`MODE_PRIVATE` 已经挡住了其他 App。
 * 真正需要加密的是账号密码，那批在 `:config` 的加密存储里，不要混进来。
 * 用户可以一键清除（[clear]），这也是把字段控制在最小集合的理由。
 */
internal object SavedCardStore {

    private const val PREFS = "bit101_nfc_card"

    /** 学号输入框里的内容 —— 与「卡片认出的学号」分开存，原因见 [readStudentId]。 */
    private const val KEY_PROBE_STUDENT_ID = "probe_student_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读名片；没存过返回 `null`。 */
    fun read(context: Context): SavedCard? =
        runCatching { SavedCardLogic.decode(prefs(context).all) }.getOrNull()

    /** 写名片（覆盖式：合并规则已由调用方在 [SavedCardLogic.merge] 里算好）。 */
    fun save(context: Context, card: SavedCard) {
        runCatching {
            val edit = prefs(context).edit()
            SavedCardLogic.encode(card).forEach { (key, value) -> edit.putString(key, value) }
            // apply()：内存立刻可见、磁盘异步落，不阻塞主线程上的 Compose 重组
            edit.apply()
        }
    }

    /**
     * 忘掉这张卡。
     *
     * ⚠️ **只清卡片那几个键** —— 学号是用户**自己填的**设置项（见 [readStudentId]），
     * 跟着卡片一起清掉的话，用户下次打开会发现比对也不灵了，还不知道为什么。
     */
    fun clear(context: Context) {
        runCatching {
            val edit = prefs(context).edit()
            listOf(
                SavedCardLogic.KEY_NAME,
                SavedCardLogic.KEY_STUDENT_ID,
                SavedCardLogic.KEY_CARD_NO,
                SavedCardLogic.KEY_UID,
                SavedCardLogic.KEY_SAVED_AT,
            ).forEach { edit.remove(it) }
            edit.apply()
        }
    }

    /**
     * 学号输入框里的内容。
     *
     * ## 为什么不直接拿「卡片认出的学号」
     *
     * 这是**两样东西**：卡片认出的学号是卡的属性（换了卡就该变），
     * 而输入框里的学号是「我是谁」—— 它决定探测时要去比什么，
     * 即使今天没贴卡也应该留着。混成一个字段的话，换张卡就把用户自己的学号冲掉了。
     */
    fun readStudentId(context: Context): String =
        runCatching { prefs(context).getString(KEY_PROBE_STUDENT_ID, "").orEmpty() }.getOrDefault("")

    fun saveStudentId(context: Context, studentId: String) {
        runCatching {
            prefs(context).edit().putString(KEY_PROBE_STUDENT_ID, studentId.trim()).apply()
        }
    }
}
