package cn.bit101.android.features.seat.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 学校二次验证挑战：验证码已发往 [maskedPhone]，等待用户输入。 */
data class SeatSmsChallenge(
    val maskedPhone: String,
    val purpose: String = ""
) {
    /** 界面上的提示语。 */
    val hint: String
        get() = "验证码已发送至 ${maskedPhone.ifBlank { "绑定手机" }}"
}

/**
 * 短信验证码的「请求 → 等待 → 提交」通道。
 *
 * 学校在风控触发时要求短信二次验证：登录流程发完短信后必须**挂起**，
 * 等用户在界面上输入验证码再继续。这里用 [CompletableDeferred] 把
 * 「网络侧等待」与「界面侧输入」解耦，两侧通过 [challenge] 状态流对接。
 *
 * 只允许一个进行中的请求（登录本身是单飞操作）。
 */
@Singleton
class SeatSmsChallengeHub @Inject constructor() {

    private var pending: CompletableDeferred<String>? = null

    private val _challenge = MutableStateFlow<SeatSmsChallenge?>(null)
    val challenge: StateFlow<SeatSmsChallenge?> = _challenge.asStateFlow()

    /** 由登录流程调用：展示输入界面并挂起，直到用户提交或取消。 */
    suspend fun awaitCode(maskedPhone: String, purpose: String): String {
        val deferred = CompletableDeferred<String>()
        synchronized(this) { pending = deferred }
        _challenge.value = SeatSmsChallenge(maskedPhone = maskedPhone, purpose = purpose)
        return try {
            deferred.await()
        } finally {
            _challenge.value = null
            synchronized(this) { if (pending === deferred) pending = null }
        }
    }

    /** 用户在界面上提交验证码。 */
    fun submit(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        synchronized(this) { pending }?.complete(trimmed)
    }

    /** 用户放弃输入（或界面被销毁）。 */
    fun cancel(reason: String = "已取消短信验证码输入") {
        synchronized(this) { pending }?.completeExceptionally(IOException(reason))
    }
}
