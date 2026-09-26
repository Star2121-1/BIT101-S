package cn.bit101.android.data.score

import android.content.Context
import java.io.File
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

        /** 网络 / 接口异常。 */
        FAILED,

        /** 没登录（学号密码为空）。 */
        NOT_LOGGED_IN,
    }

    data class Status(val atMillis: Long, val code: Code, val count: Int = 0)

    private const val FILE_NAME = "score_last_check_status"

    fun read(context: Context): Status? =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrNull()?.let(::parse)

    fun write(context: Context, status: Status) {
        runCatching { File(context.filesDir, FILE_NAME).writeText(encode(status)) }
    }

    fun encode(status: Status): String = "${status.atMillis}|${status.code.name}|${status.count}"

    /** 解析存储串；形状不对返回 null（不猜）。 */
    fun parse(raw: String?): Status? {
        val parts = raw?.trim()?.split('|') ?: return null
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val code = Code.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Status(at, code, parts.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    /** 设置页显示文案（纯函数，可单测）。 */
    fun statusText(status: Status?): String {
        if (status == null) return "还没检查过（下次打开 App 时检查）"
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(status.atMillis).atZone(ZoneId.systemDefault()))
        val what = when (status.code) {
            Code.OK -> "已同步 ${status.count} 门课"
            Code.NEED_SMS -> "学校要求短信验证，已暂停自动检查"
            Code.TIMEOUT -> "认证超时，稍后重试"
            Code.FAILED -> "获取失败，稍后重试"
            Code.NOT_LOGGED_IN -> "未登录，先登录才能检查"
        }
        return "最近检查：$time · $what"
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
}
