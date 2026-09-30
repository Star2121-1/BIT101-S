package cn.bit101.android.data.score

import android.content.Context
import cn.bit101.android.data.common.TextFileCache
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 最近一次成绩检查的结果。
 *
 * ## 为什么要有它
 *
 * 成绩检查走的是「用账密做一次学校 SSO 登录」的异步流程，**是否被学校风控拦下
 * 由学校决定**（可能返回 `waiting_sms`），而检查又是后台静默跑的 ——
 * 不给用户一个可见状态的话，「出分提醒为什么没响」完全无从判断。
 *
 * 存成一个小文件（`filesDir/score_last_check_status`），格式 `毫秒|code|条数`。
 */
object ScoreCheckStore {

    /** 检查结果分类。 */
    enum class Code {
        /** 成功拿到成绩表（[Status.count] 是解析出的课程数）。 */
        OK,

        /** 学校要求短信二次验证 —— 后台没有交互通道，本次放弃。 */
        NEED_SMS,

        /** 轮询超时（挑战一直没就绪）。 */
        TIMEOUT,

        /**
         * **学校未通过这次登录**（挑战 `status = failed`）。
         *
         * ⚠️ 与 [FAILED] 分开是**必须的**：这条的典型原因是「学号或密码不对」，
         * 属于**重试永远不会成功**的那种失败 —— 归成「稍后重试」会让用户一直等下去。
         * 真正该做的动作是去检查/重设密码，所以文案必须说清，并把服务端原因带上
         * （见 [Status.detail]）。
         */
        AUTH_FAILED,

        /** 网络 / 接口异常（这类才值得「稍后重试」）。 */
        FAILED,

        /** 没登录（学号密码为空）。 */
        NOT_LOGGED_IN,
    }

    /**
     * @param count 解析出的课程数（仅 [Code.OK] 有意义）
     * @param detail 服务端给的失败原因原文（如 `用户名或密码错误 [status=401, …]`）。
     *   存**完整**原文（含括注的技术细节）—— 展示时用 [humanReason] 取人话部分。
     */
    data class Status(
        val atMillis: Long,
        val code: Code,
        val count: Int = 0,
        val detail: String? = null,
    )

    private const val FILE_NAME = "score_last_check_status"

    fun read(context: Context): Status? =
        TextFileCache.read(context, FILE_NAME)?.let(::parse)

    fun write(context: Context, status: Status) =
        TextFileCache.write(context, FILE_NAME, encode(status))

    fun encode(status: Status): String = buildString {
        append(status.atMillis).append('|').append(status.code.name).append('|').append(status.count)
        // detail 可选：没有就不写第 4 段（老格式照样能被 parse 读）
        sanitizeDetail(status.detail)?.let { append('|').append(it) }
    }

    /**
     * 解析存储串；形状不对返回 null（不猜）。
     *
     * 兼容两种格式：老的三段 `时刻|CODE|条数`、新的四段 `时刻|CODE|条数|原因`。
     */
    fun parse(raw: String?): Status? {
        val parts = raw?.trim()?.split('|') ?: return null
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val code = Code.entries.firstOrNull { it.name == parts[1] } ?: return null
        // 第 4 段之后重新拼起来：万一原因里混进了 `|` 也不至于丢内容
        val detail = parts.drop(3).joinToString("|").takeIf { it.isNotBlank() }
        return Status(at, code, parts.getOrNull(2)?.toIntOrNull() ?: 0, detail)
    }

    /**
     * 服务端原因里**对人有用**的那一段。
     *
     * 真实形状是 `用户名或密码错误 [status=401, redirects=0, risk=ustc-token, flow=replaced]`
     * —— 方括号里是排查用的技术细节，直接摆在设置页只会淹没真正的原因。
     * 所以只取 `[` 之前；没有方括号时原样返回（不猜格式）。
     */
    fun humanReason(detail: String?): String? =
        detail?.substringBefore('[')?.trim()?.takeIf { it.isNotBlank() }?.take(60)

    /** 落盘前净化（分隔符规则见 [TextFileCache.sanitizeField]），并限长（诊断够用即可）。 */
    private fun sanitizeDetail(detail: String?): String? =
        detail?.let { TextFileCache.sanitizeField(it) }?.takeIf { it.isNotBlank() }?.take(200)

    /** 设置页显示文案（纯函数，可单测）。 */
    fun statusText(status: Status?): String {
        if (status == null) return "还没检查过（下次打开 App 时检查）"
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(status.atMillis).atZone(ZoneId.systemDefault()))
        val what = when (status.code) {
            Code.OK -> "已同步 ${status.count} 门课"
            Code.NEED_SMS -> "学校要求短信验证，已暂停自动检查"
            Code.TIMEOUT -> "认证超时，稍后重试"
            // ⚠️ 这条**不能**写「稍后重试」：学校明确拒绝了登录，重试还是同样的结果。
            // 服务端原话（如「用户名或密码错误」）比任何转述都准，直接带出来
            Code.AUTH_FAILED -> buildString {
                append("学校未通过本次登录")
                humanReason(status.detail)?.let { append("：").append(it) }
                append("（重试无效，请先确认学号与密码）")
            }
            Code.FAILED -> "获取失败，稍后重试"
            Code.NOT_LOGGED_IN -> "未登录，先登录才能检查"
        }
        return "最近检查：$time · $what"
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
}
