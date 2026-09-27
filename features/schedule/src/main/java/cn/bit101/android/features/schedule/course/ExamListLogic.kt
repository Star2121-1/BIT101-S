package cn.bit101.android.features.schedule.course

import cn.bit101.android.config.setting.base.hm
import cn.bit101.android.data.database.entity.ExamScheduleEntity
import cn.bit101.android.data.database.entity.endAt
import cn.bit101.android.data.database.entity.startAt
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 考试列表的**纯逻辑**（状态判定、排序、文案）。
 *
 * 为什么要抽出来：这里最容易错的不是界面，而是
 * 「哪一场该排在最前面」和「还剩多久」—— 而这两件事在真机上要等好几天
 * 才验证得了一次（考试日期不是随便能造的）。抽成纯函数就能立刻单测。
 *
 * 与 `NotifyLogic` 共用 `ExamScheduleEntity.startAt` / `endAt`（定义在 data 层），
 * 保证「列表里显示的倒计时」与「提醒在什么时候响」永远对得上。
 */
object ExamListLogic {

    /** 一场考试相对于「现在」的状态。 */
    enum class Status {
        /** 还没开始。 */
        UPCOMING,

        /** 正在进行（已开始、未结束）。 */
        ONGOING,

        /** 已经结束。 */
        FINISHED,
    }

    /**
     * 判状态。
     *
     * 边界口径：
     * - **刚好到开始时刻算「进行中」**（不是未开始）—— 那一刻人应该已经在考场了
     * - **刚好到结束时刻算「已结束」** —— 写 `!endAt.isAfter(now)` 而不是
     *   `endAt.isBefore(now)`，正是为了让这个边界落在「已结束」而不是「进行中」：
     *   考试都到点了还显示「进行中」会让人以为没考完
     */
    fun status(exam: ExamScheduleEntity, now: LocalDateTime): Status = when {
        exam.startAt.isAfter(now) -> Status.UPCOMING
        !exam.endAt.isAfter(now) -> Status.FINISHED
        else -> Status.ONGOING
    }

    /**
     * 列表顺序：
     * 1. **正在进行**的排最前 —— 用户打开列表最需要马上知道「我是不是正在错过考试」
     * 2. 未开始的按开始时刻**升序** —— 下一场在最前
     * 3. 已结束的按开始时刻**降序**垫底 —— 最近考过的靠前，越早考的越靠后
     */
    fun sorted(exams: List<ExamScheduleEntity>, now: LocalDateTime): List<ExamScheduleEntity> {
        val ongoing = mutableListOf<ExamScheduleEntity>()
        val upcoming = mutableListOf<ExamScheduleEntity>()
        val finished = mutableListOf<ExamScheduleEntity>()

        exams.forEach { exam ->
            when (status(exam, now)) {
                Status.ONGOING -> ongoing += exam
                Status.UPCOMING -> upcoming += exam
                Status.FINISHED -> finished += exam
            }
        }

        return ongoing.sortedBy { it.startAt } +
                upcoming.sortedBy { it.startAt } +
                finished.sortedByDescending { it.startAt }
    }

    /**
     * 日期的人话描述 + 开始时刻：`今天 08:00` / `明天 08:00` / `9月30日 08:00`。
     *
     * 跨年才写年份 —— 学生看到的考试基本都在几周内，写年份反而更长。
     */
    fun whenText(exam: ExamScheduleEntity, now: LocalDateTime): String {
        val date = exam.date
        val dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), date)
        val day = when {
            dayDiff == 0L -> "今天"
            dayDiff == 1L -> "明天"
            dayDiff == 2L -> "后天"
            date.year == now.year -> "${date.monthValue}月${date.dayOfMonth}日"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
        return "$day ${hm(exam.beginTime)}"
    }

    /**
     * 还剩多久：`还有 3 天` / `还有 5 小时` / `还有 40 分钟` / `马上开始`；
     * 正在进行写 `进行中`，已结束写 `已结束`。
     *
     * ⚠️ **最小单位是分钟**、不写秒：秒级倒计时在静态列表里既看不清也不会自己刷新，
     * 只会让人以为卡住了。
     */
    fun remainingText(exam: ExamScheduleEntity, now: LocalDateTime): String =
        when (status(exam, now)) {
            Status.ONGOING -> "进行中"
            Status.FINISHED -> "已结束"
            Status.UPCOMING -> {
                val minutes = ChronoUnit.MINUTES.between(now, exam.startAt)
                when {
                    minutes < 1L -> "马上开始"
                    minutes < 60L -> "还有 $minutes 分钟"
                    minutes < 60L * 24L -> "还有 ${minutes / 60L} 小时"
                    else -> "还有 ${minutes / (60L * 24L)} 天"
                }
            }
        }
}
