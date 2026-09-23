package cn.bit101.android.data.repo.base

import cn.bit101.android.data.score.ScoreLogic.ScoreEntry
import com.google.gson.JsonElement

/**
 * 成绩数据（BIT101 `/scores`）。
 *
 * 主要为「出分提醒」服务：拉一次成绩表 → 与本地快照差分 → 报告新课。
 * 分数本身**只存在本地快照文件里**，不出现在任何通知文案中（用户定的隐私边界）。
 */
interface ScoreRepo {

    /** 拉原始成绩表；未登录/失败返回 null（调用方静默跳过）。 */
    suspend fun fetchTable(): JsonElement?

    /**
     * 同步并差分。
     *
     * @return 本次**新出分**的课程。首次运行只建立基线、返回空；
     *   解析失败 / 未登录也返回空 —— 出分提醒是尽力而为，绝不做错。
     */
    suspend fun syncAndDiff(): List<ScoreEntry>
}
